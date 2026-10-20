/*
 * mynx_serial: a proot extension for programs the serial preload library
 * can't reach (static ones, like Go's), turned on by MYNX_SERIAL_DIR in
 * proot's environment, the host folder of the /dev/serial ports.
 *
 * - The calls Android refuses on a port's pty (modem lines, TIOCEXCL, …)
 *   are answered here: ports.c asks the port's helper, as the preload does.
 * - Android refuses apps the kernel's uevent socket, which tools watch for
 *   ports coming and going. A program's socket(AF_NETLINK, …,
 *   NETLINK_KOBJECT_UEVENT) becomes a Unix datagram socket bound at
 *   DIR/.uevent/PID-N, where the app sends the kernel's messages, and its
 *   bind(AF_NETLINK) on it succeeds.
 */
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>
#include <linux/netlink.h>
#include <talloc.h>

#include "extension/extension.h"
#include "syscall/chain.h"
#include "syscall/sysnum.h"
#include "tracee/mem.h"
#include "tracee/reg.h"
#include "extension/mynx_serial/ports.h"

typedef enum { IDLE, ANSWERED, SOCKET, BIND } Step;

typedef struct {
	const char *dir;
	Step step;
	int fd;
} Config;

/* The stand-in uevent sockets' inodes, to know a bind(AF_NETLINK) on one.
 * Inodes aren't reused while a socket lives; the oldest are forgotten. */
static unsigned long uevent_inodes[64];
static unsigned uevent_count;

static FilteredSysnum filtered_sysnums[] = {
	{ PR_ioctl,	FILTER_SYSEXIT },
	{ PR_socket,	FILTER_SYSEXIT },
	{ PR_bind,	FILTER_SYSEXIT },
	FILTERED_SYSNUM_END,
};

/* What the tracee's fd is, as /proc says: a path, or "socket:[INODE]". */
static int fd_link(const Tracee *tracee, int fd, char *link, size_t size)
{
	char proc[64];
	snprintf(proc, sizeof proc, "/proc/%d/fd/%d", tracee->pid, fd);
	ssize_t n = readlink(proc, link, size - 1);
	if (n <= 0)
		return -1;
	link[n] = 0;
	return 0;
}

static unsigned long socket_inode(const Tracee *tracee, int fd)
{
	char link[64];
	unsigned long inode;
	if (fd_link(tracee, fd, link, sizeof link) || sscanf(link, "socket:[%lu]", &inode) != 1)
		return 0;
	return inode;
}

static bool is_uevent_socket(const Tracee *tracee, int fd)
{
	unsigned long inode = socket_inode(tracee, fd);
	for (size_t i = 0; inode && i < sizeof uevent_inodes / sizeof *uevent_inodes; i++)
		if (uevent_inodes[i] == inode)
			return true;
	return false;
}

/* The tracee's call answered here: no system call, success. */
static void answer(Tracee *tracee, Config *config)
{
	set_sysnum(tracee, PR_void);
	config->step = ANSWERED;
}

