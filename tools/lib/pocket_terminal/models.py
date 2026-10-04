"""Text files the editors change, edited so that what people wrote by
hand (comments, odd lines) survives."""
import re

LABEL_WIDTH = 10  # labels are padded to line up the "=", up to this width


class Item:
    def __init__(self, label, value, comments=None):
        self.label = label
        self.value = value
        self.comments = comments or []  # lines just above the item


class ItemsFile:
    """A file of "Label = value" lines: key bars and the launcher menu.
    Other lines (comments, blank or broken lines) stay with the item
    below them."""

    def __init__(self, text):
        self.items = []
        pending = []
        for line in text.splitlines():
            stripped = line.strip()
            if stripped and not stripped.startswith("#") and "=" in stripped:
                label, value = stripped.split("=", 1)
                self.items.append(Item(label.strip(), value.strip(), pending))
                pending = []
            else:
                pending.append(line)
        self.tail = pending

    def add(self, after, label, value):
        """Adds an item after index [after] (-1: first); returns its index."""
        self.items.insert(after + 1, Item(label, value))
        return after + 1

    def delete(self, index):
        item = self.items.pop(index)
        # Its comments describe it; a header above the first item stays.
        if index == 0 and self.items:
            self.items[0].comments = item.comments + self.items[0].comments

    def move(self, index, step):
        """Moves an item up (-1) or down (1); returns its new index."""
        to = index + step
        if not 0 <= to < len(self.items):
            return index
        self.items[index], self.items[to] = self.items[to], self.items[index]
        return to

    def render(self):
        width = max((len(i.label) for i in self.items if len(i.label) <= LABEL_WIDTH), default=0)
        lines = []
        for item in self.items:
            lines += item.comments
            lines.append(f"{item.label:<{width}} = {item.value}")
        lines += self.tail
        return "".join(line + "\n" for line in lines)


def set_color(text, key, value):
    """The colours file [text] with [key]=[value] set, other lines kept."""
    pattern = re.compile(rf"^\s*{re.escape(key)}\s*=.*$", re.M)
    if pattern.search(text):
        return pattern.sub(f"{key}={value}", text, count=1)
    return text.rstrip("\n") + f"\n{key}={value}\n"
