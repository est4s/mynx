/* What proot's mynx_usb extension decides; tested natively
 * (tests/mynx/test_proot_usb.py). */
#include <ctype.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "devices.h"

static int digits(const char *s, size_t n)
{
	for (size_t i = 0; i < n; i++)
		if (!isdigit((unsigned char) s[i]))
			return 0;
	return 1;
}

int mynx_usb_device(const char *path, const char *socket, char *dev, size_t size)
{
	/* The node is next to the app's socket, in Debian's own /dev (a
	 * program's own folder named dev/bus/usb is left alone): "BBB/DDD"
	 * right after the socket's folder.  */
	const char *slash = strrchr(socket, '/');
	if (slash == NULL || size < 8)
		return -1;
	size_t folder = slash - socket + 1;
	if (strlen(path) != folder + 7 || strncmp(path, socket, folder) != 0)
		return -1;
	const char *end = path + folder;
	if (!digits(end, 3) || end[3] != '/' || !digits(end + 4, 3))
		return -1;
	memcpy(dev, end, 7);
	dev[7] = 0;
	return 0;
}

int mynx_usb_result(const char *reply, long length, int fd)
{
	char text[32];
	if (length < 0 || (size_t) length >= sizeof text)
		return -EIO;
	memcpy(text, reply, length);
	text[length] = 0;
	if (strcmp(text, "ok\n") == 0)
		return fd >= 0 ? fd : -EIO;
	if (strncmp(text, "err ", 4) != 0 || !isdigit((unsigned char) text[4]))
		return -EIO;
	char *rest;
	long error = strtol(text + 4, &rest, 10);
	if (error <= 0 || error > 4095 || strcmp(rest, "\n") != 0)
		return -EIO;
	return (int) -error;
}

int mynx_usb_real_socket(const char *socket, char *out, size_t size)
{
	/* The folder's real path, so it matches proot's translated paths;
	 * as given when it can't be resolved.  */
	const char *slash = strrchr(socket, '/');
	char folder[PATH_MAX], real[PATH_MAX];
	int written;
	if (slash != NULL && (size_t) (slash - socket) < sizeof folder) {
		memcpy(folder, socket, slash - socket);
		folder[slash - socket] = 0;
		if (realpath(folder, real) != NULL)
			written = snprintf(out, size, "%s%s", real, slash);
		else
			written = snprintf(out, size, "%s", socket);
	} else {
		written = snprintf(out, size, "%s", socket);
	}
	return written < 0 || (size_t) written >= size ? -1 : 0;
}
