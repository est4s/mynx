#!/usr/bin/env python3
"""NEON ROGUE - a pocket roguelike for narrow phone terminals.

Descend 10 levels, slay the Neon Wyrm, grab the amulet.
"""
import curses
import json
import locale
import os
import random
import signal
import sys
import time
from collections import deque

locale.setlocale(locale.LC_ALL, "")
os.environ.setdefault("ESCDELAY", "25")
UTF = "UTF" in locale.getpreferredencoding().upper()

SAVE_DIR = os.path.expanduser("~/.local/share/neonrogue")
SAVE_FILE = os.path.join(SAVE_DIR, "save.json")
SCORE_FILE = os.path.join(SAVE_DIR, "scores.json")

MAP_W, MAP_H = 54, 26
FOV_R = 7
MAX_DEPTH = 10
WALL, FLOOR, STAIRS = "#", ".", ">"
DOT = "·" if UTF else "."
BAR_FULL, BAR_EMPTY = ("█", "░") if UTF else ("=", "-")

# name, glyph, hp, atk, def, xp, min depth, color, traits
MONSTERS = [
    ("rat", "r", 4, 2, 0, 2, 1, "orange", ""),
    ("glitch bat", "b", 5, 3, 0, 3, 1, "purple", "erratic"),
    ("goblin", "g", 8, 4, 1, 5, 2, "green", ""),
    ("cyber-jackal", "j", 9, 4, 1, 7, 3, "yellow", "fast"),
    ("orc", "o", 16, 6, 2, 10, 4, "green", ""),
    ("chrome skeleton", "s", 18, 7, 3, 14, 5, "white", ""),
    ("neon wraith", "W", 20, 8, 3, 20, 6, "cyan", "drain"),
    ("troll", "T", 32, 10, 4, 28, 7, "green", "regen"),
    ("laser golem", "G", 34, 11, 6, 40, 8, "red", "ranged"),
]
BOSS = ("Neon Wyrm", "D", 120, 15, 6, 250, 10, "pink", "ranged boss")

WEAPONS = ["dagger", "short sword", "katana", "plasma blade", "photon saber", "void edge"]
ARMORS = ["leather jacket", "kevlar vest", "riot gear", "exo-plate", "quantum mesh"]

ITEM_LOOK = {
    "potion": ("!", "pink"), "scroll": ("?", "cyan"), "bomb": ("*", "orange"),
    "weapon": (")", "blue"), "armor": ("[", "blue"), "gold": ("$", "yellow"),
    "amulet": ('"', "yellow"),
}

MOVES = {
    "w": (0, -1), "s": (0, 1), "a": (-1, 0), "d": (1, 0),
    "q": (-1, -1), "e": (1, -1), "z": (-1, 1), "c": (1, 1),
    "k": (0, -1), "j": (0, 1), "h": (-1, 0), "l": (1, 0),
    "y": (-1, -1), "u": (1, -1), "b": (-1, 1), "n": (1, 1),
    "8": (0, -1), "2": (0, 1), "4": (-1, 0), "6": (1, 0),
    "7": (-1, -1), "9": (1, -1), "1": (-1, 1), "3": (1, 1),
}
KEY_MOVES = {
    curses.KEY_UP: (0, -1), curses.KEY_DOWN: (0, 1),
    curses.KEY_LEFT: (-1, 0), curses.KEY_RIGHT: (1, 0),
    curses.KEY_A1: (-1, -1), curses.KEY_A3: (1, -1),
    curses.KEY_C1: (-1, 1), curses.KEY_C3: (1, 1),
    curses.KEY_HOME: (-1, -1), curses.KEY_PPAGE: (1, -1),
    curses.KEY_END: (-1, 1), curses.KEY_NPAGE: (1, 1),
}

# ---------------------------------------------------------------- colors

PALETTE = {
    "pink": (213, curses.COLOR_MAGENTA), "cyan": (51, curses.COLOR_CYAN),
    "purple": (141, curses.COLOR_MAGENTA), "yellow": (227, curses.COLOR_YELLOW),
    "green": (84, curses.COLOR_GREEN), "red": (203, curses.COLOR_RED),
    "white": (255, curses.COLOR_WHITE), "orange": (215, curses.COLOR_YELLOW),
    "blue": (75, curses.COLOR_BLUE), "dim": (245, curses.COLOR_WHITE),
    "floor": (61, curses.COLOR_BLUE), "mfloor": (237, curses.COLOR_BLUE),
    "mwall": (239, curses.COLOR_BLUE), "button": (213, curses.COLOR_MAGENTA),
    "wall0": (33, curses.COLOR_BLUE), "wall1": (99, curses.COLOR_MAGENTA),
    "wall2": (37, curses.COLOR_CYAN), "wall3": (163, curses.COLOR_MAGENTA),
    "wall4": (69, curses.COLOR_BLUE),
}
_pairs = {}


def init_colors():
    if not curses.has_colors():
        return
    curses.start_color()
    try:
        curses.use_default_colors()
        bg = -1
    except curses.error:
        bg = curses.COLOR_BLACK
    rich = curses.COLORS >= 256
    for i, (name, (c256, c8)) in enumerate(PALETTE.items(), 1):
        curses.init_pair(i, c256 if rich else c8, bg)
        _pairs[name] = i


