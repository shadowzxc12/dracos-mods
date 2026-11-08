#!/usr/bin/env python3
"""Server Backpacks: real backpack-shaped 3D models (instead of plain boxes) everywhere - inventory, hand and
on the back - with crisp pixel-art textures in each backpack's colour.

The three base models (small / medium / large) get new geometry: rounded body, top flap with buckled straps,
front pocket, side pockets, carry handle, shoulder straps on the back, leather bottom, and a rolled bedroll on
the large one. The mod's own display transforms are kept so placement stays right.
usage: backpacks.py <pack_dir>   (run after the mod assets were copied into the pack)"""
import glob, json, os, sys
import numpy as np
from PIL import Image

PACK = sys.argv[1]
NS = os.path.join(PACK, "assets", "serverbackpacks")
M = os.path.join(NS, "models/item")
T = os.path.join(NS, "textures/item/model")

DYE = {"white": "#e4e4e4", "orange": "#e07a1f", "magenta": "#b54bb5", "light_blue": "#4fa8d8", "yellow": "#e8c53a",
       "lime": "#7cc433", "pink": "#e88aa8", "gray": "#5a5f63", "light_gray": "#9a9a94", "cyan": "#21909a",
       "purple": "#7b35b0", "blue": "#3a4bb0", "brown": "#7a4f2a", "green": "#4f6b23", "red": "#b0302a",
       "black": "#262629"}
SPECIAL = {"small": "#8b5a2b", "medium": "#8b5a2b", "large": "#8b5a2b", "lava_backpack": "#d94a10",
           "ender": "#16594f", "global": "#2f6fb5"}

# texture atlas (64x64, 1 uv unit = 4 px)
R = {"fab": [0, 0, 8, 8], "fab_d": [8, 0, 12, 4], "fab_l": [12, 0, 16, 4], "leather": [8, 4, 12, 8],
     "metal": [12, 4, 16, 8], "pocket": [0, 8, 8, 16], "flap": [8, 8, 16, 12], "roll": [8, 12, 16, 16]}


def hx(c):
    c = c.lstrip("#")
    return np.array([int(c[i:i + 2], 16) for i in (0, 2, 4)], float)


def shade(c, k):
    return np.clip(c * k if k < 1 else c + (255 - c) * (k - 1), 0, 255)


def texture(color, name):
    base = hx(color)
    rnd = np.random.RandomState(abs(hash(name)) % (2 ** 31))
    img = np.zeros((64, 64, 4))
    img[..., 3] = 255

    def fabric(x0, y0, x1, y1, k, stitch=False):
        h, w = (y1 - y0) * 4, (x1 - x0) * 4
        yy, xx = np.mgrid[0:h, 0:w]
        weave = 1 + ((xx + yy) % 2 == 0) * 0.04 - ((yy % 4) == 0) * 0.05 + (rnd.rand(h, w) - 0.5) * 0.06
        col = np.clip(shade(base, k)[None, None] * weave[..., None], 0, 255)
        if stitch:
            st = ((xx == 2) | (yy == 2) | (xx == w - 3) | (yy == h - 3)) & (((xx + yy) % 3) != 0)
            col[st] = shade(base, 1.35)
            rim = (xx == 0) | (yy == 0) | (xx == w - 1) | (yy == h - 1)
            col[rim] = shade(base, 0.6)
        img[y0 * 4:y1 * 4, x0 * 4:x1 * 4, :3] = col

    fabric(0, 0, 8, 8, 1.0)
    fabric(8, 0, 12, 4, 0.72)
    fabric(12, 0, 16, 4, 1.18)
    fabric(0, 8, 8, 16, 0.92, stitch=True)
    fabric(8, 8, 16, 12, 0.82, stitch=True)
    # leather straps
    leather = hx("#5b3a1e") if name not in ("lava_backpack", "ender") else shade(base, 0.45)
    h, w = 16, 16
    yy, xx = np.mgrid[0:h, 0:w]
    lc = leather[None, None] * (1 + (rnd.rand(h, w) - 0.5) * 0.08)[..., None]
    lc[(xx == 1) | (xx == w - 2)] = shade(leather, 1.25)
    img[16:32, 32:48, :3] = lc
    # metal buckles
    mc = np.zeros((16, 16, 3)) + hx("#c9c9c9")
    mc[(yy < 3) | (xx < 3)] = hx("#f2f2f2")
    mc[(yy > 12) | (xx > 12)] = hx("#7a7a7a")
    mc[5:11, 5:11] = hx("#3c3c3c")
    if name == "lava_backpack":
        mc[5:11, 5:11] = hx("#ffb020")
    img[16:32, 48:64, :3] = mc
    # bedroll
    roll = hx("#c9b48a") if name not in ("lava_backpack", "ender") else shade(base, 1.3)
    rc = roll[None, None] * (1 + (rnd.rand(16, 32) - 0.5) * 0.08)[..., None]
    ry = np.mgrid[0:16, 0:32][0]
    rc[ry % 5 == 0] = shade(roll, 0.78)
    img[48:64, 32:64, :3] = rc
    if name == "lava_backpack":                 # glowing lava cracks on the fabric
        yy, xx = np.mgrid[0:64, 0:64]
        n = np.sin(xx * 0.4 + np.sin(yy * 0.3) * 2) + np.sin(yy * 0.35 + xx * 0.1)
        crack = (np.abs(n) < 0.12) & (yy < 48) & ~((yy >= 16) & (yy < 32) & (xx >= 32))
        img[crack, :3] = hx("#ffcf3a")
    return Image.fromarray(img.astype(np.uint8), "RGBA")


