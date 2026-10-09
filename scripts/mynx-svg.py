#!/usr/bin/env python3
# The README's "mynx" title (docs/images/mynx.svg) from the splash art's
# pixels, in Neon's full colours: scripts/mynx-svg.py tools/lib/mynx-art >docs/images/mynx.svg
import subprocess, sys
pix = subprocess.run([sys.argv[1], "--plain"], capture_output=True, text=True).stdout.rstrip("\n").split("\n")
while pix and not pix[-1].strip(): pix.pop()
W, H, PAD = 4, 8, 16          # a pixel is twice as tall as wide, as on the terminal
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
out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">',
       f'  <rect width="{w}" height="{h}" rx="16" fill="#0D0221"/>']
for k in ("o", "#"):
    out.append(f'  <path fill="{cols[k]}" d="{"".join(paths[k])}"/>')
out.append("</svg>")
print("\n".join(out))
