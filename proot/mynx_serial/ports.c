/*
 * What the mynx_serial extension decides, apart from proot: which calls
 * Android refuses on a pty, which pty is a /dev/serial port, and asking
 * the port's helper (tools/lib/serial-port) over its control socket,
 * DIR/.NAME.ctl, as tools/src/libmynx-serial.c does. Paths are the host's.
 */
#include <dirent.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

#include "ports.h"

int mynx_serial_refused(unsigned long request)
{
    switch (request) {
    case TIOCMGET: case TIOCMSET: case TIOCMBIS: case TIOCMBIC:
    case TIOCEXCL: case TIOCNXCL: case TCSBRK: case TCXONC:
        return 1;
    }
    return 0;
}

int mynx_serial_port(const char *dir, const char *tty, char *name, size_t size)
{
    char link[512], target[256];
    if (strncmp(tty, "/dev/pts/", 9) != 0) return -1;
    DIR *d = opendir(dir);
    if (!d) return -1;
    int found = -1;
    struct dirent *e;
    while (found && (e = readdir(d))) {
        if (e->d_name[0] == '.') continue;
        snprintf(link, sizeof link, "%s/%s", dir, e->d_name);
        ssize_t n = readlink(link, target, sizeof target - 1);
        if (n <= 0) continue;
        target[n] = 0;
        if (strcmp(target, tty) == 0 && strlen(e->d_name) < size) {
            strcpy(name, e->d_name);
            found = 0;
        }
    }
    closedir(d);
    return found;
}

/* Asks the helper "COMMAND VALUE"; its answer "ok LINES" into lines. */
static int ask_helper(const char *dir, const char *name, const char *command, int value, int *lines)
{
    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    if ((size_t)snprintf(addr.sun_path, sizeof addr.sun_path, "%s/.%s.ctl", dir, name) >= sizeof addr.sun_path)
        return -1;
    int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) return -1;
    /* proot waits for the answer with every program it runs stopped: keep it short. */
    struct timeval timeout = {.tv_sec = 2};
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

int mynx_serial_ioctl(const char *dir, const char *name, unsigned long request, int *arg)
{
    int lines;
    switch (request) {
    case TIOCMGET:
        if (ask_helper(dir, name, "get", 0, &lines)) return -EACCES;
        *arg = lines;
        return 0;
    case TIOCMSET: case TIOCMBIS: case TIOCMBIC: {
        const char *command = request == TIOCMSET ? "set" : request == TIOCMBIS ? "bis" : "bic";
        return ask_helper(dir, name, command, *arg, &lines) ? -EACCES : 0;
    }
    case TIOCEXCL: case TIOCNXCL: case TCXONC:
        return 0;  /* the helper serves one program at a time already */
    case TCSBRK:
        /* A drain doesn't wait (proot can't, with everything stopped); 0 is a break, which stays refused. */
        return *arg ? 0 : -EACCES;
    }
    return -EACCES;
}

int mynx_uevent_path(const char *dir, int pid, unsigned n, char *path, size_t size)
{
    size_t room = size < sizeof ((struct sockaddr_un *)0)->sun_path ? size : sizeof ((struct sockaddr_un *)0)->sun_path;
    return (size_t)snprintf(path, room, "%s/.uevent/%d-%u", dir, pid, n) < room ? 0 : -1;
}
