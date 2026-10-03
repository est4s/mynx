#!/usr/bin/env python3
"""neondrive — a pseudo-3D synthwave racer for the terminal.

Out Run-style road projection drawn with half-block pixels. Dodge traffic and
reach each checkpoint before the clock runs out. Built for a phone in portrait.

Keys:  ← → / a d  steer    ↓ / s / space  brake    ↑ / w  nitro
       p  pause            q  quit
Touch: hold the left/right side to steer, centre-bottom brakes,
       centre-top fires nitro.
"""
import curses
import math
import os
import random
import time

HISCORE = os.path.expanduser("~/.neondrive_hiscore")
TICK = 1 / 30

# --- world -------------------------------------------------------------------
SEG_LEN = 200            # world units per road segment
RUMBLE = 3               # segments per light/dark stripe
ROAD_W = 1000            # road half-width
LANES = 3
CAM_H = 1000
CAM_DEPTH = 1 / math.tan(math.radians(50))   # 100° field of view
PLAYER_Z = CAM_H * CAM_DEPTH                 # player's distance ahead of camera
DRAW_DIST = 110          # segments drawn
TRACK_SEGS = 6000
CP_EVERY = 1000          # segments between checkpoints
CAR_W = 500              # car width in world units

# --- handling ----------------------------------------------------------------
MAX_SPEED = SEG_LEN * 60
ACCEL = MAX_SPEED / 5
BRAKING = -MAX_SPEED * 1.2
DECEL = -MAX_SPEED / 5
OFFROAD_DECEL = -MAX_SPEED / 2
OFFROAD_LIMIT = MAX_SPEED / 4
CENTRIFUGAL = 0.3
NITRO_MULT = 1.35
NITRO_TIME = 2.5
NITRO_MAX = 5
STEER_MAX = 3.0          # lateral speed cap, road half-widths per second
STEER_IMPULSE = 0.8      # per key press (key repeat gives continuous steering)
STEER_ACC = 12.0         # while a touch is held
STEER_DAMP = 4.0
KMH = 280 / MAX_SPEED
UNITS_PER_M = MAX_SPEED / (280 / 3.6)
START_TIME = 30.0

# --- palette (xterm-256) -----------------------------------------------------
SKY = [16, 17, 17, 18, 54, 54, 90, 126, 162, 198]
SUN = [226, 220, 214, 208, 202, 198, 199, 200]
MTN, MTN_EDGE = 54, 165
GRASS = (19, 17)
RUMBLE_C = (231, 201)
ROAD_C = (238, 236)
LANE = 51
STAR = 189
HUD_BG = 16

CAR_COLORS = [(46, 28), (226, 136), (39, 25), (208, 130), (231, 245), (93, 54), (51, 30)]
PLAYER_COLORS = (198, 125)

CAR_ART = [
    "...kkkkkkkk...",
    "..kwWwwwwwwk..",
    "..kwwwwwwwwk..",
    ".BBBBBBBBBBBB.",
    "BBBBBBBBBBBBBB",
    "BRRBBBBBBBBRRB",
    "BRRBBkkkkBBRRB",
    "DDDDDDDDDDDDDD",
    "DkkkDDDDDDkkkD",
    ".kkk......kkk.",
]
PALM_ART = [
    "...LL.LL...",
    ".LLlLLLlLL.",
    "LL..LLL..LL",
    "L..lLtLl..L",
    "..L..t..L..",
    ".L...t...L.",
    ".....t.....",
    "....t......",
    "....t......",
    "....t......",
    "....t......",
    "....t......",
    ".....t.....",
    ".....t.....",
    ".....t.....",
    ".....t.....",
    ".....t.....",
    "....ttt....",
]
LAMP_ART = [
    ".CCC.",
    "CWWWC",
    ".CCC.",
] + ["..G.."] * 15 + [".GGG."]
CHEV_ART = [
    "YYYYYYYY",
    "YKKYYYYY",
    "YYKKYYYY",
    "YYYKKYYY",
    "YYYYKKYY",
    "YYYKKYYY",
    "YYKKYYYY",
    "YKKYYYYY",
    "YYYYYYYY",
    "...GG...",
    "...GG...",
    "...GG...",
]
_check = "".join("wK"[i % 2] for i in range(32))
GATE_ART = [
    "M" * 36,
    "M" + _check + "MMM",
    "M" + _check[1:] + "wMMM",
    "M" + _check + "MMM",
    "M" * 36,
] + ["GG" + "." * 32 + "GG"] * 15


def make_art(rows, colors):
    return [[colors.get(ch, -1) for ch in row] for row in rows]


