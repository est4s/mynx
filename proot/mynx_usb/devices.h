#ifndef MYNX_USB_DEVICES_H
#define MYNX_USB_DEVICES_H

#include <stddef.h>

/* Whether path (a host path, as proot translated it) is a USB device node:
 * BBB/DDD in the folder of socket (the app's, in Debian's own /dev); its
 * "BBB/DDD" into dev. 0 if it is. */
int mynx_usb_device(const char *path, const char *socket, char *dev, size_t size);

/* What the program's open() returns, given the app's reply ("ok", or
 * "err ERRNO") and the fd that came with it (-1 if none): the fd, or -errno. */
int mynx_usb_result(const char *reply, long length, int fd);

/* socket with its folder's real path (proot gives binds' host paths
 * theirs: Android's /data/user/0 is a link to /data/data) into out; as
 * given if the folder can't be resolved. 0 if it fits. */
int mynx_usb_real_socket(const char *socket, char *out, size_t size);

#endif