def color(name):
    return curses.color_pair(_pairs.get(name, 0))


B = curses.A_BOLD


def put(scr, y, x, s, attr=0):
    try:
        scr.addstr(y, x, s, attr)
    except curses.error:
        pass


def put_segs(scr, y, x, segs):
    for text, attr in segs:
        put(scr, y, x, text, attr)
        x += len(text)
    return x


# ---------------------------------------------------------------- map gen

def neighbors8(x, y):
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dx or dy:
                nx, ny = x + dx, y + dy
                if 0 <= nx < MAP_W and 0 <= ny < MAP_H:
                    yield nx, ny


def center(room):
    x, y, w, h = room
    return x + w // 2, y + h // 2


def carve_corridor(m, a, b):
    (x1, y1), (x2, y2) = center(a), center(b)
    if random.random() < 0.5:
        for x in range(min(x1, x2), max(x1, x2) + 1):
            m[y1][x] = FLOOR
        for y in range(min(y1, y2), max(y1, y2) + 1):
            m[y][x2] = FLOOR
    else:
        for y in range(min(y1, y2), max(y1, y2) + 1):
            m[y][x1] = FLOOR
        for x in range(min(x1, x2), max(x1, x2) + 1):
            m[y2][x] = FLOOR


def gen_map():
    while True:
        m = [[WALL] * MAP_W for _ in range(MAP_H)]
        rooms = []
        for _ in range(400):
            if len(rooms) >= 11:
                break
            w, h = random.randint(4, 10), random.randint(3, 6)
            x, y = random.randint(1, MAP_W - w - 1), random.randint(1, MAP_H - h - 1)
            if any(x <= rx + rw and rx <= x + w and y <= ry + rh and ry <= y + h
                   for rx, ry, rw, rh in rooms):
                continue
            rooms.append((x, y, w, h))
        if len(rooms) < 6:
            continue
        rooms.sort(key=lambda r: r[0])
        if random.random() < 0.5:
            rooms.reverse()
        for x, y, w, h in rooms:
            for yy in range(y, y + h):
                for xx in range(x, x + w):
                    m[yy][xx] = FLOOR
        for a, b in zip(rooms, rooms[1:]):
            carve_corridor(m, a, b)
        for _ in range(2):
            carve_corridor(m, *random.sample(rooms, 2))
        return m, rooms