class Sprite:
    def __init__(self, rows, colors, world_w, coll=0.0):
        self.px = make_art(rows, colors)
        self.aw = len(rows[0])
        self.ah = len(rows)
        self.world_w = world_w
        self.coll = coll           # collision width in road half-widths (0 = none)


def car_sprite(body, dark, tail=196):
    return Sprite(CAR_ART, {"k": 233, "w": 24, "W": 31, "B": body, "D": dark,
                            "R": tail}, CAR_W)


SPRITES = {
    "palm": Sprite(PALM_ART, {"L": 42, "l": 29, "t": 94}, 800, 0.3),
    "lamp": Sprite(LAMP_ART, {"C": 51, "W": 231, "G": 240}, 300, 0.2),
    "chevR": Sprite(CHEV_ART, {"Y": 226, "K": 16, "G": 240}, 450, 0.45),
    "chevL": Sprite([r[::-1] for r in CHEV_ART], {"Y": 226, "K": 16, "G": 240}, 450, 0.45),
    "gate": Sprite(GATE_ART, {"M": 201, "w": 231, "K": 16, "G": 201}, 2700),
}
TRAFFIC = [car_sprite(b, d) for b, d in CAR_COLORS]
PLAYER = car_sprite(*PLAYER_COLORS, tail=226)


