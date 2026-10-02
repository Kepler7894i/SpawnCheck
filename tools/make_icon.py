"""Generates the Spawn Check icon (pixel art, 32x32 grid scaled up). Usage: python tools/make_icon.py"""
import random
from PIL import Image

N = 32
STONE = [(88, 88, 92), (96, 96, 100), (80, 80, 84), (104, 104, 108)]
GREEN, YELLOW, RED = (76, 217, 100), (250, 204, 21), (239, 68, 68)
# 4x4 floor of 8px tiles; G/Y/R = spawn marker colour of that tile, . = bare floor
LAYOUT = ["G G Y .", "G Y R R", ". G R .", "G G . R"]

random.seed(7)
img = Image.new("RGB", (N, N))
px = img.load()
for y in range(N):
    for x in range(N):
        px[x, y] = random.choice(STONE)

def mix(a, b, t):
    return tuple(round(a[i] * (1 - t) + b[i] * t) for i in range(3))

for ty, row in enumerate(LAYOUT):
    for tx, c in enumerate(row.split()):
        x0, y0 = tx * 8, ty * 8
        col = {"G": GREEN, "Y": YELLOW, "R": RED}.get(c)
        for y in range(8):
            for x in range(8):
                edge = x in (0, 7) or y in (0, 7)
                p = px[x0 + x, y0 + y]
                if edge:
                    px[x0 + x, y0 + y] = mix(p, (20, 20, 24), 0.7)  # grid line
                elif col:
                    inner = 2 <= x <= 5 and 2 <= y <= 5
                    px[x0 + x, y0 + y] = col if inner else mix(p, col, 0.45)

img.resize((128, 128), Image.NEAREST).save("common/src/main/resources/assets/spawncheck/icon.png")
img.resize((512, 512), Image.NEAREST).save("docs/images/icon-512.png")
