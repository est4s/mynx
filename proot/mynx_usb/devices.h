#ifndef MYNX_USB_DEVICES_H
#define MYNX_USB_DEVICES_H

#include <stddef.h>

/* Whether path (a host path, as proot translated it) is a USB device node,
 * …/dev/bus/usb/BBB/DDD; its "BBB/DDD" into dev. 0 if it is. */
int mynx_usb_device(const char *path, char *dev, size_t size);

/* What the program's open() returns, given the app's reply ("ok", or
 * "err ERRNO") and the fd that came with it (-1 if none): the fd, or -errno. */
int mynx_usb_result(const char *reply, long length, int fd);

#endif