# --- track -------------------------------------------------------------------
class Seg:
    __slots__ = ("i", "y1", "y2", "curve", "dark", "sprites", "cars")

    def __init__(self, i, y1, y2, curve):
        self.i, self.y1, self.y2, self.curve = i, y1, y2, curve
        self.dark = (i // RUMBLE) % 2
        self.sprites = []
        self.cars = []


def ease_in(a, b, p):
    return a + (b - a) * p * p


def ease_inout(a, b, p):
    return a + (b - a) * (0.5 - math.cos(p * math.pi) / 2)


def build_track(rng):
    segs = []

    def add_road(enter, hold, leave, curve, height):
        sy = segs[-1].y2 if segs else 0.0
        ey = sy + height * SEG_LEN
        total = enter + hold + leave
        for n in range(total):
            if n < enter:
                c = ease_in(0, curve, n / enter)
            elif n < enter + hold:
                c = curve
            else:
                c = ease_inout(curve, 0, (n - enter - hold) / leave)
            y1 = segs[-1].y2 if segs else 0.0
            segs.append(Seg(len(segs), y1, ease_inout(sy, ey, (n + 1) / total), c))

    add_road(20, 40, 20, 0, 0)
    while len(segs) < TRACK_SEGS - 700:
        cur = segs[-1].y2 / SEG_LEN
        L = rng.choice((25, 40, 50, 75))
        hill = rng.choice((0, 0, 10, 20, 40, 60)) * rng.choice((-1, 1))
        if abs(cur + hill) > 90:
            hill = -hill
        curve = rng.choice((2, 3, 4)) * rng.choice((-1, 1))
        kind = rng.random()
        if kind < 0.2:
            add_road(L, L, L, 0, hill)
        elif kind < 0.6:
            add_road(L, L, L, curve, hill)
        elif kind < 0.8:                                   # s-bends
            h = L // 2
            for k in range(4):
                add_road(h, h, h, curve * (-1) ** k, rng.choice((0, 0, 10, -10)))
        else:                                              # rolling bumps
            for k in range(rng.randint(3, 6)):
                add_road(10, 10, 10, 0, rng.choice((5, 10, 15)) * (-1) ** k)
    rem = TRACK_SEGS - len(segs)
    a = rem // 3
    add_road(a, rem - 2 * a, a, 0, -segs[-1].y2 / SEG_LEN)
    segs[-1].y2 = 0.0

    for s in segs:
        i = s.i
        if i % CP_EVERY == 0:
            s.sprites.append((0.0, "gate"))
        elif i % 20 == 0:
            s.sprites += [(-1.25, "lamp"), (1.25, "lamp")]
        elif abs(s.curve) > 1.5 and i % 6 == 0:
            s.sprites.append((-1.4, "chevR") if s.curve > 0 else (1.4, "chevL"))
        elif rng.random() < 0.18:
            s.sprites.append((rng.choice((-1, 1)) * (1.6 + rng.random() * 1.6), "palm"))
    return segs


# --- traffic -----------------------------------------------------------------
LANE_X = [-2 / 3, 0.0, 2 / 3]


class Car:
    __slots__ = ("z", "off", "target", "speed", "spr", "seg", "timer")


def add_cars(game, n, ahead_from):
    rng = game.rng
    L = len(game.segs) * SEG_LEN
    for _ in range(n):
        c = Car()
        c.z =(game.pos + ahead_from + rng.random() * (L * 0.8)) % L
        c.off = c.target = rng.choice(LANE_X)
        c.speed = MAX_SPEED * (0.25 + rng.random() * 0.3)
        c.spr = rng.choice(TRAFFIC)
        c.timer = rng.uniform(2, 8)
        c.seg = game.seg_at(c.z)
        c.seg.cars.append(c)
        game.cars.append(c)


# --- colour pairs + blitting -------------------------------------------------
class Painter:
    def __init__(self):
        self.pairs = {}
        self.next = 1
        self.limit = curses.COLOR_PAIRS - 1
        self.rich = curses.COLORS >= 256

    def map8(self, c):
        if self.rich:
            return c
        if c < 16:
            return c % 8
        if c >= 232:
            return 7 if c > 243 else 0
        c -= 16
        r, g, b = c // 36, (c // 6) % 6, c % 6
        return (1 if r > 2 else 0) | (2 if g > 2 else 0) | (4 if b > 2 else 0)

    def pair(self, fg, bg):
        key = (fg, bg)
        p = self.pairs.get(key)
        if p is None:
            if self.next > self.limit:
                return 0
            p = self.next
            self.next += 1
            curses.init_pair(p, self.map8(fg), self.map8(bg))
            p = curses.color_pair(p)
            self.pairs[key] = p
        return p

    def blit(self, scr, fb, W, rows, top):
        pair = self.pair
        for r in range(rows):
            a = fb[2 * r]
            b = fb[2 * r + 1]
            x = 0
            while x < W:
                t, u = a[x], b[x]
                x2 = x + 1
                while x2 < W and a[x2] == t and b[x2] == u:
                    x2 += 1
                try:
                    scr.addstr(top + r, x, ("▀" if t != u else " ") * (x2 - x), pair(t, u))
                except curses.error:
                    pass
                x = x2


def put(scr, y, x, s, attr=0):
    h, w = scr.getmaxyx()
    if 0 <= y < h and x < w:
        if x < 0:
            s, x = s[-x:], 0
        try:
            scr.addstr(y, x, s[: w - x - (1 if y == h - 1 else 0)], attr)
        except curses.error:
            pass


def center(scr, painter, y, s, fg, bold=True, bg=HUD_BG):
    w = scr.getmaxyx()[1]
    s = f" {s} "
    put(scr, y, max(0, (w - len(s)) // 2), s,
        painter.pair(fg, bg) | (curses.A_BOLD if bold else 0))


# --- game state --------------------------------------------------------------
class Game:
    def __init__(self, seed=None, demo=False):
        self.rng = random.Random(seed)
        self.segs = build_track(self.rng)
        self.L = len(self.segs) * SEG_LEN
        self.demo = demo
        self.z = -6 * SEG_LEN              # absolute distance driven (gate just ahead)
        self.x = 0.0                       # lateral, in road half-widths
        self.vx = 0.0
        self.speed = 0.0
        self.time = START_TIME
        self.next_cp = CP_EVERY * SEG_LEN
        self.cps = 0
        self.nitro = 2
        self.nitro_t = 0.0
        self.bg_off = 0.0
        self.sun_dx = 0.0
        self.shake = 0.0
        self.msg = ""
        self.msg_t = 0.0
        self.msg_fg = 226
        self.cars = []
        self.stars = [(self.rng.random(), self.rng.random() * 0.6) for _ in range(40)]
        self.mtn = self._mountains()
        add_cars(self, 16, 25 * SEG_LEN)

    @property
    def pos(self):
        return self.z % self.L

    def seg_at(self, z):
        return self.segs[int(z // SEG_LEN) % len(self.segs)]

    def _mountains(self):
        r = self.rng
        ph = [r.random() * 6 for _ in range(4)]
        out = []
        for i in range(240):
            t = i / 240 * 2 * math.pi
            v = (math.sin(t * 2 + ph[0]) * 0.45 + math.sin(t * 5 + ph[1]) * 0.25
                 + math.sin(t * 11 + ph[2]) * 0.18 + math.sin(t * 23 + ph[3]) * 0.12)
            out.append(max(0.0, (v + 0.85) / 1.85))
        return out

    def say(self, text, fg=226, dur=1.6):
        self.msg, self.msg_fg, self.msg_t = text, fg, dur

    # -- simulation --
    def update(self, dt, steer_dir, impulse, braking, racing):
        pseg = self.seg_at(self.pos + PLAYER_Z)
        sp = self.speed / MAX_SPEED
        self.update_cars(dt)

        top = MAX_SPEED * (NITRO_MULT if self.nitro_t > 0 else 1)
        self.nitro_t = max(0.0, self.nitro_t - dt)
        if not racing:
            self.speed += BRAKING * 0.5 * dt
        elif braking:
            self.speed += BRAKING * dt
        elif self.speed < top:
            self.speed += ACCEL * (2 if self.nitro_t > 0 else 1) * dt
        if self.speed > top:
            self.speed += DECEL * dt
        if abs(self.x) > 1 and self.speed > OFFROAD_LIMIT:
            self.speed += OFFROAD_DECEL * dt
        self.speed = max(0.0, self.speed)

        # steering: touch hold accelerates, key presses are impulses, else damp
        if steer_dir:
            if self.vx * steer_dir < 0:
                self.vx = 0.0
            self.vx += steer_dir * STEER_ACC * dt
        else:
            self.vx *= math.exp(-STEER_DAMP * dt)
        if impulse:
            if self.vx * impulse < 0:
                self.vx = 0.0
            self.vx += impulse * STEER_IMPULSE
        self.vx = max(-STEER_MAX, min(STEER_MAX, self.vx))
        self.x += self.vx * dt * min(1.0, sp * 2)
        self.x -= dt * 2 * sp * sp * pseg.curve * CENTRIFUGAL
        self.x = max(-3.0, min(3.0, self.x))

        self.z += self.speed * dt
        self.bg_off += pseg.curve * sp * dt * 6
        self.sun_dx = (self.sun_dx - pseg.curve * sp * dt * 0.15) * math.exp(-dt * 0.25)
        self.shake = max(0.0, self.shake - dt)
        self.msg_t = max(0.0, self.msg_t - dt)

        if not self.demo:
            self.collide(pseg)
            if self.z + PLAYER_Z >= self.next_cp:
                self.next_cp += CP_EVERY * SEG_LEN
                self.cps += 1
                bonus = max(10, int(27 - 1.5 * self.cps))
                self.time += bonus
                self.nitro = min(NITRO_MAX, self.nitro + 1)
                add_cars(self, 4, 40 * SEG_LEN)
                self.say(f"CHECKPOINT  +{bonus}s", 51, 2.2)

    def update_cars(self, dt):
        rng = self.rng
        for c in self.cars:
            c.timer -= dt
            if c.timer <= 0:
                c.timer = rng.uniform(3, 9)
                i = LANE_X.index(c.target)
                c.target = LANE_X[max(0, min(2, i + rng.choice((-1, 1))))]
            d = c.target - c.off
            c.off += max(-0.5 * dt, min(0.5 * dt, d))
            c.z = (c.z + c.speed * dt) % self.L
            s = self.seg_at(c.z)
            if s is not c.seg:
                c.seg.cars.remove(c)
                s.cars.append(c)
                c.seg = s

    def nudge_to(self, target_pos):
        d = (target_pos - self.pos) % self.L
        if d > self.L / 2:
            d -= self.L
        self.z += d

    def crash(self, text="CRASH!"):
        self.shake = 0.35
        self.nitro_t = 0.0
        self.say(text, 196, 0.9)

    def collide(self, pseg):
        half = CAR_W / ROAD_W / 2
        if abs(self.x) > 1:
            for off, kind in pseg.sprites:
                cw = SPRITES[kind].coll
                if cw and abs(self.x - off) < half + cw / 2:
                    self.speed = MAX_SPEED / 6
                    self.nudge_to(pseg.i * SEG_LEN - PLAYER_Z - SEG_LEN)
                    self.crash("SMASH!")
                    return
        pz = (self.pos + PLAYER_Z) % self.L
        for c in self.cars:
            if self.speed <= c.speed:
                continue
            dz = (c.z - pz) % self.L
            if dz > self.L / 2:
                dz -= self.L
            if -250 < dz < 120 and abs(self.x - c.off) < half * 2 * 0.8:
                self.speed = c.speed * 0.6
                self.nudge_to(c.z - PLAYER_Z - 150)
                self.crash()
                return

    # -- rendering --
    def render(self, fb, W, H):
        hy = int(H * 0.42) + (self.rng.choice((-1, 1)) if self.shake > 0 else 0)
        Kx = min(W / 2, H * 0.6)        # keep proportions on wide screens
        Ky = H - hy
        mid = W / 2
        self.draw_background(fb, W, H, hy)

        segs = self.segs
        N = len(segs)
        pos = self.pos
        base_i = int(pos // SEG_LEN)
        base = segs[base_i]
        pct = (pos % SEG_LEN) / SEG_LEN
        pseg = self.seg_at(pos + PLAYER_Z)
        ppct = ((pos + PLAYER_Z) % SEG_LEN) / SEG_LEN
        cam_y = CAM_H + pseg.y1 + (pseg.y2 - pseg.y1) * ppct
        cam_x = self.x * ROAD_W

        x = 0.0
        dx = -base.curve * pct
        maxy = H
        proj = []
        for n in range(DRAW_DIST):
            seg = segs[(base_i + n) % N]
            z1 = (base_i + n) * SEG_LEN - pos
            z2 = z1 + SEG_LEN
            x1c = -cam_x - x
            x2c = -cam_x - x - dx
            x += dx
            dx += seg.curve
            if z1 <= CAM_DEPTH:
                continue
            s1 = CAM_DEPTH / z1
            s2 = CAM_DEPTH / z2
            sx1 = mid + s1 * x1c * Kx
            sx2 = mid + s2 * x2c * Kx
            sy1 = hy - s1 * (seg.y1 - cam_y) * Ky
            sy2 = hy - s2 * (seg.y2 - cam_y) * Ky
            w1 = s1 * ROAD_W * Kx
            w2 = s2 * ROAD_W * Kx
            proj.append((seg, sx1, sy1, s1, sx2, sy2, s2, maxy))
            if sy2 >= sy1 or sy2 >= maxy:
                continue
            self.fill_segment(fb, W, seg, sx1, sy1, w1, sx2, sy2, w2, maxy)
            maxy = sy2

        for seg, sx1, sy1, s1, sx2, sy2, s2, clip in reversed(proj):
            for off, kind in seg.sprites:
                spr = SPRITES[kind]
                draw_sprite(fb, W, H, spr, sx1 + s1 * off * ROAD_W * Kx, sy1,
                            s1 * spr.world_w * Kx, clip)
            for c in seg.cars:
                p = (c.z % SEG_LEN) / SEG_LEN
                s = s1 + (s2 - s1) * p
                cx = sx1 + (sx2 - sx1) * p
                cy = sy1 + (sy2 - sy1) * p
                draw_sprite(fb, W, H, c.spr, cx + s * c.off * ROAD_W * Kx, cy,
                            s * CAR_W * Kx, clip)

        bounce = 0
        if self.speed > 0 and (abs(self.x) > 1 or self.shake > 0):
            bounce = self.rng.choice((0, 1))
        pw = CAR_W / CAM_H * Kx
        draw_sprite(fb, W, H, PLAYER, mid, H - bounce, pw, H)
        if self.nitro_t > 0:
            fy = H - 1 - bounce
            for fx in (-0.36, 0.28):
                xx = int(mid + fx * pw)
                if 0 <= xx < W - 1 and 0 <= fy < H:
                    fb[fy][xx] = fb[fy][xx + 1] = self.rng.choice((226, 208, 51))

    def draw_background(self, fb, W, H, hy):
        nsky = len(SKY)
        for y in range(H):
            if y < hy:
                fb[y][:] = [SKY[min(nsky - 1, y * nsky // max(1, hy))]] * W
            else:
                fb[y][:] = [GRASS[1]] * W
        for sx, sy in self.stars:
            y = int(sy * hy)
            xx = int((sx * W * 1.5 - self.bg_off * 0.5) % (W * 1.5))
            if 0 <= y < hy and xx < W:
                fb[y][xx] = STAR
        # sun
        r = max(3, int(min(W, hy * 2) * 0.27))
        cy = hy - r * 0.35
        cx = W / 2 + W * 0.35 * math.tanh(self.sun_dx)
        for y in range(max(0, int(cy - r)), hy):
            dy = y + 0.5 - cy
            if abs(dy) >= r:
                continue
            k = dy / r
            if k > 0.1 and int(dy) % 4 < int(k * 3.5):
                continue
            half = math.sqrt(r * r - dy * dy)
            a, b = max(0, int(cx - half)), min(W, int(cx + half))
            if b > a:
                fb[y][a:b] = [SUN[min(len(SUN) - 1, int((dy + r) / (2 * r) * len(SUN)))]] * (b - a)
        # mountains
        mh = max(2, int(hy * 0.3))
        n = len(self.mtn)
        off = int(self.bg_off * 6)
        for xx in range(W):
            h = int(self.mtn[(xx + off) % n] * mh)
            if h <= 0:
                continue
            top = hy - h
            fb[top][xx] = MTN_EDGE
            for y in range(top + 1, hy):
                fb[y][xx] = MTN

    @staticmethod
    def fill_segment(fb, W, seg, x1, y1, w1, x2, y2, w2, maxy):
        dark = seg.dark
        g, rc, rd = GRASS[dark], RUMBLE_C[dark], ROAD_C[dark]
        ya = max(0, int(math.ceil(y2)))
        yb = min(int(math.ceil(min(y1, maxy))), len(fb))
        span = y1 - y2
        grass = [g] * W
        for y in range(ya, yb):
            t = (y1 - y) / span
            cx = x1 + (x2 - x1) * t
            w = w1 + (w2 - w1) * t
            row = fb[y]
            row[:] = grass
            r = w / 6 + 0.5
            a, b = max(0, int(cx - w - r)), min(W, int(cx + w + r))
            if b > a:
                row[a:b] = [rc] * (b - a)
            a, b = max(0, int(cx - w)), min(W, int(cx + w))
            if b > a:
                row[a:b] = [rd] * (b - a)
            if not dark and w > 5:
                lw = max(1, int(w / 20))
                for k in range(1, LANES):
                    lx = int(cx - w + 2 * w * k / LANES - lw / 2 + 0.5)
                    a, b = max(0, lx), min(W, lx + lw)
                    if b > a:
                        row[a:b] = [LANE] * (b - a)


def draw_sprite(fb, W, H, spr, cx, by, dw, clip):
    if dw < 0.7:
        return
    iw = max(1, int(dw + 0.5))
    ih = max(1, int(dw * spr.ah / spr.aw + 0.5))
    x0 = int(cx - iw / 2 + 0.5)
    y0 = int(by + 0.5) - ih
    xa, xb = max(0, x0), min(W, x0 + iw)
    if xb <= xa:
        return
    aw, ah, px = spr.aw, spr.ah, spr.px
    cols = [(i, (i - x0) * aw // iw) for i in range(xa, xb)]
    for j in range(max(0, y0), min(H, int(clip), y0 + ih)):
        srow = px[(j - y0) * ah // ih]
        row = fb[j]
        for i, si in cols:
            c = srow[si]
            if c >= 0:
                row[i] = c


# --- input -------------------------------------------------------------------
class Input:
    def __init__(self):
        self.hold = 0           # touch steering held: -1/0/1
        self.hold_brake = False
        self.hold_t = 0.0
        self.pulse = 0          # short steer from a quick tap
        self.pulse_until = 0.0
        self.brake_until = 0.0
        self.reset_frame()

    def reset_frame(self):
        self.impulse = 0
        self.nitro = False
        self.pause = False
        self.quit = False
        self.any = False

    def zone(self, mx, my, w, h):
        if mx < w * 0.38:
            return "L"
        if mx > w * 0.62:
            return "R"
        return "N" if my < h * 0.5 else "B"

    def read(self, scr, now):
        self.reset_frame()
        h, w = scr.getmaxyx()
        while True:
            k = scr.getch()
            if k == -1:
                break
            self.any = True
            if k in (curses.KEY_LEFT, ord("a"), ord("A"), ord("h")):
                self.impulse -= 1
            elif k in (curses.KEY_RIGHT, ord("d"), ord("D"), ord("l")):
                self.impulse += 1
            elif k in (curses.KEY_DOWN, ord("s"), ord("S"), ord("j"), ord(" ")):
                self.brake_until = now + 0.2
            elif k in (curses.KEY_UP, ord("w"), ord("W"), ord("k"), ord("n")):
                self.nitro = True
            elif k in (ord("p"), ord("P")):
                self.pause = True
            elif k in (ord("q"), ord("Q"), 27):
                self.quit = True
            elif k == curses.KEY_MOUSE:
                try:
                    _, mx, my, _, bs = curses.getmouse()
                except curses.error:
                    continue
                z = self.zone(mx, my, w, h)
                if bs & curses.BUTTON1_PRESSED:
                    self.hold_t = now
                    if z == "L":
                        self.hold = -1
                    elif z == "R":
                        self.hold = 1
                    elif z == "B":
                        self.hold_brake = True
                    else:
                        self.nitro = True
                if bs & curses.BUTTON1_RELEASED:
                    quick = now - self.hold_t < 0.1
                    if self.hold and quick:
                        self.pulse, self.pulse_until = self.hold, now + 0.1
                    if self.hold_brake and quick:
                        self.brake_until = now + 0.25
                    self.hold, self.hold_brake = 0, False
                if bs & curses.BUTTON1_CLICKED:
                    if z in "LR":
                        self.pulse, self.pulse_until = (-1 if z == "L" else 1), now + 0.1
                    elif z == "B":
                        self.brake_until = now + 0.25
                    else:
                        self.nitro = True
        if (self.hold or self.hold_brake) and now - self.hold_t > 4:
            self.hold, self.hold_brake = 0, False   # lost a release event

    def steer(self, now):
        return self.hold or (self.pulse if now < self.pulse_until else 0)

    def braking(self, now):
        return self.hold_brake or now < self.brake_until


# --- screens -----------------------------------------------------------------
def load_hi():
    try:
        with open(HISCORE) as f:
            return float(f.read().strip() or 0)
    except (OSError, ValueError):
        return 0.0


def save_hi(v):
    try:
        with open(HISCORE, "w") as f:
            f.write(f"{v:.2f}")
    except OSError:
        pass


def layout(scr):
    h, w = scr.getmaxyx()
    rows = h - 2
    return h, w, rows, rows * 2


def too_small(scr, painter, h, w):
    if w >= 30 and h >= 14:
        return False
    scr.erase()
    put(scr, h // 2, 0, "window too small"[:w], painter.pair(201, HUD_BG))
    scr.refresh()
    return True


class Frame:
    """Reusable framebuffer that follows the terminal size."""

    def __init__(self):
        self.W = self.H = 0
        self.fb = []

    def get(self, W, H):
        if (W, H) != (self.W, self.H):
            self.W, self.H = W, H
            self.fb = [[0] * W for _ in range(H)]
        return self.fb


def bar(scr, painter, y, w, text, fg=245):
    put(scr, y, 0, text.ljust(w)[:w], painter.pair(fg, HUD_BG))


def title(scr, painter, frame, inp, best):
    demo = Game(demo=True)
    demo.speed = MAX_SPEED * 0.6
    t0 = last = time.monotonic()
    while True:
        now = time.monotonic()
        dt = min(0.1, now - last)
        last = now
        inp.read(scr, now)
        if inp.quit:
            return False
        if inp.any:
            return True
        h, w, rows, H = layout(scr)
        if too_small(scr, painter, h, w):
            time.sleep(0.2)
            continue
        demo.x = 0.35 * math.sin((now - t0) * 0.4)
        demo.update(dt, 0, 0, False, True)
        demo.speed = MAX_SPEED * 0.6
        demo.x = 0.35 * math.sin((now - t0) * 0.4)
        fb = frame.get(w, H)
        demo.render(fb, w, H)
        painter.blit(scr, fb, w, rows, 1)
        bar(scr, painter, 0, w, "")
        bar(scr, painter, h - 1, w, "")
        y = max(1, rows // 6)
        center(scr, painter, y, "N E O N   D R I V E", 201)
        center(scr, painter, y + 1, "~ outrun the clock ~", 51, bold=False)
        if best > 0:
            center(scr, painter, y + 3, f"best  {best:.2f} km", 226)
        blink = int(now * 2) % 2
        center(scr, painter, y + 5, "tap or press any key" if blink else " " * 20, 231)
        put(scr, h - 1, 0, " ◀▶ steer  ▼ brake  ▲ nitro  p pause  q quit"[:w - 1],
            painter.pair(245, HUD_BG))
        if rows > 20:
            center(scr, painter, rows - 3, "touch: hold left/right to steer", 245, False)
            center(scr, painter, rows - 2, "centre-top nitro · centre-bottom brake", 245, False)
        scr.refresh()
        time.sleep(max(0.0, TICK - (time.monotonic() - now)))


def play(scr, painter, frame, inp):
    """Run one race. Returns (km, checkpoints, quit_requested)."""
    g = Game()
    start = time.monotonic()
    last = start
    paused = False
    over_t = None
    while True:
        now = time.monotonic()
        dt = min(0.1, now - last)
        last = now
        inp.read(scr, now)
        if inp.quit:
            return max(0.0, g.z) / UNITS_PER_M / 1000, g.cps, True
        if inp.pause:
            paused = not paused
        h, w, rows, H = layout(scr)
        if too_small(scr, painter, h, w):
            time.sleep(0.2)
            last = time.monotonic()
            continue
        countdown = 3 - (now - start)
        if paused:
            dt = 0.0
        else:
            racing = countdown <= 0 and g.time > 0
            if racing and inp.nitro and g.nitro > 0 and g.nitro_t <= 0:
                g.nitro -= 1
                g.nitro_t = NITRO_TIME
                g.say("N I T R O", 208, 1.0)
            if countdown > 0:
                g.update(dt, 0, 0, True, True)   # held at the line
                g.speed = 0.0
            else:
                g.update(dt, inp.steer(now) if racing else 0, inp.impulse if racing else 0,
                         inp.braking(now), racing)
                g.time = max(0.0, g.time - dt)
                if g.time <= 0 and over_t is None:
                    over_t = now
                    g.say("TIME UP", 196, 99)
            if over_t and g.speed <= 0 and now - over_t > 1.5:
                return max(0.0, g.z) / UNITS_PER_M / 1000, g.cps, False

        fb = frame.get(w, H)
        g.render(fb, w, H)
        painter.blit(scr, fb, w, rows, 1)

        km = max(0.0, g.z) / UNITS_PER_M / 1000
        kmh = int(g.speed * KMH)
        t = int(math.ceil(g.time))
        tfg = 196 if g.time < 10 and int(now * 4) % 2 else 226
        bar(scr, painter, 0, w, "")
        put(scr, 0, 1, f"⏱ {t:>3}", painter.pair(tfg, HUD_BG) | curses.A_BOLD)
        put(scr, 0, 9, f"{kmh:>3} km/h", painter.pair(51, HUD_BG) | curses.A_BOLD)
        right = f"{km:6.2f} km  CP {g.cps}"
        put(scr, 0, w - len(right) - 1, right, painter.pair(201, HUD_BG) | curses.A_BOLD)

        boost = "▮" * g.nitro + "▯" * (NITRO_MAX - g.nitro)
        bar(scr, painter, h - 1, w, "")
        put(scr, h - 1, 1, "NITRO ", painter.pair(208 if g.nitro_t > 0 else 245, HUD_BG)
            | curses.A_BOLD)
        put(scr, h - 1, 7, boost, painter.pair(208, HUD_BG))
        hint = "▲ boost  ▼ brake  p pause"
        if w - 15 > len(hint):
            put(scr, h - 1, w - len(hint) - 2, hint, painter.pair(240, HUD_BG))

        my = max(1, rows // 4)
        if paused:
            center(scr, painter, my, "P A U S E D", 51)
            center(scr, painter, my + 1, "p to resume · q to quit", 245, False)
        elif countdown > 0:
            center(scr, painter, my, str(int(countdown) + 1), 226)
        elif countdown > -0.8:
            center(scr, painter, my, "G O !", 46)
        elif g.msg_t > 0:
            center(scr, painter, my, g.msg, g.msg_fg)
        scr.refresh()
        time.sleep(max(0.0, TICK - (time.monotonic() - now)))


def game_over(scr, painter, inp, km, cps, best, new_best):
    h, w = scr.getmaxyx()
    y = max(1, (h - 8) // 2)
    lines = [
        ("T I M E   U P", 196),
        ("", 0),
        (f"distance  {km:.2f} km", 226),
        (f"checkpoints  {cps}", 51),
        ("★ new best! ★" if new_best else f"best  {best:.2f} km", 201),
        ("", 0),
        ("r / tap: again   q: quit", 245),
    ]
    width = max(len(s) for s, _ in lines) + 4
    for i, (s, fg) in enumerate(lines):
        x = max(0, (w - width) // 2)
        put(scr, y + i, x, s.center(width), painter.pair(fg or 245, HUD_BG) | curses.A_BOLD)
    scr.refresh()
    time.sleep(0.6)                     # don't let a held key skip this
    scr.nodelay(False)
    try:
        while True:
            k = scr.getch()
            if k in (ord("q"), ord("Q"), 27):
                return False
            if k in (ord("r"), ord("R"), ord(" "), 10, 13, curses.KEY_ENTER, curses.KEY_MOUSE):
                if k == curses.KEY_MOUSE:
                    try:
                        _, _, _, _, bs = curses.getmouse()
                    except curses.error:
                        continue
                    if not bs & (curses.BUTTON1_PRESSED | curses.BUTTON1_CLICKED):
                        continue
                return True
    finally:
        scr.nodelay(True)


def main(scr):
    curses.curs_set(0)
    curses.start_color()
    curses.use_default_colors()
    curses.mousemask(curses.BUTTON1_PRESSED | curses.BUTTON1_RELEASED | curses.BUTTON1_CLICKED)
    curses.mouseinterval(0)
    scr.nodelay(True)
    scr.keypad(True)
    painter = Painter()
    frame = Frame()
    inp = Input()
    best = load_hi()
    if not title(scr, painter, frame, inp, best):
        return
    while True:
        km, cps, quit_ = play(scr, painter, frame, inp)
        new_best = km > best and km > 0
        if new_best:
            best = km
            save_hi(best)
        if quit_ or not game_over(scr, painter, inp, km, cps, best, new_best):
            return


if __name__ == "__main__":
    os.environ.setdefault("ESCDELAY", "25")
    curses.wrapper(main)
