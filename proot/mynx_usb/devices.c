/* What proot's mynx_usb extension decides; tested natively
 * (tests/mynx/test_proot_usb.py). */
#include <ctype.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>

#include "devices.h"

#define NODE "/dev/bus/usb/"

static int digits(const char *s, size_t n)
{
	for (size_t i = 0; i < n; i++)
		if (!isdigit((unsigned char) s[i]))
			return 0;
	return 1;
}

int mynx_usb_device(const char *path, char *dev, size_t size)
{
	size_t length = strlen(path);
	/* "BBB/DDD" at the very end, right after NODE.  */
	if (length < strlen(NODE) + 7 || size < 8)
		return -1;
	const char *end = path + length - 7;
	if (strncmp(end - strlen(NODE), NODE, strlen(NODE)) != 0
	    || !digits(end, 3) || end[3] != '/' || !digits(end + 4, 3))
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
