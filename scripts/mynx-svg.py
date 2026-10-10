#!/usr/bin/env python3
# The "mynx" title from the splash art's pixels, in Neon's full colours:
# the README's (docs/images/mynx.svg) and, with --vector, the setup
# screen's Android drawable (app/src/main/res/drawable/mynx_title.xml),
# which has no background of its own:
#   scripts/mynx-svg.py tools/lib/mynx-art >docs/images/mynx.svg
#   scripts/mynx-svg.py --vector tools/lib/mynx-art >app/src/main/res/drawable/mynx_title.xml
import subprocess, sys
vector = "--vector" in sys.argv
pix = subprocess.run([sys.argv[-1], "--plain"], capture_output=True, text=True).stdout.rstrip("\n").split("\n")
while pix and not pix[-1].strip(): pix.pop()
W, H = 4, 8                   # a pixel is twice as tall as wide, as on the terminal
PAD = 0 if vector else 16
cols = {"#": "#00F3FF", "o": "#FF0BBB"}
paths = {k: [] for k in cols}
for y, row in enumerate(pix):
    x = 0
    while x < len(row):
        c = row[x]
        if c in cols:
            e = x
            while e < len(row) and row[e] == c: e += 1
            paths[c].append(f"M{PAD+x*W},{PAD+y*H}h{(e-x)*W}v{H}h-{(e-x)*W}z")
            x = e
        else: x += 1
w, h = 2*PAD + len(pix[0])*W, 2*PAD + len(pix)*H
if vector:
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- Made from tools/lib/mynx-art by scripts/mynx-svg.py: change the art, run it again. -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           f'    android:width="{w}dp"', f'    android:height="{h}dp"',
           f'    android:viewportWidth="{w}"', f'    android:viewportHeight="{h}">']
    for k in ("o", "#"):
        out.append(f'    <path android:fillColor="{cols[k]}" android:pathData="{"".join(paths[k])}"/>')
    out.append("</vector>")
else:
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">',
           f'  <rect width="{w}" height="{h}" rx="16" fill="#0D0221"/>']
    for k in ("o", "#"):
        out.append(f'  <path fill="{cols[k]}" d="{"".join(paths[k])}"/>')
    out.append("</svg>")
print("\n".join(out))
