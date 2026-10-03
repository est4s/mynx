#!/usr/bin/env python3
"""neonflap — a one-button synthwave flapper for the terminal.

Any key (space/enter/up) flaps. q quits. Built to fit a phone in portrait.
"""
import curses
import os
import random
import time

HISCORE = os.path.expanduser("~/.neonflap_hiscore")
GRAVITY = 0.045
FLAP = -0.55
MAX_FALL = 0.9
TICK = 1 / 30
PIPE_EVERY = 22  # columns between pipes
GAP = 6


def load_hi():
    try:
        with open(HISCORE) as f:
            return int(f.read().strip() or 0)
    except (OSError, ValueError):
        return 0


def save_hi(score):
    try:
        with open(HISCORE, "w") as f:
            f.write(str(score))
    except OSError:
        pass


def init_colors():
    curses.start_color()
    curses.use_default_colors()
    pairs = [
        (1, curses.COLOR_MAGENTA),  # pipes
        (2, curses.COLOR_CYAN),     # bird
        (3, curses.COLOR_YELLOW),   # score
        (4, curses.COLOR_BLUE),     # stars / ground
        (5, curses.COLOR_RED),      # crash
    ]
    for n, fg in pairs:
        curses.init_pair(n, fg, -1)


def put(win, y, x, s, attr=0):
    h, w = win.getmaxyx()
    if 0 <= y < h and x < w:
        if x < 0:
            s, x = s[-x:], 0
        try:
            win.addstr(y, x, s[: w - x - (1 if y == h - 1 else 0)], attr)
        except curses.error:
            pass


def title(scr, hi):
    scr.nodelay(False)
    scr.clear()
    h, w = scr.getmaxyx()
    lines = [
        ("N E O N F L A P", curses.color_pair(1) | curses.A_BOLD),
        ("", 0),
        ("~ ▶ ~", curses.color_pair(2) | curses.A_BOLD),
        ("", 0),
        ("any key: flap   q: quit", curses.color_pair(4)),
        (f"best: {hi}", curses.color_pair(3)),
        ("", 0),
        ("press a key to start", curses.A_BLINK),
    ]
    top = h // 2 - len(lines) // 2
    for i, (s, a) in enumerate(lines):
        put(scr, top + i, (w - len(s)) // 2, s, a)
    scr.refresh()
    return scr.getch() not in (ord("q"), ord("Q"))


def play(scr, hi):
    scr.nodelay(True)
    h, w = scr.getmaxyx()
    play_h = h - 2  # last row is ground, first is HUD
    bx = max(4, w // 5)
    by, vy = play_h / 2, 0.0
    pipes = []  # [x, gap_top, scored]
    stars = [(random.randrange(1, play_h), random.randrange(w)) for _ in range(w // 3)]
    score, frame = 0, 0
    next_pipe = w // 2

    while True:
        start = time.monotonic()
        key = scr.getch()
        while scr.getch() != -1:  # drain key repeat
            pass
        if key in (ord("q"), ord("Q")):
            return score, False
        if key != -1:
            vy = FLAP

        vy = min(vy + GRAVITY, MAX_FALL)
        by += vy
        frame += 1

        if frame % 2 == 0:
            for p in pipes:
                p[0] -= 1
            pipes = [p for p in pipes if p[0] > -3]
            next_pipe -= 1
            if next_pipe <= 0:
                gap_top = random.randint(2, max(2, play_h - GAP - 1))
                pipes.append([w, gap_top, False])
                next_pipe = PIPE_EVERY
            if frame % 6 == 0:
                stars = [(y, (x - 1) % w) for y, x in stars]

        iy = int(by)
        dead = iy < 1 or iy >= play_h
        for p in pipes:
            if p[0] <= bx <= p[0] + 2 and not (p[1] <= iy < p[1] + GAP):
                dead = True
            if not p[2] and p[0] + 2 < bx:
                p[2] = True
                score += 1

        scr.erase()
        for y, x in stars:
            put(scr, y, x, "·", curses.color_pair(4) | curses.A_DIM)
        pipe_attr = curses.color_pair(1) | curses.A_BOLD
        for x, gt, _ in pipes:
            for y in range(1, play_h):
                if gt <= y < gt + GAP:
                    continue
                cap = y in (gt - 1, gt + GAP)
                put(scr, y, x, "▀▀▀" if cap and y == gt + GAP else "▄▄▄" if cap else "███", pipe_attr)
        bird = "✕" if dead else ("▲" if vy < 0 else "▶")
        put(scr, iy, bx, bird, curses.color_pair(5 if dead else 2) | curses.A_BOLD)
        put(scr, play_h, 0, "▔" * w, curses.color_pair(4))
        hud = f" score {score}   best {max(hi, score)} "
        put(scr, 0, (w - len(hud)) // 2, hud, curses.color_pair(3) | curses.A_BOLD)
        scr.refresh()

        if dead:
            time.sleep(0.6)
            return score, True
        time.sleep(max(0, TICK - (time.monotonic() - start)))


def game_over(scr, score, hi, new_best):
    scr.nodelay(False)
    h, w = scr.getmaxyx()
    msg = [
        ("G A M E   O V E R", curses.color_pair(5) | curses.A_BOLD),
        (f"score {score}", curses.color_pair(3) | curses.A_BOLD),
        ("★ new best! ★" if new_best else f"best {hi}", curses.color_pair(1) | curses.A_BOLD),
        ("", 0),
        ("r: retry   q: quit", curses.color_pair(4)),
    ]
    top = h // 2 - len(msg) // 2
    for i, (s, a) in enumerate(msg):
        put(scr, top + i, (w - len(s)) // 2, " " + s + " ", a)
    scr.refresh()
    curses.flushinp()
    time.sleep(0.4)
    while True:
        k = scr.getch()
        if k in (ord("q"), ord("Q")):
            return False
        if k in (ord("r"), ord("R"), ord(" "), 10, curses.KEY_ENTER):
            return True


def main(scr):
    curses.curs_set(0)
    init_colors()
    hi = load_hi()
    if not title(scr, hi):
        return
    while True:
        score, crashed = play(scr, hi)
        new_best = score > hi
        if new_best:
            hi = score
            save_hi(hi)
        if not crashed or not game_over(scr, score, hi, new_best):
            return


if __name__ == "__main__":
    os.environ.setdefault("ESCDELAY", "25")
    curses.wrapper(main)