def el(fr, to, region, front=None):
    faces = {f: {"uv": R[region], "texture": "#layer0"} for f in ("north", "south", "east", "west", "up", "down")}
    if front:
        faces["north"]["uv"] = R[front]
    return {"from": fr, "to": to, "faces": faces}


def model(size):
    H = {"small": 9.0, "medium": 10.5, "large": 12.0}[size]
    E = []
    # body: core + rounded sides/top
    E.append(el([4, 0.5, 7], [12, H - 0.5, 11.5], "fab"))
    E.append(el([3.5, 1.2, 7.4], [4, H - 1.2, 11.1], "fab_d"))
    E.append(el([12, 1.2, 7.4], [12.5, H - 1.2, 11.1], "fab_d"))
    E.append(el([4.6, H - 0.5, 7.5], [11.4, H, 11], "fab_l"))
    E.append(el([4, 0, 7], [12, 0.8, 11.5], "leather"))                    # leather bottom
    # top flap draped over the front
    E.append(el([4.3, H - 0.4, 6.6], [11.7, H + 0.3, 11.2], "flap"))
    E.append(el([4.3, H - 3.6, 6.4], [11.7, H + 0.3, 7.0], "flap", front="flap"))
    # front pocket with its own flap
    ph = H * 0.5
    E.append(el([5, 1.2, 5.4], [11, 1.2 + ph, 7], "fab", front="pocket"))
    E.append(el([4.8, ph - 0.2, 5.1], [11.2, ph + 1.4, 7], "flap"))
    # buckled straps (flap -> pocket)
    for x in (6.1, 9.1):
        E.append(el([x, 2.0, 5.0], [x + 0.8, ph + 1.6, 5.2], "leather"))
        E.append(el([x, H - 3.4, 6.2], [x + 0.8, H + 0.4, 6.4], "leather"))
        E.append(el([x - 0.2, 2.6, 4.8], [x + 1.0, 3.6, 5.1], "metal"))
        E.append(el([x - 0.2, H - 3.0, 6.0], [x + 1.0, H - 2.0, 6.3], "metal"))
    # side pockets (medium & large)
    if size != "small":
        for x0, x1 in ((2.6, 3.6), (12.4, 13.4)):
            E.append(el([x0, 1.0, 7.8], [x1, H * 0.48, 10.7], "fab_d"))
            E.append(el([x0 - 0.1, H * 0.48 - 0.8, 7.7], [x1 + 0.1, H * 0.48 + 0.2, 10.8], "leather"))
    # carry handle
    E.append(el([6.5, H + 0.3, 8.8], [9.5, H + 1.2, 9.4], "leather"))
    E.append(el([6.5, H, 8.8], [7.1, H + 1.2, 9.4], "leather"))
    E.append(el([8.9, H, 8.8], [9.5, H + 1.2, 9.4], "leather"))
    # shoulder straps on the back
    for x in (5.0, 9.8):
        E.append(el([x, 1.0, 11.5], [x + 1.2, H - 0.8, 12.1], "leather"))
    # bedroll on the large one
    if size == "large":
        E.append(el([3, H + 0.3, 7.9], [13, H + 2.5, 10.6], "roll"))
        E.append(el([2.8, H + 0.6, 8.2], [3, H + 2.2, 10.3], "roll"))
        E.append(el([13, H + 0.6, 8.2], [13.2, H + 2.2, 10.3], "roll"))
        for x in (5, 10.2):
            E.append(el([x, H + 0.2, 7.8], [x + 0.8, H + 2.6, 10.7], "leather"))
    return E


done = 0
for size in ("small", "medium", "large"):
    p = os.path.join(M, f"base_{size}.json")
    if not os.path.exists(p):
        continue
    m = json.load(open(p))
    m.pop("credit", None)
    m["texture_size"] = [64, 64]
    m["elements"] = model(size)
    json.dump(m, open(p, "w"))

for f in glob.glob(os.path.join(M, "model", "*.json")):
    name = os.path.basename(f)[:-5]
    color = SPECIAL.get(name)
    if color is None:
        for dye in sorted(DYE, key=len, reverse=True):
            if name.startswith(dye + "_"):
                color = DYE[dye]
                break
    if color is None:
        color = "#8b5a2b"
    texture(color, name).save(os.path.join(T, name + ".png"))
    # inventory / hand: show the 3D backpack instead of the flat icon
    item = os.path.join(NS, "items", name + ".json")
    if os.path.exists(item):
        d = json.load(open(item))
        mm = d.get("model", {})
        if mm.get("type") == "minecraft:select" and "fallback" in mm:
            mm["fallback"] = {"type": "minecraft:model", "model": f"serverbackpacks:item/model/{name}"}
            json.dump(d, open(item, "w"))
            done += 1
print("3D backpacks:", done)
