"""Real 3D item models for the legendary weapons.

Each weapon's 2D shape (shapes.py) is sampled along its length and turned into cuboid elements: blades get a
bevel (thick spine, thin glinting edges), guards and pommels stick out further, handles are thick, gems and glowing
parts protrude a little. Elements are built upright and then all rotated -45 degrees around the model centre so the
model lines up exactly with the 2D sprite - that way vanilla's handheld display transforms (hand, ground, frames)
fit without guessing. The inventory keeps the 2D icon.
"""
import numpy as np
from PIL import Image

import hq
import shapes

STEP = 0.5          # sampling step in the 32-grid
SC = 0.5            # 32-grid units -> model units (16 = one block)

# material swatches in the 32x32 atlas (uv units are 0..16, so each 8x8 px swatch = 4 uv units)
SW = {"blade_hi": (0, 0), "blade": (4, 0), "blade_lo": (8, 0), "edge": (12, 0),
      "accent": (0, 4), "accent_hi": (4, 4), "gold": (8, 4), "gold_hi": (12, 4),
      "leather": (0, 8), "wrap": (4, 8), "dark": (8, 8), "pommel": (12, 8)}


def atlas(st):
    stops = st["stops"]
    STEEL = ["#15181e", "#3c434f", "#7d8796", "#c9d0da", "#ffffff"]
    k = st.get("tint", 0.62)
    ramp = [hq.mixhex(STEEL[i], [stops[0], stops[1], stops[2], stops[3], "#ffffff"][i], k) for i in range(5)]
    gold = hq.tint(hq.GOLD, stops[2], 0.2)
    leather = hq.tint(hq.LEATHER, stops[1], 0.25)
    acc = hq.rgb(st["accent"])
    img = np.zeros((32, 32, 4))
    img[..., 3] = 255
    yy, xx = np.mgrid[0:8, 0:8]
    grad = (1 - yy / 7) * 0.5 + (1 - xx / 7) * 0.5          # light from the top-left on every swatch

    def put(name, cols, lo, hi, extra=None):
        u, v = SW[name]
        c = hq.ramp(cols, lo + (hi - lo) * grad)
        if extra is not None:
            c = extra(c)
        img[v * 2:v * 2 + 8, u * 2:u * 2 + 8, :3] = np.clip(c, 0, 255)

    put("blade_hi", ramp, 0.55, 0.85)
    put("blade", ramp, 0.40, 0.70)
    put("blade_lo", ramp, 0.22, 0.48)
    put("edge", ramp, 0.80, 1.00)
    put("accent", [hq.mixhex(st["accent"], stops[1], 0.4), st["accent"], st["accent"]], 0.2, 1.0)
    img[8:16, 8:16, :3] = np.clip(acc * 0.8 + (255 - acc) * 0.6 * grad[..., None], 0, 255)   # accent_hi: glowing core
    put("gold", gold, 0.25, 0.75)
    put("gold_hi", gold, 0.6, 1.0)
    put("leather", leather, 0.15, 0.5)
    put("wrap", leather, 0.35, 0.7, lambda c: c * (1 - 0.18 * ((xx + yy) % 4 < 2))[..., None])
    put("dark", [stops[0], stops[1], stops[2]], 0.1, 0.6)
    put("pommel", gold, 0.4, 0.95)
    return Image.fromarray(img.astype(np.uint8), "RGBA")


def sample(name):
    """Group map on a (u, v) grid: rows = along the weapon, cols = across."""
    arch, params = shapes.SHAPES[name]
    f = shapes.ARCH[arch](params)
    L = shapes.L
    us = np.arange(0, L + STEP, STEP)
    vs = np.arange(-15, 15 + STEP, STEP)
    g = np.zeros((len(us), len(vs)), dtype=np.int8)
    for i, u in enumerate(us):
        for j, v in enumerate(vs):
            ch = f(u, v)
            if ch:
                g[i, j] = hq.GROUP[ch]
    return us, vs, g


def material(group, depth, width):
    """(swatch, half-thickness in model units) for a cell. depth = cells to the nearest edge across the part."""
    if group == hq.HANDLE:
        return "wrap", 0.8
    if group == hq.GUARD:
        return ("gold_hi" if depth >= 2 else "gold"), 1.15
    if group == hq.ACCENT:
        return "accent_hi", 0.75
    # metal (blades, heads): spine thick, edges thin and bright
    if width <= 3:
        return "blade", 0.45
    if depth == 0:
        return "edge", 0.22
    if depth == 1:
        return "blade_hi", 0.38
    return ("blade" if depth < 4 else "blade_lo"), 0.55


def boxes(name):
    us, vs, g = sample(name)
    cells = []        # per row: list of (j0, j1, swatch, half)
    for i in range(len(us)):
        row = g[i]
        runs = []
        j = 0
        while j < len(vs):
            if row[j] == 0:
                j += 1
                continue
            k = j
            while k < len(vs) and row[k] == row[j]:
                k += 1
            # split the part run into bevel bands
            width = k - j
            for c in range(j, k):
                d = min(c - j, k - 1 - c)
                sw, half = material(row[j], d, width)
                if runs and runs[-1][1] == c and runs[-1][2] == sw and runs[-1][3] == half:
                    runs[-1][1] = c + 1
                else:
                    runs.append([c, c + 1, sw, half])
            j = k
        cells.append(runs)
    # merge identical runs over consecutive rows into taller boxes
    out = []
    open_ = {}
    for i, runs in enumerate(cells + [[]]):
        keys = {(r[0], r[1], r[2], r[3]) for r in runs}
        for key in list(open_):
            if key not in keys:
                out.append((open_.pop(key), i, key))
        for key in keys:
            if key not in open_:
                open_[key] = i
    return us, vs, out


def model(name, tex, parent):
    us, vs, bx = boxes(name)
    L = shapes.L
    z = shapes.ZOOM
    E = []
    for (i0, i1, (j0, j1, sw, half)) in bx:
        u0, u1 = us[i0] - STEP / 2, us[min(i1, len(us)) - 1] + STEP / 2
        v0, v1 = vs[j0] - STEP / 2, vs[j1 - 1] + STEP / 2
        y0, y1 = 8 + (u0 - L / 2) * SC / z, 8 + (u1 - L / 2) * SC / z
        x0, x1 = 8 + v0 * SC / z, 8 + v1 * SC / z
        su, sv = SW[sw]
        uv = [su, sv, su + 4, sv + 4]
        faces = {f: {"uv": uv, "texture": "#t"} for f in ("north", "south", "east", "west", "up", "down")}
        E.append({"from": [round(x0, 3), round(y0, 3), round(8 - half, 3)], "to": [round(x1, 3), round(y1, 3), round(8 + half, 3)],
                  "rotation": {"angle": -45, "axis": "z", "origin": [8, 8, 8]}, "faces": faces})
    return {"parent": parent, "textures": {"t": tex, "particle": tex}, "elements": E}
