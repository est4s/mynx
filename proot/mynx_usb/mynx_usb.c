/*
 * mynx_usb: a proot extension that lets programs open USB devices, turned
 * on by MYNX_USB_SOCKET in proot's environment (the app's socket, a host
 * path).
 *
 * Android won't let apps open /dev/bus/usb/BBB/DDD, but the fd its
 * UsbManager.openDevice() gives takes the same usbfs ioctls, and the app
 * hands one out on its socket: send "BBB/DDD\n", get "ok\n" with the fd
 * (SCM_RIGHTS), or "err ERRNO\n". A program's open of the node becomes
 * that exchange, made by the program itself as calls proot chains, so the
 * fd lands in the program: socket, connect, write, recvmsg, close. It's
 * done in proot, not the preload, because some programs make the system
 * calls themselves (Rust's nusb, Go).
 */
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <unistd.h>
#include <talloc.h>

#include "extension/extension.h"
#include "syscall/chain.h"
#include "syscall/sysnum.h"
#include "tracee/mem.h"
#include "tracee/reg.h"
#include "extension/mynx_usb/devices.h"

typedef enum { IDLE, SOCKET, CONNECT, WRITE, RECEIVE, CLOSE } Step;

/* What the program's calls use, written below its stack pointer: free
 * while it's stopped, as sysvipc does. Pointers are the program's. */
typedef struct {
	struct msghdr message;
	struct iovec iov;
	struct sockaddr_un address;
	char request[8];
	char reply[32];
	union {
		struct cmsghdr header;
		char space[CMSG_SPACE(sizeof(int))];
	} control;
} Exchange;

typedef struct {
	const char *socket;
	Step step;
	int fd;
	int receive_flags;
	word_t at;
} Config;

/* No FILTER_SYSEXIT: every open would stop at its exit. Only a device's
 * asks for that stop (enter()), as link2symlink does.  */
static FilteredSysnum filtered_sysnums[] = {
	{ PR_open,	0 },
	{ PR_openat,	0 },
	FILTERED_SYSNUM_END,
};

static int enter(Tracee *tracee, Config *config)
{
	Reg path_reg, flags_reg;
	switch (get_sysnum(tracee, ORIGINAL)) {
	case PR_open:
		path_reg = SYSARG_1;
		flags_reg = SYSARG_2;
		break;
	case PR_openat:
		path_reg = SYSARG_2;
		flags_reg = SYSARG_3;
		break;
	default:
		return 0;
	}
	char path[PATH_MAX], dev[8];
	if (read_string(tracee, path, peek_reg(tracee, CURRENT, path_reg), sizeof path) <= 0
	    || mynx_usb_device(path, dev, sizeof dev) != 0)
		return 0;

	int flags = (int) peek_reg(tracee, CURRENT, flags_reg);
	/* 256 more: clear of x86-64's red zone (arm64 has none).  */
	word_t at = (peek_reg(tracee, CURRENT, STACK_POINTER) - 256 - sizeof(Exchange)) & ~(word_t) 15;
	Exchange exchange;
	memset(&exchange, 0, sizeof exchange);
	exchange.address.sun_family = AF_UNIX;
	if (strlen(config->socket) >= sizeof exchange.address.sun_path)
		return -ENAMETOOLONG;
	strcpy(exchange.address.sun_path, config->socket);
	memcpy(exchange.request, dev, 7);
	exchange.request[7] = '\n';
	exchange.iov.iov_base = (void *) (at + offsetof(Exchange, reply));
	exchange.iov.iov_len = sizeof exchange.reply;
	exchange.message.msg_iov = (void *) (at + offsetof(Exchange, iov));
	exchange.message.msg_iovlen = 1;
	exchange.message.msg_control = (void *) (at + offsetof(Exchange, control));
	exchange.message.msg_controllen = sizeof exchange.control;
	if (write_data(tracee, at, &exchange, sizeof exchange) < 0)
		return -EFAULT;

	/* The open becomes the socket; the rest is chained at its exit.  */
	set_sysnum(tracee, PR_socket);
	poke_reg(tracee, SYSARG_1, AF_UNIX);
	poke_reg(tracee, SYSARG_2, SOCK_STREAM | SOCK_CLOEXEC);
	poke_reg(tracee, SYSARG_3, 0);
	config->step = SOCKET;
	config->at = at;
	tracee->sysexit_pending = true;
	tracee->restart_how = PTRACE_SYSCALL;
	config->receive_flags = (flags & O_CLOEXEC) ? MSG_CMSG_CLOEXEC : 0;
	return 0;
}

