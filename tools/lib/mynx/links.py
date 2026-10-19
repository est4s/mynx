"""Hard links proot faked, broken when their folder was moved.

proot can't make hard links on Android, so it fakes them (link2symlink):
each name becomes a symlink to `.l2s.NAME0001`, a symlink to
`.l2s.NAME0001.0002`, the file. The links hold absolute paths, so an
installer that unpacks into a temporary folder and then moves it (as
arduino-cli does with its toolchains) leaves every such name dangling.
"""
import os
import shutil

PREFIX = ".l2s."


def fix_moved_links(folder):
    """Makes each broken name in [folder] a copy of its file, and removes
    the .l2s files it used. Returns how many names it fixed."""
    found, links = {}, []
    for root, dirs, files in os.walk(folder):
        for name in dirs + files:
            path = os.path.join(root, name)
            if name.startswith(PREFIX):
                found.setdefault(name, []).append(path)
            try:
                links.append((path, os.readlink(path)))
            except OSError:
                pass  # not a link (proot shows a working hard link as a file)

    def here(target, near):
        """Where the moved .l2s file [target] is now: the one nearest [near]."""
        if os.path.exists(target):
            return None
        places = found.get(os.path.basename(target), [])
        return max(places, key=lambda p: len(os.path.commonpath([p, near])), default=None)

    def data_file(target, near):
        for _ in range(8):
            if not os.path.basename(target).startswith(PREFIX):
                return None
            local = here(target, near)
            if local is None:
                return None
            try:
                target = os.readlink(local)
            except OSError:
                return local if os.path.isfile(local) else None
        return None

    fixed, used = 0, set()
    for path, target in links:
        if os.path.basename(path).startswith(PREFIX):
            continue
        data = data_file(target, path)
        if data:
            os.unlink(path)
            shutil.copy2(data, path)
            used.add(data)
            fixed += 1
    for path, target in links:
        if os.path.basename(path).startswith(PREFIX) and data_file(target, path) in used:
            os.unlink(path)
    for data in used:
        os.unlink(data)
    return fixed
