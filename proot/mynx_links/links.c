/*
 * What mynx_links decides, apart from proot so it can be tested natively.
 *
 * link2symlink fakes a hard link NAME as a symlink to an intermediate
 * DIR/.l2s.NAMEnnnn, itself a symlink to the file, DIR/.l2s.NAMEnnnn.nnnn.
 * Every path call dereferences it (lstat says a regular file, readlink
 * EINVAL), but getdents comes from the kernel: DT_LNK.
 */
#include <dirent.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "links.h"

#define PREFIX ".l2s."	/* link2symlink's, on Android */

struct dirent64_record {
	uint64_t d_ino;
	int64_t d_off;
	unsigned short d_reclen;
	unsigned char d_type;
	char d_name[];
};

static int read_link(const char *path, char *target, size_t size)
{
	ssize_t n = readlink(path, target, size - 1);
	if (n <= 0)
		return -1;
	target[n] = 0;
	return 0;
}

/* The type of the file the faked hard link path stands for, or -1 if it isn't one
 * (or a broken one), as link2symlink's resolve_faked_hard_link() decides. */
static int faked_link_type(const char *path)
{
	char intermediate[PATH_MAX], final[PATH_MAX];
	struct stat st;
	if (read_link(path, intermediate, sizeof intermediate))
		return -1;
	const char *name = strrchr(intermediate, '/');
	if (name == NULL || strncmp(name + 1, PREFIX, strlen(PREFIX)) != 0
	    || read_link(intermediate, final, sizeof final) || lstat(final, &st))
		return -1;
	return IFTODT(st.st_mode);
}

int mynx_links_fix_types(const char *dir, char *buf, size_t len)
{
	int changed = 0;
	size_t header = offsetof(struct dirent64_record, d_name);
	for (size_t at = 0; at + header <= len;) {
		struct dirent64_record *record = (struct dirent64_record *) (buf + at);
		size_t reclen = record->d_reclen;
		if (reclen <= header || at + reclen > len)
			break;
		at += reclen;
		if (record->d_type != DT_LNK || memchr(record->d_name, 0, reclen - header) == NULL)
			continue;
		char path[PATH_MAX];
		if (snprintf(path, sizeof path, "%s/%s", dir, record->d_name) >= (int) sizeof path)
			continue;
		int type = faked_link_type(path);
		if (type >= 0 && type != DT_LNK) {
			record->d_type = type;
			changed++;
		}
	}
	return changed;
}
