/*
 * mynx_links: a proot extension that makes link2symlink's faked hard links
 * regular files in directory listings too, turned on with it
 * (--link2symlink).
 *
 * getdents64 reports a faked hard link as DT_LNK, while every other call
 * says it is a regular file (readlink: EINVAL). Programs that trust d_type
 * then fail: Python's shutil.copytree(symlinks=True), so PlatformIO's ESP32
 * toolchains don't install. At the exit, links.c gives each one the type of
 * the file it stands for. The old getdents (32-bit programs) isn't covered.
 */
#include <errno.h>
#include <limits.h>
#include <stdlib.h>

#include "extension/extension.h"
#include "path/path.h"
#include "syscall/sysnum.h"
#include "tracee/mem.h"
#include "tracee/reg.h"
#include "extension/mynx_links/links.h"

static FilteredSysnum filtered_sysnums[] = {
	{ PR_getdents64,	FILTER_SYSEXIT },
	FILTERED_SYSNUM_END,
};

static int fix_listing(Tracee *tracee)
{
	if (get_sysnum(tracee, ORIGINAL) != PR_getdents64)
		return 0;
	long length = (long) peek_reg(tracee, CURRENT, SYSARG_RESULT);
	if (length <= 0)
		return 0;

	char dir[PATH_MAX];
	if (readlink_proc_pid_fd(tracee->pid, peek_reg(tracee, ORIGINAL, SYSARG_1), dir) < 0)
		return 0;
	word_t address = peek_reg(tracee, ORIGINAL, SYSARG_2);
	char *buf = malloc(length);
	if (buf == NULL)
		return 0;
	int status = read_data(tracee, buf, address, length);
	if (status == 0 && mynx_links_fix_types(dir, buf, length) > 0)
		status = write_data(tracee, address, buf, length);
	free(buf);
	return status < 0 ? status : 0;
}

int mynx_links_callback(Extension *extension, ExtensionEvent event, intptr_t data1 UNUSED, intptr_t data2 UNUSED)
{
	switch (event) {
	case INITIALIZATION:
		extension->filtered_sysnums = filtered_sysnums;
		return 0;

	case INHERIT_PARENT:
		return 0;

	case SYSCALL_EXIT_END:
		return fix_listing(TRACEE(extension));

	default:
		return 0;
	}
}
