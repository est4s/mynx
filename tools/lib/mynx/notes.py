"""Release notes: CI bundles every release's notes in the tools
(release-notes.json, newest first), so `mynx notes` works offline and
matches GitHub's releases."""
import re
import textwrap

VERSION = re.compile(r"^v?(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.]+))?$")
# GitHub's generated notes end each line with " by @login in .../pull/N".
PULL = re.compile(r"(?: by @[\w-]+)? in https://github\.com/[\w.-]+/[\w.-]+/pull/(\d+)$")


def version_key(version):
    """For sorting: 0.2.0-beta.1 < 0.2.0 < 0.10.0. None if it isn't one."""
    m = VERSION.match(version or "")
    if not m:
        return None
    major, minor, patch, pre = m.groups()
    return (int(major), int(minor), int(patch or 0), pre is None, pre or "")


def newer(releases, seen, current):
    """The releases after [seen], up to [current], newest first. With
    nothing seen yet, just [current]'s."""
    now = version_key(current)
    if now is None:
        return []
    last = version_key(seen)
    return [r for r in releases
            if (k := version_key(r.get("version"))) is not None and k <= now
            and (k > last if last is not None else k == now)]


def format_notes(text, width):
    """Markdown notes as terminal lines: headings and bold lose their
    marks, list items get bullets, links to pull requests become (#N)."""
    lines = []
    for raw in text.replace("\r\n", "\n").split("\n"):
        line = raw.strip().replace("**", "")
        if not line:
            if lines and lines[-1]:
                lines.append("")
            continue
        line = re.sub(r"^#+\s*", "", line)
        bullet = re.match(r"^[*-]\s+", line)
        if bullet:
            line = PULL.sub(r" (#\1)", line[bullet.end():])
            lines += textwrap.wrap(line, width, initial_indent="• ", subsequent_indent="  ",
                                   break_long_words=False, break_on_hyphens=False)
        else:
            lines += textwrap.wrap(line, width, break_long_words=False, break_on_hyphens=False)
    while lines and not lines[-1]:
        lines.pop()
    return lines


def render(releases, width, app="mynx"):
    blocks = []
    for r in releases:
        date = (r.get("published") or "")[:10]
        head = f"{app} {r['version']}" + (f" · {date}" if date else "")
        body = format_notes(r.get("notes") or "", width) or ["(no notes)"]
        blocks.append("\n".join([head, "", *body]))
    return "\n\n".join(blocks) + "\n" if blocks else ""
