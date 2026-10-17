/*
 * libmynx-serial.so: what Android refuses on a USB serial port's
 * pseudo-terminal, done another way. Loaded into every program in
 * Debian (LD_PRELOAD, set by the app; the serial-preload setting).
 *
 * Android's SELinux refuses the modem-line ioctls (DTR, RTS), TIOCEXCL,
 * tcdrain() and flock() on a pty with EACCES. Each wrapper here calls
 * the real function first and only steps in when that failed with
 * EACCES on a /dev/serial port's pty; everything else passes through
 * as it was, errno kept. DTR and RTS go to the port's helper
 * (tools/lib/serial-port) over its control socket, /dev/serial/.NAME.ctl,
 * which sends them to the board.
 *
 * Static programs (Go's) don't load it.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#ifdef MYNX_SERIAL_TEST
/* Tests: what Android refuses is refused here too, without asking the
 * kernel (a desktop allows it), and the ports' folder is the test's. */
static const char *serial_dir(void)
{
    const char *dir = getenv("MYNX_SERIAL_DIR");
    return dir ? dir : "/dev/serial";
}
#define REFUSED() (errno = EACCES, -1)
#else
static const char *serial_dir(void) { return "/dev/serial"; }
#endif

#define REAL(name) \
    static __typeof__(name) *real; \
    if (!real) real = (__typeof__(name) *)dlsym(RTLD_NEXT, #name)

/* The port NAME whose link in the folder points at fd's pty, into name. */
static int port_name(int fd, char *name, size_t size)
{
    char proc[64], tty[256], link[512], target[256];
    snprintf(proc, sizeof proc, "/proc/self/fd/%d", fd);
    ssize_t n = readlink(proc, tty, sizeof tty - 1);
    if (n <= 0) return -1;
    tty[n] = 0;
    if (strncmp(tty, "/dev/pts/", 9) != 0) return -1;
    DIR *dir = opendir(serial_dir());
    if (!dir) return -1;
    int found = -1;
    struct dirent *e;
    while (found && (e = readdir(dir))) {
        if (e->d_name[0] == '.') continue;
        snprintf(link, sizeof link, "%s/%s", serial_dir(), e->d_name);
        n = readlink(link, target, sizeof target - 1);
        if (n <= 0) continue;
        target[n] = 0;
        if (strcmp(target, tty) == 0 && strlen(e->d_name) < size) {
            strcpy(name, e->d_name);
            found = 0;
        }
    }
    closedir(dir);
    return found;
}

/* Whether fd is a /dev/serial port's pty; errno stays as it was. */
static int is_port(int fd)
{
    char name[64];
    int saved = errno;
    int found = port_name(fd, name, sizeof name) == 0;
    errno = saved;
    return found;
}

/* Asks fd's helper "COMMAND VALUE"; its answer "ok LINES" (TIOCM_* bits) into lines. */
static int ask_helper(int fd, const char *command, int value, int *lines)
{
    char name[64];
    if (port_name(fd, name, sizeof name)) return -1;
    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    if ((size_t)snprintf(addr.sun_path, sizeof addr.sun_path, "%s/.%s.ctl", serial_dir(), name) >= sizeof addr.sun_path)
        return -1;
    int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) return -1;
    struct timeval timeout = {.tv_sec = 5};
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof timeout);
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof timeout);
    char buf[64];
    int len = snprintf(buf, sizeof buf, "%s %d\n", command, value);
    int ok = -1;
    if (connect(s, (struct sockaddr *)&addr, sizeof addr) == 0 && write(s, buf, len) == len) {
        size_t got = 0;
        ssize_t n;
        while (got < sizeof buf - 1 && (n = read(s, buf + got, sizeof buf - 1 - got)) > 0) {
            got += n;
            if (memchr(buf, '\n', got)) break;
        }
        buf[got] = 0;
        if (sscanf(buf, "ok %d", lines) == 1) ok = 0;
    }
    close(s);
    return ok;
}

static int modem_lines(int fd, unsigned long request, int *arg)
{
    int lines;
    const char *command = request == TIOCMGET ? "get" : request == TIOCMSET ? "set" : request == TIOCMBIS ? "bis" : "bic";
    if (!arg || ask_helper(fd, command, request == TIOCMGET ? 0 : *arg, &lines)) return -1;
    if (request == TIOCMGET) *arg = lines;
    return 0;
}

/* tcdrain(): wait until the helper has read everything written. */
static int drain(int fd)
{
    REAL(ioctl);
    int left;
    struct timespec pause = {0, 1000000};
    while (real(fd, TIOCOUTQ, &left) == 0) {
        if (left <= 0) return 0;
        nanosleep(&pause, NULL);
    }
    return -1;
}

static int is_refused(unsigned long request)
{
    switch (request) {
    case TIOCMGET: case TIOCMSET: case TIOCMBIS: case TIOCMBIC:
    case TIOCEXCL: case TIOCNXCL: case TCSBRK: case TCXONC:
        return 1;
    }
    return 0;
}

int ioctl(int fd, unsigned long request, ...)
{
    REAL(ioctl);
    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);
#ifdef MYNX_SERIAL_TEST
    int r = is_refused(request) && is_port(fd) ? REFUSED() : real(fd, request, arg);
#else
    int r = real(fd, request, arg);
#endif
    if (r != -1 || errno != EACCES || !is_refused(request) || !is_port(fd)) return r;
    int r2 = -1;
    switch (request) {
    case TIOCMGET: case TIOCMSET: case TIOCMBIS: case TIOCMBIC:
        r2 = modem_lines(fd, request, arg);
        break;
    case TIOCEXCL: case TIOCNXCL: case TCXONC:
        r2 = 0;  /* the helper serves one program at a time already */
        break;
    case TCSBRK:
        if ((int)(long)arg) r2 = drain(fd);  /* 0 is a break, which stays refused */
        break;
    }
    if (r2 == -1) errno = EACCES;
    return r2;
}

/* glibc's tcdrain(), tcflow() and flock() make the system call themselves, not through ioctl(). */
int tcdrain(int fd)
{
    REAL(tcdrain);
#ifdef MYNX_SERIAL_TEST
    int r = is_port(fd) ? REFUSED() : real(fd);
#else
    int r = real(fd);
#endif
    if (r != -1 || errno != EACCES || !is_port(fd)) return r;
    if (drain(fd) == 0) return 0;
    errno = EACCES;
    return -1;
}

int tcflow(int fd, int action)
{
    REAL(tcflow);
#ifdef MYNX_SERIAL_TEST
    int r = is_port(fd) ? REFUSED() : real(fd, action);
#else
    int r = real(fd, action);
#endif
    if (r != -1 || errno != EACCES || !is_port(fd)) return r;
    return 0;
}

int flock(int fd, int operation)
{
    REAL(flock);
#ifdef MYNX_SERIAL_TEST
    int r = is_port(fd) ? REFUSED() : real(fd, operation);
#else
    int r = real(fd, operation);
#endif
    if (r != -1 || errno != EACCES || !is_port(fd)) return r;
    return 0;  /* one program at a time already, as above */
}
