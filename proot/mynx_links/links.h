#ifndef MYNX_LINKS_H
#define MYNX_LINKS_H

#include <stddef.h>

/* In buf, len bytes of getdents64 records read from the host folder dir,
 * gives each faked hard link the type of the file it stands for. Returns
 * how many records changed. */
int mynx_links_fix_types(const char *dir, char *buf, size_t len);

#endif