static int enter(Tracee *tracee, Config *config)
{
	switch (get_sysnum(tracee, ORIGINAL)) {
	case PR_ioctl: {
		unsigned long request = peek_reg(tracee, CURRENT, SYSARG_2);
		char tty[PATH_MAX], name[64];
		if (!mynx_serial_refused(request)
		    || fd_link(tracee, peek_reg(tracee, CURRENT, SYSARG_1), tty, sizeof tty)
		    || mynx_serial_port(config->dir, tty, name, sizeof name))
			return 0;
		word_t address = peek_reg(tracee, CURRENT, SYSARG_3);
		bool pointer = request == TIOCMGET || request == TIOCMSET || request == TIOCMBIS || request == TIOCMBIC;
		int arg = (int) address;
		if (pointer && request != TIOCMGET && read_data(tracee, &arg, address, sizeof arg) < 0)
			return -EFAULT;
		int status = mynx_serial_ioctl(config->dir, name, request, &arg);
		if (status < 0)
			return status;
		if (request == TIOCMGET && write_data(tracee, address, &arg, sizeof arg) < 0)
			return -EFAULT;
		answer(tracee, config);
		return 0;
	}
	case PR_socket:
		if ((int) peek_reg(tracee, CURRENT, SYSARG_1) != AF_NETLINK
		    || (int) peek_reg(tracee, CURRENT, SYSARG_3) != NETLINK_KOBJECT_UEVENT)
			return 0;
		poke_reg(tracee, SYSARG_1, AF_UNIX);
		poke_reg(tracee, SYSARG_2, SOCK_DGRAM | (peek_reg(tracee, CURRENT, SYSARG_2) & (SOCK_NONBLOCK | SOCK_CLOEXEC)));
		poke_reg(tracee, SYSARG_3, 0);
		config->step = SOCKET;
		return 0;
	case PR_bind: {
		sa_family_t family;
		int fd = peek_reg(tracee, CURRENT, SYSARG_1);
		if (read_data(tracee, &family, peek_reg(tracee, CURRENT, SYSARG_2), sizeof family) < 0
		    || family != AF_NETLINK || !is_uevent_socket(tracee, fd))
			return 0;
		answer(tracee, config);
		return 0;
	}
	default:
		return 0;
	}
}

/* The stand-in socket is made: bind it where the app sends uevents, before the tracee sees it. */
static void bind_uevent_socket(Tracee *tracee, Config *config)
{
	int fd = (int) peek_reg(tracee, CURRENT, SYSARG_RESULT);
	struct sockaddr_un addr = { .sun_family = AF_UNIX };
	if (fd < 0 || mynx_uevent_path(config->dir, tracee->pid, ++uevent_count, addr.sun_path, sizeof addr.sun_path))
		return;
	unlink(addr.sun_path);
	/* Below the stack pointer, as sysvipc does: free while the tracee is stopped.  */
	word_t at = peek_reg(tracee, CURRENT, STACK_POINTER) - sizeof addr;
	if (write_data(tracee, at, &addr, sizeof addr) < 0
	    || register_chained_syscall(tracee, PR_bind, fd, at, sizeof addr, 0, 0, 0) < 0)
		return;
	force_chain_final_result(tracee, fd);
	config->step = BIND;
	config->fd = fd;
}

int mynx_serial_callback(Extension *extension, ExtensionEvent event, intptr_t data1, intptr_t data2 UNUSED)
{
	switch (event) {
	case INITIALIZATION: {
		Config *config = talloc_zero(extension, Config);
		if (config == NULL || data1 == 0)
			return -1;
		config->dir = talloc_strdup(config, (const char *) data1);
		char uevent_dir[PATH_MAX];
		snprintf(uevent_dir, sizeof uevent_dir, "%s/.uevent", config->dir);
		mkdir(uevent_dir, 0700);
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
		config->dir = talloc_strdup(config, parent->dir);
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
		Tracee *tracee = TRACEE(extension);
		Config *config = talloc_get_type_abort(extension->config, Config);
		Step step = config->step;
		config->step = IDLE;
		if (step == ANSWERED)
			poke_reg(tracee, SYSARG_RESULT, 0);
		else if (step == SOCKET)
			bind_uevent_socket(tracee, config);
		return 0;
	}

	case SYSCALL_CHAINED_EXIT: {
		Tracee *tracee = TRACEE(extension);
		Config *config = talloc_get_type_abort(extension->config, Config);
		if (config->step != BIND)
			return 0;
		config->step = IDLE;
		if ((int) peek_reg(tracee, CURRENT, SYSARG_RESULT) == 0)
			uevent_inodes[uevent_count % (sizeof uevent_inodes / sizeof *uevent_inodes)] = socket_inode(tracee, config->fd);
		return 0;
	}

	default:
		return 0;
	}
}