static void chain(Tracee *tracee, Config *config, Step step, Sysnum sysnum, word_t arg2, word_t arg3)
{
	if (register_chained_syscall(tracee, sysnum, config->fd, arg2, arg3, 0, 0, 0) < 0) {
		config->step = IDLE;
		return;
	}
	config->step = step;
}

/* The socket is made: on to connect, with -EIO as the result until the fd comes. */
static void socket_made(Tracee *tracee, Config *config)
{
	int fd = (int) peek_reg(tracee, CURRENT, SYSARG_RESULT);
	if (fd < 0)
		return;
	config->fd = fd;
	force_chain_final_result(tracee, -EIO);
	chain(tracee, config, CONNECT, PR_connect, config->at + offsetof(Exchange, address), sizeof(struct sockaddr_un));
}

static int received(Tracee *tracee, Config *config, long length)
{
	Exchange exchange;
	if (length < 0 || read_data(tracee, &exchange, config->at, sizeof exchange) < 0)
		return -EIO;
	int fd = -1;
	/* The program's pointers in the header: find the fd by offset instead.  */
	if (exchange.message.msg_controllen >= CMSG_LEN(sizeof(int))
	    && exchange.control.header.cmsg_level == SOL_SOCKET
	    && exchange.control.header.cmsg_type == SCM_RIGHTS)
		memcpy(&fd, CMSG_DATA(&exchange.control.header), sizeof fd);
	int result = mynx_usb_result(exchange.reply, length, fd);
	/* An error that came with an fd anyway: don't leave it open.  */
	if (result < 0 && fd >= 0)
		register_chained_syscall(tracee, PR_close, fd, 0, 0, 0, 0, 0);
	return result;
}

static void chained_exit(Tracee *tracee, Config *config)
{
	long result = (long) peek_reg(tracee, CURRENT, SYSARG_RESULT);
	switch (config->step) {
	case CONNECT:
		if (result < 0) {
			force_chain_final_result(tracee, result == -ENOENT || result == -ECONNREFUSED ? -EIO : result);
			chain(tracee, config, CLOSE, PR_close, 0, 0);
		} else {
			chain(tracee, config, WRITE, PR_write, config->at + offsetof(Exchange, request), 8);
		}
		break;
	case WRITE:
		if (result != 8) {
			force_chain_final_result(tracee, result < 0 ? result : -EIO);
			chain(tracee, config, CLOSE, PR_close, 0, 0);
		} else {
			chain(tracee, config, RECEIVE, PR_recvmsg, config->at + offsetof(Exchange, message), config->receive_flags);
		}
		break;
	case RECEIVE:
		force_chain_final_result(tracee, received(tracee, config, result));
		chain(tracee, config, CLOSE, PR_close, 0, 0);
		break;
	case CLOSE:
	default:
		config->step = IDLE;
		break;
	}
}

int mynx_usb_callback(Extension *extension, ExtensionEvent event, intptr_t data1, intptr_t data2 UNUSED)
{
	switch (event) {
	case INITIALIZATION: {
		Config *config = talloc_zero(extension, Config);
		if (config == NULL || data1 == 0)
			return -1;
		config->socket = talloc_strdup(config, (const char *) data1);
		extension->config = config;
		extension->filtered_sysnums = filtered_sysnums;
		return 0;
	}

	case INHERIT_PARENT:
		return 1;

	case INHERIT_CHILD: {
		Config *parent = talloc_get_type_abort(((Extension *) data1)->config, Config);
		Config *config = talloc_zero(extension, Config);
		if (config == NULL)
			return -1;
		config->socket = talloc_strdup(config, parent->socket);
		extension->config = config;
		return 0;
	}

	case SYSCALL_ENTER_END: {
		Config *config = talloc_get_type_abort(extension->config, Config);
		config->step = IDLE;
		if ((int) data1 < 0)
			return 0;
		return enter(TRACEE(extension), config);
	}

	case SYSCALL_EXIT_END: {
		Config *config = talloc_get_type_abort(extension->config, Config);
		if (config->step == SOCKET)
			socket_made(TRACEE(extension), config);
		else
			config->step = IDLE;
		return 0;
	}

	case SYSCALL_CHAINED_EXIT: {
		Config *config = talloc_get_type_abort(extension->config, Config);
		if (config->step != IDLE)
			chained_exit(TRACEE(extension), config);
		return 0;
	}

	default:
		return 0;
	}
}