def roll(atk, dfn):
    """Damage for one attack; 0 means a miss."""
    if random.random() < 0.12:
        return 0
    dmg = max(1, random.randint((atk + 1) // 2, atk) - random.randint(0, dfn))
    if random.random() < 0.08:
        dmg *= 2
    return dmg


def cap(s):
    return s[0].upper() + s[1:]


# ---------------------------------------------------------------- game

class Game:
    def __init__(self):
        self.depth = 0
        self.turn = 0
        self.msgs = []
        self.fresh = 0
        self.killer = None
        self.won = False
        self.vis = set()
        self.cam = (0, 0, 0, 2, MAP_W, MAP_H)
        self.buttons = []
        self.p = dict(x=0, y=0, hp=20, maxhp=20, atk=3, dfn=0, lvl=1, xp=0,
                      xp_total=0, gold=0, weapon=None, armor=None,
                      potions=1, scrolls=0, bombs=1, kills=0)
        self.new_level()
        self.msg("Find the stairs. Slay the Neon Wyrm on depth 10.")
        self.msg("Tap a tile to travel, [o] explores, [?] for help.")

    # ---- persistence
    def to_dict(self):
        d = {k: getattr(self, k) for k in
             ("depth", "turn", "msgs", "killer", "won", "p", "mons", "items", "stairs")}
        d["map"] = ["".join(r) for r in self.map]
        d["seen"] = ["".join("1" if s else "0" for s in r) for r in self.seen]
        return d

    @classmethod
    def from_dict(cls, d):
        g = cls.__new__(cls)
        for k in ("depth", "turn", "msgs", "killer", "won", "p", "mons", "items"):
            setattr(g, k, d[k])
        g.stairs = tuple(d["stairs"]) if d["stairs"] else None
        g.map = [list(r) for r in d["map"]]
        g.seen = [[c == "1" for c in r] for r in d["seen"]]
        g.msgs = [m for m in g.msgs][-30:]
        g.fresh = len(g.msgs)
        g.vis = set()
        g.cam = (0, 0, 0, 2, MAP_W, MAP_H)
        g.buttons = []
        g.update_fov()
        return g

    def save(self):
        if self.p["hp"] <= 0 or self.won:
            return
        os.makedirs(SAVE_DIR, exist_ok=True)
        tmp = SAVE_FILE + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.to_dict(), f)
        os.replace(tmp, SAVE_FILE)

    @staticmethod
    def load():
        with open(SAVE_FILE) as f:
            return Game.from_dict(json.load(f))

    # ---- helpers
    def msg(self, text):
        if self.msgs:
            last = self.msgs[-1]
            base, _, n = last.rpartition(" (x")
            count = int(n[:-1]) if base and n[:-1].isdigit() else 1
            if (base if count > 1 else last) == text:
                self.msgs.pop()
                if len(self.msgs) < self.fresh:
                    self.fresh -= 1
                text = f"{text} (x{count + 1})"
        self.msgs.append(text)
        if len(self.msgs) > 30:
            drop = len(self.msgs) - 30
            self.msgs = self.msgs[drop:]
            self.fresh = max(0, self.fresh - drop)

    @property
    def atk(self):
        return self.p["atk"] + (self.p["weapon"][1] if self.p["weapon"] else 0)

    @property
    def dfn(self):
        return self.p["dfn"] + (self.p["armor"][1] if self.p["armor"] else 0)

    def mon_at(self, x, y):
        for m in self.mons:
            if m["x"] == x and m["y"] == y:
                return m
        return None

    def visible_mons(self):
        return [m for m in self.mons if (m["x"], m["y"]) in self.vis]

    def free_cell(self, rooms=None):
        for _ in range(500):
            if rooms:
                x, y, w, h = random.choice(rooms)
                cx, cy = random.randint(x, x + w - 1), random.randint(y, y + h - 1)
            else:
                cx, cy = random.randint(1, MAP_W - 2), random.randint(1, MAP_H - 2)
            if (self.map[cy][cx] == FLOOR and not self.mon_at(cx, cy)
                    and (cx, cy) != (self.p["x"], self.p["y"])
                    and not any(i["x"] == cx and i["y"] == cy for i in self.items)):
                return cx, cy
        return None

    # ---- level setup
    def new_level(self):
        self.depth += 1
        self.map, rooms = gen_map()
        self.seen = [[False] * MAP_W for _ in range(MAP_H)]
        self.mons, self.items = [], []
        self.p["x"], self.p["y"] = center(rooms[0])
        self.stairs = None
        if self.depth < MAX_DEPTH:
            self.stairs = self.free_cell(rooms[-1:])
            sx, sy = self.stairs
            self.map[sy][sx] = STAIRS
        else:
            self.spawn_monster(rooms[-1:], BOSS)
        for _ in range(4 + self.depth + random.randint(0, 2)):
            self.spawn_monster(rooms[1:])
        for _ in range(3 + random.randint(0, 3)):
            self.spawn_item(rooms)
        for _ in range(random.randint(2, 4)):
            self.spawn_item(rooms, "gold")
        self.update_fov()
        if self.depth > 1:
            self.msg(f"You descend to depth {self.depth}.")
        if self.depth == MAX_DEPTH:
            self.msg("The air hums. Something huge is here...")

    def spawn_monster(self, rooms, kind=None):
        if kind is None:
            pool = [m for m in MONSTERS if self.depth - 4 <= m[6] <= self.depth]
            kind = random.choices(pool, weights=[m[6] + 1 for m in pool])[0]
        pos = self.free_cell(rooms)
        if not pos:
            return
        name, ch, hp, atk, dfn, xp, mind, col, traits = kind
        scale = 1 + 0.15 * max(0, self.depth - mind)
        hp = int(hp * scale)
        self.mons.append(dict(name=name, ch=ch, hp=hp, maxhp=hp,
                              atk=int(atk * scale), dfn=dfn, xp=int(xp * scale),
                              color=col, traits=traits, x=pos[0], y=pos[1],
                              awake=False, spotted=False))

    def make_item(self, kind=None):
        if kind is None:
            kind = random.choices(["potion", "scroll", "bomb", "weapon", "armor"],
                                  weights=[28, 14, 14, 15, 15])[0]
        it = dict(kind=kind)
        if kind == "gold":
            it["amount"] = random.randint(3, 8) * self.depth
        elif kind == "weapon":
            idx = min(len(WEAPONS) - 1, (self.depth - 1) // 2 + random.choice((0, 0, 1)))
            it["name"], it["bonus"] = WEAPONS[idx], 1 + idx * 2 + random.randint(0, 1)
        elif kind == "armor":
            idx = min(len(ARMORS) - 1, (self.depth - 1) // 2 + random.choice((0, 0, 1)))
            it["name"], it["bonus"] = ARMORS[idx], 1 + idx + random.randint(0, 1)
        return it

    def spawn_item(self, rooms, kind=None):
        pos = self.free_cell(rooms)
        if pos:
            it = self.make_item(kind)
            it["x"], it["y"] = pos
            self.items.append(it)

    # ---- field of view
    def los(self, x0, y0, x1, y1):
        dx, dy = abs(x1 - x0), abs(y1 - y0)
        sx, sy = (1 if x1 > x0 else -1), (1 if y1 > y0 else -1)
        err, x, y = dx - dy, x0, y0
        while (x, y) != (x1, y1):
            if (x, y) != (x0, y0) and self.map[y][x] == WALL:
                return False
            e2 = 2 * err
            if e2 > -dy:
                err -= dy
                x += sx
            if e2 < dx:
                err += dx
                y += sy
        return True

    def update_fov(self):
        px, py = self.p["x"], self.p["y"]
        vis = set()
        for y in range(max(0, py - FOV_R), min(MAP_H, py + FOV_R + 1)):
            for x in range(max(0, px - FOV_R), min(MAP_W, px + FOV_R + 1)):
                if (x - px) ** 2 + (y - py) ** 2 <= FOV_R * FOV_R + FOV_R \
                        and self.map[y][x] != WALL and self.los(px, py, x, y):
                    vis.add((x, y))
        # walls light up when they border a visible floor tile
        for x, y in list(vis):
            for nx, ny in neighbors8(x, y):
                if self.map[ny][nx] == WALL:
                    vis.add((nx, ny))
        for x, y in vis:
            self.seen[y][x] = True
        self.vis = vis

    def spot(self):
        for m in self.visible_mons():
            if not m["spotted"]:
                m["spotted"] = True
                art = "the" if "boss" in m["traits"] else "a"
                self.msg(f"You spot {art} {m['name']} ({m['ch']}).")

    # ---- player actions
    def player_move(self, dx, dy):
        nx, ny = self.p["x"] + dx, self.p["y"] + dy
        m = self.mon_at(nx, ny)
        if m:
            self.attack(m)
            return True
        if self.map[ny][nx] == WALL:
            return False
        self.p["x"], self.p["y"] = nx, ny
        self.pickup()
        if self.map[ny][nx] == STAIRS:
            self.msg("Stairs down. Press > (or tap @) to descend.")
        return True

    def attack(self, m):
        dmg = roll(self.atk, m["dfn"])
        if not dmg:
            self.msg(f"You miss the {m['name']}.")
            return
        m["hp"] -= dmg
        m["awake"] = True
        if m["hp"] > 0:
            self.msg(f"You hit the {m['name']} ({dmg}).")
            return
        self.kill(m)

    def kill(self, m):
        self.mons.remove(m)
        self.p["kills"] += 1
        self.msg(f"You destroy the {m['name']}! +{m['xp']}xp")
        self.gain_xp(m["xp"])
        if "boss" in m["traits"]:
            self.drop(m, "amulet")
            self.msg("It drops a glowing amulet!")
        elif random.random() < 0.2:
            self.drop(m, random.choice([None, "gold"]))

    def drop(self, m, kind):
        it = self.make_item(kind)
        it["x"], it["y"] = m["x"], m["y"]
        self.items.append(it)

    def gain_xp(self, xp):
        p = self.p
        p["xp"] += xp
        p["xp_total"] += xp
        while p["xp"] >= self.xp_need():
            p["xp"] -= self.xp_need()
            p["lvl"] += 1
            p["maxhp"] += 4
            p["hp"] = min(p["maxhp"], p["hp"] + 4 + p["maxhp"] // 5)
            p["atk"] += 1
            if p["lvl"] % 3 == 0:
                p["dfn"] += 1
            self.msg(f"** Level up! You are level {p['lvl']}. **")

    def xp_need(self):
        return 8 + 4 * self.p["lvl"] ** 2

    def pickup(self):
        p = self.p
        for it in [i for i in self.items if i["x"] == p["x"] and i["y"] == p["y"]]:
            self.items.remove(it)
            k = it["kind"]
            if k == "potion":
                p["potions"] += 1
                self.msg("You pick up a healing potion (!).")
            elif k == "scroll":
                p["scrolls"] += 1
                self.msg("You pick up a teleport scroll (?).")
            elif k == "bomb":
                p["bombs"] += 1
                self.msg("You pick up a plasma bomb (*).")
            elif k == "gold":
                p["gold"] += it["amount"]
                self.msg(f"You find {it['amount']} gold.")
            elif k in ("weapon", "armor"):
                cur = p[k]
                label = f"{it['name']} +{it['bonus']}"
                if cur is None or it["bonus"] > cur[1]:
                    p[k] = [it["name"], it["bonus"]]
                    self.msg(f"You {'wield' if k == 'weapon' else 'wear'} the {label}.")
                else:
                    val = it["bonus"] * 3 * self.depth
                    p["gold"] += val
                    self.msg(f"You scrap a {label} for {val} gold.")
            elif k == "amulet":
                self.won = True
                self.msg("You claim the Amulet of Neon!")

    def quaff(self):
        p = self.p
        if not p["potions"]:
            self.msg("No potions.")
            return False
        p["potions"] -= 1
        heal = max(10, int(p["maxhp"] * 0.4))
        p["hp"] = min(p["maxhp"], p["hp"] + heal)
        self.msg(f"You drink a potion. (+{heal}hp)")
        return True

    def read_scroll(self):
        if not self.p["scrolls"]:
            self.msg("No teleport scrolls.")
            return False
        pos = self.free_cell()
        if not pos:
            return False
        self.p["scrolls"] -= 1
        self.p["x"], self.p["y"] = pos
        self.msg("Reality glitches - you are elsewhere.")
        self.pickup()
        self.update_fov()
        return True

    def throw_bomb(self):
        if not self.p["bombs"]:
            self.msg("No bombs.")
            return False
        self.p["bombs"] -= 1
        px, py = self.p["x"], self.p["y"]
        hit = 0
        for m in list(self.mons):
            if max(abs(m["x"] - px), abs(m["y"] - py)) <= 2 and self.los(px, py, m["x"], m["y"]):
                dmg = 10 + 2 * self.depth + random.randint(0, 5)
                m["hp"] -= dmg
                m["awake"] = True
                hit += 1
                if m["hp"] <= 0:
                    self.kill(m)
        self.msg(f"BOOM! The plasma bomb hits {hit} {'foe' if hit == 1 else 'foes'}.")
        return True

    def descend(self):
        p = self.p
        if self.map[p["y"]][p["x"]] != STAIRS:
            self.msg("There are no stairs here.")
            return
        self.new_level()
        self.save()

    # ---- monsters
    def dist_map(self, limit=30):
        start = (self.p["x"], self.p["y"])
        dist = {start: 0}
        q = deque([start])
        while q:
            c = q.popleft()
            if dist[c] >= limit:
                continue
            for n in neighbors8(*c):
                if n not in dist and self.map[n[1]][n[0]] != WALL:
                    dist[n] = dist[c] + 1
                    q.append(n)
        return dist

    def monsters_act(self):
        dist = self.dist_map()
        for m in list(self.mons):
            for _ in range(2 if "fast" in m["traits"] else 1):
                if self.p["hp"] <= 0 or m not in self.mons:
                    return
                self.mon_act(m, dist)
            if "regen" in m["traits"] and m["hp"] < m["maxhp"]:
                m["hp"] += 1

    def mon_act(self, m, dist):
        p = self.p
        visible = (m["x"], m["y"]) in self.vis
        if visible:
            m["awake"] = True
        if not m["awake"]:
            return
        dx, dy = p["x"] - m["x"], p["y"] - m["y"]
        cheb = max(abs(dx), abs(dy))
        if cheb == 1:
            self.mon_attack(m)
            return
        if "ranged" in m["traits"] and visible and cheb <= 6 and random.random() < 0.25:
            dmg = roll(max(2, m["atk"] - 3), self.dfn)
            p["hp"] -= dmg
            what = "breathes neon fire" if "boss" in m["traits"] else "fires a laser"
            self.msg(f"The {m['name']} {what}{f' ({dmg})' if dmg else ', missing'}.")
            if p["hp"] <= 0:
                self.killer = m["name"]
            return
        opts = [n for n in neighbors8(m["x"], m["y"])
                if self.map[n[1]][n[0]] != WALL and not self.mon_at(*n)]
        if not opts:
            return
        if "erratic" in m["traits"] and random.random() < 0.5:
            m["x"], m["y"] = random.choice(opts)
            return
        here = dist.get((m["x"], m["y"]), 999)
        best = min(opts, key=lambda n: dist.get(n, 999))
        if dist.get(best, 999) < here:
            m["x"], m["y"] = best
        elif here == 999 and random.random() < 0.5:
            m["x"], m["y"] = random.choice(opts)

    def mon_attack(self, m):
        dmg = roll(m["atk"], self.dfn)
        if not dmg:
            self.msg(f"The {m['name']} misses you.")
            return
        self.p["hp"] -= dmg
        if "drain" in m["traits"]:
            m["hp"] = min(m["maxhp"], m["hp"] + dmg)
            self.msg(f"The {m['name']} drains you ({dmg}).")
        else:
            self.msg(f"The {m['name']} hits you ({dmg}).")
        if self.p["hp"] <= 0:
            self.killer = m["name"]

    def end_turn(self):
        self.update_fov()
        self.monsters_act()
        self.spot()
        self.turn += 1
        p = self.p
        if p["hp"] > 0 and self.turn % 10 == 0 and p["hp"] < p["maxhp"]:
            p["hp"] = min(p["maxhp"], p["hp"] + 1 + p["maxhp"] // 40)
        if self.turn % 50 == 0:
            self.save()

    # ---- pathing / auto-play
    def path_step(self, is_goal):
        start = (self.p["x"], self.p["y"])
        prev = {start: None}
        q = deque([start])
        while q:
            c = q.popleft()
            if c != start and is_goal(c):
                while prev[c] != start:
                    c = prev[c]
                return c[0] - start[0], c[1] - start[1]
            for n in neighbors8(*c):
                if n not in prev and self.seen[n[1]][n[0]] and self.map[n[1]][n[0]] != WALL:
                    prev[n] = c
                    q.append(n)
        return None

    def explore_step(self):
        items = {(i["x"], i["y"]) for i in self.items}

        def frontier(c):
            return c in items or any(not self.seen[y][x] for x, y in neighbors8(*c))

        step = self.path_step(frontier)
        if step:
            return step
        here = (self.p["x"], self.p["y"])
        if self.stairs and self.seen[self.stairs[1]][self.stairs[0]] and here != self.stairs:
            return self.path_step(lambda c: c == self.stairs)
        return None

    def run(self, scr, step_fn):
        """Repeat step_fn until something interesting happens or a key is pressed."""
        hp0 = self.p["hp"]
        limit = 1 if self.visible_mons() else 400
        scr.nodelay(True)
        try:
            for _ in range(limit):
                step = step_fn()
                if step is None or not self.player_move(*step):
                    return False
                self.end_turn()
                if self.p["hp"] <= 0 or self.won or self.visible_mons() or self.p["hp"] < hp0:
                    break
                self.draw(scr)
                time.sleep(0.02)
                if scr.getch() != -1:
                    break
        finally:
            scr.nodelay(False)
        return True

    def auto_explore(self, scr):
        if self.visible_mons():
            self.msg("Not with enemies in view!")
            return
        self.run(scr, self.explore_step)
        if not self.visible_mons() and self.explore_step() is None:
            p = self.p
            if self.map[p["y"]][p["x"]] == STAIRS:
                self.msg("Level explored. Press > to descend.")
            else:
                self.msg("Nothing left to explore.")

    def travel(self, scr, target):
        if not self.seen[target[1]][target[0]] or self.map[target[1]][target[0]] == WALL:
            return
        self.run(scr, lambda: self.path_step(lambda c: c == target))

    def rest(self, scr):
        if self.visible_mons():
            self.msg("Not with enemies in view!")
            return
        p = self.p
        hp0 = p["hp"]
        scr.nodelay(True)
        try:
            for i in range(300):
                if p["hp"] >= p["maxhp"]:
                    break
                self.end_turn()
                if self.visible_mons() or p["hp"] < hp0 or p["hp"] <= 0:
                    break
                hp0 = p["hp"]
                if i % 10 == 0:
                    self.draw(scr)
                    if scr.getch() != -1:
                        break
        finally:
            scr.nodelay(False)
        self.msg("You rest." if p["hp"] >= p["maxhp"] else "Your rest is interrupted.")

    # ---- drawing
    def draw(self, scr):
        scr.erase()
        rows, cols = scr.getmaxyx()
        self.buttons = []
        if rows < 14 or cols < 36:
            put(scr, 0, 0, "Make the terminal bigger!", color("pink") | B)
            scr.refresh()
            return
        p = self.p
        top = 2
        view_h = min(MAP_H, rows - top - 4)
        view_w = min(MAP_W, cols)
        cam_x = max(0, min(p["x"] - view_w // 2, MAP_W - view_w))
        cam_y = max(0, min(p["y"] - view_h // 2, MAP_H - view_h))
        off_x = (cols - view_w) // 2
        self.cam = (cam_x, cam_y, off_x, top, view_w, view_h)

        wall_attr = color(f"wall{(self.depth - 1) % 5}") | B
        for sy in range(view_h):
            my = cam_y + sy
            row, seen = self.map[my], self.seen[my]
            for sx in range(view_w):
                mx = cam_x + sx
                if not seen[mx]:
                    continue
                t, v = row[mx], (mx, my) in self.vis
                if t == WALL:
                    ch, a = "#", wall_attr if v else color("mwall")
                elif t == STAIRS:
                    ch, a = ">", (color("yellow") | B) if v else color("orange")
                else:
                    ch, a = DOT, color("floor") if v else color("mfloor")
                put(scr, top + sy, off_x + sx, ch, a)

        def screen(x, y):
            if cam_x <= x < cam_x + view_w and cam_y <= y < cam_y + view_h:
                return top + y - cam_y, off_x + x - cam_x
            return None

        for it in self.items:
            pos = screen(it["x"], it["y"])
            if pos and self.seen[it["y"]][it["x"]]:
                g, col = ITEM_LOOK[it["kind"]]
                v = (it["x"], it["y"]) in self.vis
                put(scr, *pos, g, color(col) | (B if v else 0))
        for m in self.visible_mons():
            pos = screen(m["x"], m["y"])
            if pos:
                put(scr, *pos, m["ch"], color(m["color"]) | B)
        put(scr, *screen(p["x"], p["y"]), "@", color("pink") | B)

        # HUD
        bw = 10
        frac = max(0, p["hp"]) / p["maxhp"]
        filled = round(bw * frac)
        hpcol = "green" if frac > 0.6 else "yellow" if frac > 0.3 else "red"
        pct = int(100 * p["xp"] / self.xp_need())
        put_segs(scr, 0, 0, [
            ("DEPTH ", color("dim")), (f"{self.depth}", color("cyan") | B),
            ("  HP ", color("dim")), (BAR_FULL * filled, color(hpcol)),
            (BAR_EMPTY * (bw - filled), color("mwall")),
            (f" {max(0, p['hp'])}/{p['maxhp']}", color(hpcol) | B),
            ("  LV ", color("dim")), (str(p["lvl"]), color("pink") | B),
            (f" {pct}%", color("dim")),
        ])
        wpn = f"{p['weapon'][0]}+{p['weapon'][1]}" if p["weapon"] else "fists"
        put_segs(scr, 1, 0, [
            ("ATK ", color("dim")), (str(self.atk), color("orange") | B),
            ("  DEF ", color("dim")), (str(self.dfn), color("blue") | B),
            ("  $", color("yellow")), (str(p["gold"]), color("yellow") | B),
            ("  ", 0), (wpn[:max(0, cols - 30)], color("dim")),
        ])

        # messages
        mrow = top + view_h
        recent = self.msgs[-3:]
        first = len(self.msgs) - len(recent)
        for i, text in enumerate(recent):
            new = first + i >= self.fresh
            put(scr, mrow + i, 0, text[:cols - 1], color("white") | B if new else color("dim"))

        # tap buttons
        btns = [("EXPLORE", "o"), ("REST", "R"), (f"!{p['potions']}", "p"),
                (f"?{p['scrolls']}", "t"), (f"*{p['bombs']}", "f"), (">", ">"), ("HELP", "?")]
        x = 0
        for label, key in btns:
            text = f"[{label}]"
            if x + len(text) > cols:
                break
            put(scr, rows - 1, x, text, color("button") | B)
            self.buttons.append((rows - 1, x, x + len(text), key))
            x += len(text) + 1
        scr.refresh()

    def tap(self, sx, sy):
        """Turn a screen tap into a key or ('move', dx, dy) / ('travel', x, y)."""
        for row, x0, x1, key in self.buttons:
            if sy == row and x0 <= sx < x1:
                return key
        cam_x, cam_y, off_x, top, vw, vh = self.cam
        if not (top <= sy < top + vh and off_x <= sx < off_x + vw):
            return None
        mx, my = cam_x + sx - off_x, cam_y + sy - top
        dx, dy = mx - self.p["x"], my - self.p["y"]
        if dx == dy == 0:
            return ">" if self.map[my][mx] == STAIRS else "."
        if max(abs(dx), abs(dy)) == 1:
            return ("move", dx, dy)
        return ("travel", mx, my)


# ---------------------------------------------------------------- screens

TITLE = [
    "╔╗╔╔═╗╔═╗╔╗╔  ╦═╗╔═╗╔═╗╦ ╦╔═╗",
    "║║║║╣ ║ ║║║║  ╠╦╝║ ║║ ╦║ ║║╣ ",
    "╝╚╝╚═╝╚═╝╝╚╝  ╩╚═╚═╝╚═╝╚═╝╚═╝",
] if UTF else ["N E O N   R O G U E"]

HELP = [
    "NEON ROGUE - HOW TO PLAY",
    "",
    "Reach depth 10, slay the Neon Wyrm (D)",
    "and grab its amulet.",
    "",
    "MOVE    wasd + qezc diagonals",
    "        hjkl + yubn, arrows, numpad",
    "        walk into monsters to attack",
    "TAP     a tile to travel there,",
    "        next to you to step/attack,",
    "        on yourself to wait/descend",
    "o       auto-explore    R  rest",
    ".       wait one turn   >  descend",
    "p  !    drink potion (heals 40%)",
    "t  ?    teleport scroll",
    "f  *    plasma bomb (radius 2)",
    "i       character sheet",
    "Q       save and quit",
    "",
    "Weapons/armor auto-equip if better,",
    "otherwise they are scrapped for gold.",
]


def text_screen(scr, lines, colors=None):
    scr.erase()
    rows, cols = scr.getmaxyx()
    y0 = max(0, (rows - len(lines)) // 2)
    for i, line in enumerate(lines):
        attr = (colors or {}).get(i, color("white"))
        put(scr, y0 + i, max(0, (cols - len(line)) // 2) if i == 0 else 1, line[:cols - 1], attr)
    put(scr, rows - 1, 1, "press any key", color("dim"))
    scr.refresh()
    curses.flushinp()
    scr.getch()


def char_sheet(scr, g):
    p = g.p
    w = f"{p['weapon'][0]} +{p['weapon'][1]}" if p["weapon"] else "fists"
    a = f"{p['armor'][0]} +{p['armor'][1]}" if p["armor"] else "nothing"
    text_screen(scr, [
        "CHARACTER",
        "",
        f"Level {p['lvl']}   XP {p['xp']}/{g.xp_need()}",
        f"HP {p['hp']}/{p['maxhp']}",
        f"Attack {g.atk}   Defense {g.dfn}",
        f"Weapon  {w}",
        f"Armor   {a}",
        f"Potions {p['potions']}  Scrolls {p['scrolls']}  Bombs {p['bombs']}",
        "",
        f"Depth {g.depth}   Turn {g.turn}   Kills {p['kills']}",
        f"Gold {p['gold']}",
    ], {0: color("pink") | B})


def score_of(g):
    return g.p["gold"] + g.p["xp_total"] * 2 + g.depth * 100 + (2000 if g.won else 0)


def load_scores():
    try:
        with open(SCORE_FILE) as f:
            return json.load(f)
    except (OSError, ValueError):
        return []


def record_score(g):
    scores = load_scores()
    entry = dict(score=score_of(g), depth=g.depth, lvl=g.p["lvl"], won=g.won,
                 cause=("WON" if g.won else f"{g.killer or '?'}"),
                 date=time.strftime("%Y-%m-%d"))
    scores.append(entry)
    scores.sort(key=lambda s: -s["score"])
    os.makedirs(SAVE_DIR, exist_ok=True)
    with open(SCORE_FILE, "w") as f:
        json.dump(scores[:10], f)
    try:
        os.remove(SAVE_FILE)
    except OSError:
        pass
    return scores[:10].index(entry) + 1 if entry in scores[:10] else None


def scores_screen(scr):
    scores = load_scores()
    lines = ["HALL OF NEON", ""]
    if not scores:
        lines.append("No runs yet. Go die gloriously.")
    for i, s in enumerate(scores, 1):
        end = "WON!" if s["won"] else f"d{s['depth']} {s['cause']}"
        lines.append(f"{i:2}. {s['score']:6}  L{s['lvl']:<2} {end}"[:52])
    text_screen(scr, lines, {0: color("cyan") | B})


def end_screen(scr, g):
    time.sleep(0.6)
    rank = record_score(g)
    p = g.p
    if g.won:
        head = ["*** VICTORY ***", "", "The Neon Wyrm is dead and the",
                "Amulet of Neon glows in your hand."]
        col = color("yellow") | B
    else:
        head = ["*** YOU DIED ***", "", f"Slain by a {g.killer or 'mystery'}",
                f"on depth {g.depth}, turn {g.turn}."]
        col = color("red") | B
    lines = head + [
        "",
        f"Level {p['lvl']}   Kills {p['kills']}   Gold {p['gold']}",
        f"SCORE {score_of(g)}" + (f"   (#{rank} all time)" if rank else ""),
        "",
        "Last words of the log:",
    ] + ["  " + m for m in g.msgs[-4:]]
    text_screen(scr, lines, {0: col})


def title_screen(scr):
    while True:
        scr.erase()
        rows, cols = scr.getmaxyx()
        y = max(0, rows // 2 - 8)
        for i, line in enumerate(TITLE):
            x = max(0, (cols - len(line)) // 2)
            half = 14 if UTF else len(line)
            put(scr, y + i, x, line[:half], color("pink") | B)
            put(scr, y + i, x + half, line[half:], color("cyan") | B)
        y += len(TITLE) + 1
        tag = "descend 10 levels  -  slay the wyrm"
        put(scr, y, max(0, (cols - len(tag)) // 2), tag, color("purple"))
        y += 1
        if UTF:
            grid = "▁▂▃▄▅▆▇█▇▆▅▄▃▂▁"
            put(scr, y, max(0, (cols - len(grid)) // 2), grid, color("pink"))
        has_save = os.path.exists(SAVE_FILE)
        opts = ([("c", "continue run")] if has_save else []) + [
            ("n", "new run" + (" (abandons save)" if has_save else "")),
            ("s", "hall of neon"), ("?", "how to play"), ("x", "exit")]
        y += 2
        bx = max(1, cols // 2 - 12)
        for key, label in opts:
            put_segs(scr, y, bx, [(f"[{key}] ", color("cyan") | B), (label, color("white"))])
            y += 1
        scr.refresh()
        k = scr.getch()
        ch = chr(k) if 0 <= k < 256 else ""
        if k == curses.KEY_MOUSE:
            try:
                _, mx, my, _, _ = curses.getmouse()
            except curses.error:
                continue
            idx = my - (y - len(opts))
            if 0 <= idx < len(opts):
                ch = opts[idx][0]
        if ch == "c" and has_save:
            return "c"
        if ch in ("n", "s", "?", "x", "Q"):
            return "x" if ch == "Q" else ch


# ---------------------------------------------------------------- main loop

CURRENT = None


def play(scr, g):
    global CURRENT
    CURRENT = g
    try:
        while True:
            g.draw(scr)
            if g.p["hp"] <= 0 or g.won:
                end_screen(scr, g)
                return
            k = scr.getch()
            g.fresh = len(g.msgs)
            action = None
            if k == curses.KEY_MOUSE:
                try:
                    _, mx, my, _, bstate = curses.getmouse()
                except curses.error:
                    continue
                if bstate & (curses.BUTTON1_PRESSED | curses.BUTTON1_CLICKED):
                    action = g.tap(mx, my)
            elif k in KEY_MOVES:
                action = ("move", *KEY_MOVES[k])
            elif k in (10, 13, curses.KEY_ENTER):
                action = ">"
            elif 0 <= k < 256:
                ch = chr(k)
                action = ("move", *MOVES[ch]) if ch in MOVES else ch
            if action is None:
                continue
            if isinstance(action, tuple):
                if action[0] == "move":
                    if g.player_move(action[1], action[2]):
                        g.end_turn()
                else:
                    g.travel(scr, (action[1], action[2]))
                continue
            ch = action
            if ch in ".5 ":
                g.end_turn()
            elif ch == "o":
                g.auto_explore(scr)
            elif ch == "R":
                g.rest(scr)
            elif ch == "p":
                if g.quaff():
                    g.end_turn()
            elif ch == "t":
                if g.read_scroll():
                    g.end_turn()
            elif ch == "f":
                if g.throw_bomb():
                    g.end_turn()
            elif ch == ">":
                g.descend()
            elif ch == "?":
                text_screen(scr, HELP, {0: color("pink") | B})
            elif ch == "i":
                char_sheet(scr, g)
            elif ch == "Q":
                g.save()
                return
    except KeyboardInterrupt:
        g.save()
    finally:
        CURRENT = None


def on_hangup(*_):
    if CURRENT:
        CURRENT.save()
    sys.exit(0)


def main(scr):
    curses.curs_set(0)
    init_colors()
    scr.keypad(True)
    curses.mousemask(curses.BUTTON1_PRESSED | curses.BUTTON1_RELEASED | curses.BUTTON1_CLICKED)
    curses.mouseinterval(0)
    while True:
        choice = title_screen(scr)
        if choice == "n":
            play(scr, Game())
        elif choice == "c":
            try:
                g = Game.load()
            except (OSError, ValueError, KeyError):
                continue
            play(scr, g)
        elif choice == "s":
            scores_screen(scr)
        elif choice == "?":
            text_screen(scr, HELP, {0: color("pink") | B})
        else:
            return


if __name__ == "__main__":
    signal.signal(signal.SIGHUP, on_hangup)
    signal.signal(signal.SIGTERM, on_hangup)
    try:
        curses.wrapper(main)
    except KeyboardInterrupt:
        pass
