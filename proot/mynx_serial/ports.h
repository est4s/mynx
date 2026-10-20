#ifndef MYNX_SERIAL_PORTS_H
#define MYNX_SERIAL_PORTS_H

#include <stddef.h>

/* Whether request is one Android refuses on a pty (EACCES). */
int mynx_serial_refused(unsigned long request);

/* The port NAME in dir whose link points at tty (a pty's path), into name; 0 if found. */
int mynx_serial_port(const char *dir, const char *tty, char *name, size_t size);

/* Does a refused request for port name: 0, or -errno. arg is the int the
 * request reads or writes (TIOCM*), or its value (TCSBRK, TCXONC). */
int mynx_serial_ioctl(const char *dir, const char *name, unsigned long request, int *arg);

/* Where a program's stand-in uevent socket is bound: dir/.uevent/PID-N; -1 if too long. */
int mynx_uevent_path(const char *dir, int pid, unsigned n, char *path, size_t size);

#endif
