"""Worn armor for the legendary sets, painted as fake-3D pixel art (256x128 = 4x vanilla).

How it works (see also armor_preview.py to look at the result on a player model):

* Every body part is painted as ONE wrap-around strip (right | front | left | back), so belts, rims and
  plate edges line up around the corners of the box.
* Nothing is drawn colour-by-colour. Each strip has a HEIGHT map (how far a pixel sticks out), a MATERIAL
  map (plate / dark plate / trim / chainmail / cloth) and a GLOW map. Plates are stacked like real armor:
  mail at the bottom, lames above, breast plate above that, trims and gem bezels on top.
* shade() turns the height map into light: normals -> diffuse light from the upper left, specular
  highlight on metal, ambient occlusion in seams, a short cast shadow under every higher plate and a
  dark outline where a plate sits on a lower one. The result is snapped to a small colour ramp per
  material, so it still reads as clean pixel art.
* Glow pixels (gems, runes, lava cracks, stars) use their own ramp and bleed a soft halo onto the metal
  around them. Armor is rendered as alpha *cutout*, so alpha is strictly 0 or 255.
* Each set has its own palette and a motif: void = stars, draco = scales, inferno = lava cracks,
  frost = crystal facets, storm = bolt runes + sparks, bone = ribs + cracked bone.
"""
import zlib

import numpy as np
from PIL import Image
from scipy import ndimage

S = 4                     # texels per vanilla pixel
N = 8                     # colours per material ramp (index 0 = outline)
BOXES = {"head": (0, 0, 8, 8, 8), "body": (16, 16, 8, 12, 4), "arm": (40, 16, 4, 12, 4), "leg": (0, 16, 4, 12, 4)}

PLATE, PLATE2, TRIM, MAIL, DARK = 1, 2, 3, 4, 5

GOLD = ["#241204", "#5a340a", "#a06a14", "#dba52e", "#ffd966", "#fff6c8"]
SILVER = ["#12161e", "#39424f", "#6f7b8c", "#aab6c6", "#dfe7f1", "#ffffff"]
BRONZE = ["#150a04", "#3e2010", "#7a4520", "#b87534", "#e8ab5c", "#ffe2a8"]

SETS = {
    "void": dict(
        plate=["#06030e", "#190b36", "#33186e", "#5a34b4", "#8a66ea", "#c9b4ff"],
        plate2=["#030208", "#0c0922", "#1a1648", "#2e2c7c", "#5356b8", "#a4a8f0"],
        trim=GOLD, under=["#040308", "#120c1e", "#241a38", "#3c2e58", "#5c4c80", "#8878a8"],
        glow=["#5a1090", "#c040ff", "#f0a0ff"], motif="stars", glyph="star"),
    "draco": dict(
        plate=["#120303", "#3c0907", "#74130d", "#b0281a", "#e4573a", "#ffa27a"],
        plate2=["#0c0303", "#28090a", "#4c1110", "#7a1d17", "#a83828", "#d86e50"],
        trim=GOLD, under=["#080404", "#1c0e0c", "#30191a", "#4a2a26", "#6a4038", "#94685a"],
        glow=["#a04000", "#ffa020", "#fff0a0"], motif="scales", glyph="eye"),
    "inferno": dict(
        plate=["#050303", "#170d0c", "#2b1a17", "#443029", "#66483c", "#96705a"],
        plate2=["#040202", "#100808", "#1e1110", "#30201c", "#4a3229", "#6e4e40"],
        trim=BRONZE, under=["#060202", "#1c0806", "#38100a", "#5a1c0e", "#843014", "#b04c1c"],
        glow=["#b01800", "#ff7a00", "#ffe870"], motif="lava", glyph="flame"),
    "frost": dict(
        plate=["#04101e", "#12365c", "#2a6a9c", "#58a4d4", "#9ed6f4", "#eafaff"],
        plate2=["#030a16", "#0c2444", "#1a4a78", "#3478aa", "#68aad6", "#b8e0f8"],
        trim=SILVER, under=["#03060e", "#0a1626", "#142a44", "#244464", "#3c6488", "#6490b4"],
        glow=["#0a80b0", "#50e8ff", "#e0ffff"], motif="facets", glyph="snow"),
    "storm": dict(
        plate=["#05070c", "#161e2e", "#2c3a54", "#4a5e82", "#7890b6", "#b8cce8"],
        plate2=["#030508", "#0e1420", "#1c2638", "#303e58", "#4c5e80", "#7c90b0"],
        trim=SILVER, under=["#030408", "#0c1018", "#181e2c", "#283044", "#3c4860", "#5c6c88"],
        glow=["#a07000", "#ffe030", "#ffffd0"], motif="bolts", glyph="bolt"),
    "bone": dict(
        plate=["#1c160e", "#4e4432", "#8a7c62", "#beb294", "#e6dcc2", "#fffbee"],
        plate2=["#140e08", "#382c1e", "#62523a", "#8c7a5a", "#b4a480", "#dcd0b0"],
        trim=BRONZE, under=["#060203", "#16060a", "#2a0c12", "#42141c", "#5e2028", "#844048"],
        glow=["#900010", "#ff2838", "#ffc0b0"], motif="bone", glyph="skull"),
}

GLYPHS = {
    "star": ["...#...", "...#...", "..###..", "#######", "..###..", "...#...", "...#..."],
    "bolt": ["....##.", "...##..", "..##...", ".#####.", "...##..", "..##...", ".##...."],
    "flame": ["...#...", "..##...", "..###..", ".####.#", ".#####.", "######.", ".####.."],
    "snow": ["#..#..#", ".#.#.#.", "..###..", "#######", "..###..", ".#.#.#.", "#..#..#"],
    "skull": [".#####.", "#######", "#..#..#", "#######", ".##.##.", ".#####.", ".#.#.#."],
    "eye": ["...#...", "..###..", ".##.##.", "##...##", ".##.##.", "..###..", "...#..."],
}


def _hex(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=float)


def _ramp(anchors, n=N):
    a = np.array([_hex(c) for c in anchors])
    t = np.linspace(0, len(a) - 1, n)
    i = np.clip(np.floor(t).astype(int), 0, len(a) - 2)
    f = (t - i)[:, None]
    return a[i] * (1 - f) + a[i + 1] * f


def _seed(*parts):
    return zlib.crc32("/".join(str(p) for p in parts).encode()) & 0x7fffffff


# ------------------------------------------------------------------ canvas: height + material + glow
class Cv:
    def __init__(self, w, h, seed, wrap=True):
        self.w, self.h, self.wrap = w, h, wrap
        yy, xx = np.mgrid[0:h, 0:w]
        self.x, self.y = xx + 0.5, yy + 0.5
        self.xi, self.yi = xx, yy
        self.H = np.zeros((h, w))
        self.Z = np.zeros((h, w))
        self.T = np.zeros((h, w))
        self.E = np.zeros((h, w))
        self.M = np.zeros((h, w), dtype=int)
        self.R = np.zeros((h, w), dtype=int)
        self.nreg = 0
        self.rs = np.random.RandomState(seed)

    def _edt(self, mask):
        if self.wrap:
            m = np.pad(np.tile(mask, (1, 3)), ((1, 1), (0, 0)))
            return ndimage.distance_transform_edt(m)[1:-1, self.w:2 * self.w]
        return ndimage.distance_transform_edt(np.pad(mask, 1))[1:-1, 1:-1]

    def plate(self, mask, mat, z, bevel=2.0, bh=1.0, dome=None):
        """A raised piece: flat top at height z+bh, rounded edge 'bevel' pixels wide."""
        mask = np.asarray(mask, dtype=bool)
        if not mask.any():
            return mask
        self.nreg += 1
        hh = np.full(mask.shape, float(z))
        if bevel > 0:
            p = np.clip(self._edt(mask) / bevel, 0, 1)
            hh = z + (1 - (1 - p) ** 2) * bh
        if dome is not None:
            hh = hh + dome
        self.H[mask] = hh[mask]
        self.M[mask] = mat
        self.R[mask] = self.nreg
        self.Z[mask] = z
        self.E[mask] = 0
        self.T[mask] = 0
        return mask

    def cut(self, mask):
        self.M[mask] = 0
        self.E[mask] = 0
        self.H[mask] = -1.5
        self.R[mask] = 0

    def glow(self, mask, e=1.0):
        mask = np.asarray(mask, dtype=bool) & (self.M > 0)
        self.E[mask] = (e[mask] if isinstance(e, np.ndarray) else e)

    def gem(self, cx, cy, rx, ry, z, diamond=True, bezel=1.8, mat=TRIM):
        """Glowing stone in a metal bezel."""
        dx, dy = np.abs(self.x - cx), np.abs(self.y - cy)
        if diamond:
            d = dx / rx + dy / ry
            db = dx / (rx + bezel) + dy / (ry + bezel * ry / rx)
        else:
            d = np.sqrt((dx / rx) ** 2 + (dy / ry) ** 2)
            db = np.sqrt((dx / (rx + bezel)) ** 2 + (dy / (ry + bezel)) ** 2)
        self.plate(db < 1, mat, z, bevel=1.2, bh=0.8)
        g = d < 1
        self.plate(g, DARK, z - 0.2, bevel=0)
        core = np.clip(1.0 - d * 0.55, 0, 1)
        # facet look: upper-left half brighter
        core = core + 0.12 * ((self.x - cx) + (self.y - cy) < 0)
        self.glow(g, np.clip(core, 0.35, 1))
        return g

    def glyph(self, name, cx, cy, e=0.95, scale=1):
        rows = GLYPHS[name]
        gh, gw = len(rows), len(rows[0])
        for j, row in enumerate(rows):
            for i, ch in enumerate(row):
                if ch != "#":
                    continue
                x0 = int(round(cx - gw * scale / 2)) + i * scale
                y0 = int(round(cy - gh * scale / 2)) + j * scale
                m = (self.xi >= x0) & (self.xi < x0 + scale) & (self.yi >= y0) & (self.yi < y0 + scale)
                self.glow(m, e)
                self.H[m & (self.M > 0)] -= 0.3


def _blur(a, s, wrap):
    return ndimage.gaussian_filter(a, s, mode=("nearest", "wrap") if wrap else "nearest")


def shade(cv, pal):
    """height/material/glow maps -> RGBA pixels (alpha 0/255)."""
    w, h = cv.w, cv.h
    ramps = {PLATE: _ramp(pal["plate"]), PLATE2: _ramp(pal["plate2"]), TRIM: _ramp(pal["trim"]),
             MAIL: _ramp(pal["under"]), DARK: _ramp(pal["under"])}
    gloss = {PLATE: 0.42, PLATE2: 0.30, TRIM: 0.55, MAIL: 0.0, DARK: 0.0}
    base = {PLATE: 0.56, PLATE2: 0.50, TRIM: 0.60, MAIL: 0.40, DARK: 0.26}
    alive = cv.M > 0
    Hs = _blur(cv.H, 0.6, cv.wrap)
    if cv.wrap:
        Hp = np.pad(Hs, ((0, 0), (1, 1)), mode="wrap")
        gx = (Hp[:, 2:] - Hp[:, :-2]) / 2
    else:
        gx = np.gradient(Hs, axis=1)
    gy = np.gradient(Hs, axis=0)
    nz = 1.0 / np.sqrt(gx ** 2 + gy ** 2 + 1)
    nx, ny = -gx * nz, -gy * nz
    L = np.array([-0.40, -0.74, 0.54])
    L /= np.linalg.norm(L)
    ndl = nx * L[0] + ny * L[1] + nz * L[2]
    Hv = L + np.array([0, 0, 1.0])
    Hv /= np.linalg.norm(Hv)
    spec = np.clip(nx * Hv[0] + ny * Hv[1] + nz * Hv[2], 0, 1) ** 28
    spec = np.clip((spec - 0.80) / 0.2, 0, 1)
    ao = np.clip(_blur(cv.H, 2.4, cv.wrap) - cv.H, 0, 2.0)
    sh = np.zeros((h, w))
    for dx, dy, eps in ((1, 1, 0.45), (1, 2, 0.7), (2, 3, 1.0)):
        up = np.roll(np.roll(cv.H, dy, axis=0), dx, axis=1)
        up[:dy, :] = cv.H[:dy, :]
        if not cv.wrap:
            up[:, :dx] = cv.H[:, :dx]
        sh = np.maximum(sh, np.clip(up - cv.H - eps, 0, 1))
    I = np.zeros((h, w))
    for m, b in base.items():
        k = cv.M == m
        I[k] = b + (ndl[k] - L[2]) * (0.95 if m in (PLATE, PLATE2, TRIM) else 0.45) + spec[k] * gloss[m]
    I -= ao * 0.20
    I -= np.minimum(sh * 0.9, 0.22)
    I += cv.T
    I += (0.5 - cv.y / h) * 0.13
    mail = cv.M == MAIL          # chain links: 2px checker with a lit top-left pixel
    I[mail] += (((cv.xi // 2 + cv.yi // 2) % 2) * 0.22 - 0.08)[mail]
    I[mail] += (((cv.xi % 4 == 0) & (cv.yi % 4 == 0)) * 0.10)[mail]
    idx = np.clip(np.rint(I * (N - 1)), 1, N - 1).astype(int)

    # outlines: silhouette, and the lower plate along every edge to a higher plate
    def nb(a, dy, dx, fill):
        r = np.roll(np.roll(a, dy, axis=0), dx, axis=1)
        if dy > 0:
            r[:dy, :] = fill
        if dy < 0:
            r[dy:, :] = fill
        if not cv.wrap:
            if dx > 0:
                r[:, :dx] = fill
            if dx < 0:
                r[:, dx:] = fill
        return r
    outline = np.zeros((h, w), dtype=bool)
    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        rn = nb(cv.R, dy, dx, -1)
        zn = nb(cv.Z, dy, dx, -99.0)
        mn = nb(cv.M, dy, dx, -1)
        diff = (rn != cv.R) & (rn >= 0)
        outline |= diff & (mn > 0) & ((zn > cv.Z + 1e-6) | ((zn == cv.Z) & (dy + dx > 0)))
        outline |= diff & (mn == 0)
    out = np.zeros((h, w, 4))
    for m, r in ramps.items():
        k = cv.M == m
        out[k, :3] = r[idx[k]]
        ko = k & outline
        out[ko, :3] = r[0] * 0.75 + r[1] * 0.25
    # glow: own ramp + halo on the surrounding metal
    g = [_hex(c) for c in pal["glow"]] + [np.array([255.0, 255, 255])]
    E = np.where(alive, cv.E, 0)
    lit = E > 0
    if lit.any():
        e = np.rint(E * 6) / 6
        c = np.zeros((h, w, 3))
        for lo, hi, a, b in ((0.0, 0.45, g[0], g[1]), (0.45, 0.8, g[1], g[2]), (0.8, 1.001, g[2], g[3] * 0.6 + g[2] * 0.4)):
            k = lit & (e >= lo) & (e < hi + 1e-9)
            f = ((e - lo) / (hi - lo))[..., None]
            c = np.where(k[..., None], a * (1 - f) + b * f, c)
        halo = (_blur(E, 1.3, cv.wrap) * 1.1 + _blur(E, 3.0, cv.wrap) * 0.9)[..., None] * g[1] * 0.75
        out[..., :3] = np.where(lit[..., None], c, out[..., :3] + halo * (1.0 - out[..., :3].mean(axis=2, keepdims=True) / 255.0) ** 1.5)
    out[..., 3] = alive * 255
    out[~alive, :3] = 0
    return np.clip(out, 0, 255)


# ------------------------------------------------------------------ set motifs (drawn into the plates)
def _walks(cv, mask, n, length, zig, e, width=1):
    ys, xs = np.nonzero(mask)
    if len(ys) == 0:
        return
    for _ in range(n):
        i = cv.rs.randint(len(ys))
        x, y = int(xs[i]), int(ys[i])
        dx = cv.rs.choice([-1, 1])
        steps = cv.rs.randint(max(length // 2, 2), length + 1)
        for s in range(steps):
            xw = x % cv.w if cv.wrap else x
            if not (0 <= y < cv.h and 0 <= xw < cv.w) or not mask[y, xw]:
                break
            for k in range(width):
                xk = (xw + k) % cv.w
                if mask[y, xk]:
                    cv.E[y, xk] = max(cv.E[y, xk], e if s not in (0, steps - 1) else e * 0.55)
                    cv.H[y, xk] -= 0.5
            if zig:
                if s % zig == zig - 1:
                    dx = -dx
                x += dx
                y += 1
            else:
                r = cv.rs.rand()
                if r < 0.40:
                    y += 1
                elif r < 0.75:
                    x += dx
                    y += 1
                else:
                    x += dx
                if cv.rs.rand() < 0.22:
                    dx = -dx


def motif(cv, pal, amount=1.0):
    kind = pal["motif"]
    pl = (cv.M == PLATE) & (cv.E == 0)
    p2 = (cv.M == PLATE2) & (cv.E == 0)
    both = pl | p2
    area = both.sum()
    rs = cv.rs
    edge = ndimage.binary_erosion(both, iterations=2)       # keep clear of plate rims
    if kind == "stars":
        noise = _blur(rs.rand(cv.h, cv.w), 3.5, cv.wrap)
        noise = (noise - noise.mean()) / (noise.std() + 1e-6)
        cv.T[both] += (noise * 0.07)[both]
        for _ in range(int(area * 0.012 * amount)):
            y, x = rs.randint(cv.h), rs.randint(cv.w)
            if not edge[y, x]:
                continue
            if rs.rand() < 0.3 and 0 < y < cv.h - 1 and 0 < x < cv.w - 1 and edge[y - 1:y + 2, x - 1:x + 2].all():
                cv.E[y, x] = 1.0
                for yy, xx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
                    cv.E[yy, xx] = max(cv.E[yy, xx], 0.4)
            else:
                cv.E[y, x] = 0.62
    elif kind == "scales":
        cw, ch = 8, 5
        row = cv.yi // ch
        xo = cv.xi + (row % 2) * (cw // 2)
        dx = np.abs((xo % cw) - (cw - 1) / 2)
        dy = cv.yi % ch
        t = np.zeros((cv.h, cv.w))
        t += np.where(dy == 0, 0.10, 0)                      # lit top of each scale
        t += np.where((dy <= 1) & (dx < 1.6), 0.08, 0)
        arc = dy + (dx / 3.5) ** 2 * 2.2                     # U-shaped lower edge
        t -= np.where(arc >= ch - 0.6, 0.22, 0)
        t -= np.where((arc >= ch - 1.8) & (arc < ch - 0.6), 0.07, 0)
        cv.T[pl] += t[pl]
        cv.H[pl] += (-(dy / ch) * 0.5)[pl]
    elif kind == "lava":
        noise = rs.rand(cv.h, cv.w)
        cv.T[both] += ((noise > 0.82) * -0.07 + (noise < 0.12) * 0.06)[both]
        _walks(cv, edge, int(area / 120 * amount) + 1, 14, 0, 0.78)
        _walks(cv, edge, int(area / 420 * amount), 7, 0, 1.0)
    elif kind == "facets":
        n = max(int(area / 55), 3)
        py, px = rs.rand(n) * cv.h, rs.rand(n) * cv.w
        best = np.full((cv.h, cv.w), 1e9)
        cid = np.zeros((cv.h, cv.w), dtype=int)
        for i in range(n):
            ddx = np.abs(cv.x - px[i])
            if cv.wrap:
                ddx = np.minimum(ddx, cv.w - ddx)
            d = ddx * 1.25 + np.abs(cv.y - py[i]) * 0.8 + np.abs(ddx - np.abs(cv.y - py[i])) * 0.25
            k = d < best
            best[k] = d[k]
            cid[k] = i
        tilt = (rs.rand(n) - 0.5) * 0.26
        cv.T[both] += tilt[cid][both]
        ridge = (cid != np.roll(cid, 1, axis=1)) | (cid != np.roll(cid, 1, axis=0))
        cv.T[both & ridge] += 0.13
        for _ in range(int(area * 0.006 * amount)):
            y, x = rs.randint(cv.h), rs.randint(cv.w)
            if edge[y, x]:
                cv.E[y, x] = 0.9
    elif kind == "bolts":
        # clean steel: the lightning lives in the bolt runes; a few sparks + rivets + brushed metal here
        for _ in range(int(area * 0.004 * amount)):
            y, x = rs.randint(cv.h), rs.randint(cv.w)
            if edge[y, x] and pl[y, x]:
                cv.E[y, x] = 0.8
        riv = (cv.M == TRIM) & (cv.xi % 6 == 2)
        riv &= ndimage.binary_erosion(cv.M == TRIM, structure=np.ones((3, 1)))
        cv.T[riv] -= 0.26
        brushed = ((cv.yi * 3 + cv.xi // 5) % 4 == 0) * 0.045
        cv.T[both] += brushed[both]
    elif kind == "bone":
        noise = rs.rand(cv.h, cv.w)
        cv.T[both] += ((noise > 0.9) * -0.09)[both]
        ys, xs = np.nonzero(edge)
        for _ in range(int(area / 150 * amount)):       # hairline cracks
            if len(ys) == 0:
                break
            i = rs.randint(len(ys))
            x, y = int(xs[i]), int(ys[i])
            for s in range(rs.randint(3, 7)):
                if 0 <= y < cv.h and edge[y, x % cv.w]:
                    cv.T[y, x % cv.w] -= 0.24
                y += 1
                x += rs.choice([-1, 0, 0, 1])


# ------------------------------------------------------------------ part layouts (4x pixels)
def _body_fields(cv):
    x, y = cv.x, cv.y
    front = (x >= 16) & (x < 48)
    back = x >= 64
    U = np.where(front, np.abs(x - 32), np.where(back, np.abs(x - 80), 16.0))   # distance from the centre line
    return x, y, front, back, U


def chest(pal, seed):
    cv = Cv(96, 48, seed)
    x, y, front, back, U = _body_fields(cv)
    ribs = pal["motif"] == "bone"
    halfA = (x >= 32) & (x < 80)
    cv.plate(np.ones_like(front), DARK if ribs else MAIL, 0, bevel=0)
    sil = 47.6 - np.clip(U - 3, 0, None) * 0.86                   # pointed lower edge
    # faulds + hem
    cv.plate((y >= 35 - 0.12 * U), PLATE2, 0.8, bh=0.8)
    cv.plate((y >= sil - 2.6), TRIM, 1.3, bevel=1.2, bh=0.7)
    if ribs:
        for k in range(4):
            y0 = 22.5 + k * 4.4 + 0.10 * U
            m = (y >= y0) & (y < y0 + 3.2) & (U >= 1.0)
            for half in (halfA, ~halfA):
                cv.plate(m & half, PLATE, 1.5 - k * 0.1, bevel=1.6, bh=1.3)
    else:
        for k, (a, b, z) in enumerate(((29.5, 38.0, 1.2), (22.0, 31.5, 1.7))):
            cv.plate((y >= a - 0.10 * U) & (y < b - 0.14 * U), PLATE2, z, bevel=2.2, bh=1.0)
    # breast / shoulder-blade plates
    dome = 1.3 * np.exp(-(((U - 8.5) / 7.0) ** 2 + ((y - 13) / 9.0) ** 2))
    pec_low = 25.5 - 0.30 * U
    pec = (y >= 1.5) & (y < pec_low)
    for half in (halfA, ~halfA):
        cv.plate(pec & half, PLATE, 2.3, bevel=2.6, bh=1.2, dome=dome)
    for half in (halfA, ~halfA):
        cv.plate(pec & half & (y >= pec_low - 2.4), TRIM, 2.8, bevel=1.2, bh=0.7)
    # centre ridge (front) / segmented spine (back)
    cv.plate(front & (U < 2.0) & (y >= 5) & (y < 27), TRIM, 3.2, bevel=1.2, bh=0.8)
    for k in range(7):
        y0 = 2.5 + k * 5.6
        wv = 3.2 - 0.18 * k
        cv.plate(back & (U < wv) & (y >= y0) & (y < y0 + 4.6), PLATE2 if not ribs else PLATE, 3.2, bevel=1.5, bh=1.2)
    # collar
    neck = (U / 8.0) ** 2 + (y / 6.5) ** 2
    cv.plate(front & (neck < 2.1), TRIM, 3.4, bevel=1.4, bh=0.9)
    cv.plate(~front & (y < 3.0 + (2.5 * np.exp(-(U / 7.0) ** 2)) * back), TRIM, 3.4, bevel=1.3, bh=0.9)
    # core gem
    cv.gem(32, 15.5, 3.6, 5.2, 3.6)
    cv.glyph(pal["glyph"], 80, 15, e=0.8)                         # sigil between the shoulder blades
    motif(cv, pal)
    cv.cut(front & (neck < 1.0))
    cv.cut(y >= sil)
    return cv


def belt(pal, seed):
    cv = Cv(96, 48, seed)
    x, y, front, back, U = _body_fields(cv)
    cv.plate(np.ones_like(front), MAIL, 0, bevel=0)
    for k in range(12):                                           # hip tassets
        m = (x >= k * 8) & (x < k * 8 + 8) & (y >= 39)
        cv.plate(m, PLATE if k % 2 == 0 else PLATE2, 0.9, bevel=1.6, bh=0.9)
    cv.plate((y >= 28) & (y < 34), PLATE2, 0.8, bevel=1.5, bh=0.7)
    cv.plate((y >= 32.5) & (y < 40.5), DARK, 1.4, bevel=1.0, bh=0.5)      # leather belt
    cv.plate((y >= 32.5) & (y < 34.2), TRIM, 1.9, bevel=0.8, bh=0.5)
    cv.plate((y >= 38.8) & (y < 40.5), TRIM, 1.9, bevel=0.8, bh=0.5)
    studs = (y >= 35.5) & (y < 37.5) & ((cv.xi + 2) % 8 < 2) & (U > 8)
    cv.plate(studs, TRIM, 2.0, bevel=0)
    cv.plate(front & (U < 6.5) & (y >= 30.5) & (y < 42.5), TRIM, 2.4, bevel=1.5, bh=1.0)     # buckle
    cv.gem(32, 36.5, 3.2, 4.2, 2.9, bezel=0.01)
    cv.plate(back & (U < 3.5) & (y >= 31.5) & (y < 41.5), TRIM, 2.2, bevel=1.4, bh=0.8)
    motif(cv, pal, 0.8)
    cv.cut(y < 28)
    return cv


def arm(pal, seed):
    cv = Cv(64, 48, seed)
    x, y = cv.x, cv.y
    A = np.minimum(np.abs(x - 8), 64 - np.abs(x - 8))             # distance around the arm from the outer side
    cv.plate(np.ones_like(A, dtype=bool), MAIL, 0, bevel=0)
    # gauntlet + bracer (flared: longer on the outside)
    cv.plate(y >= 43.5, DARK, 0.5, bevel=0)
    cv.plate((y >= 44.5) & (A < 22), PLATE2, 1.0, bevel=1.4, bh=0.8)                 # knuckle plate
    btop = 26.0 + 4.0 * (A / 32.0) ** 1.3
    br = (y >= btop) & (y < 44)
    cv.plate(br, PLATE, 1.2, bevel=2.0, bh=0.9, dome=0.7 * np.exp(-(A / 9.0) ** 2))
    cv.plate(br & (A > 29.5), DARK, 0.4, bevel=0)                                    # lacing seam on the inside
    cv.plate(br & (y < btop + 2.2) & (A <= 29.5), TRIM, 1.9, bevel=1.0, bh=0.7)
    cv.plate((y >= 41.8) & (y < 44) & (A <= 29.5), PLATE2, 1.8, bevel=1.0, bh=0.7)
    for k in (-1, 1):                                                                # two fins on the forearm
        d = np.abs(np.minimum(np.abs(x - (8 + 16 * k)), 64 - np.abs(x - (8 + 16 * k))))
        cv.plate((d < 1.6) & (y >= btop + 3.4) & (y < 41), PLATE2, 1.9, bevel=1.0, bh=0.8)
    cv.plate((A < 6.2) & (np.abs(y - 35.5) < 5.4 - 0.35 * A), PLATE2, 1.9, bevel=1.4, bh=0.8)
    cv.glyph(pal["glyph"], 8, 36, e=0.95)
    # elbow cop on the outside
    cv.plate(((A / 8.5) ** 2 + ((y - 23.0) / 3.8) ** 2) < 1, PLATE2, 1.5, bevel=1.5, bh=1.0)
    # pauldron: three overlapping shells that hang lowest on the outside, lowest shell first
    k = (A / 16.0) ** 1.5
    low = 21.5 - 4.6 * k
    cv.plate(y < low, PLATE2, 1.7, bevel=2.2, bh=1.0)
    cv.plate((y < low) & (y >= low - 2.2), TRIM, 2.2, bevel=1.1, bh=0.7)
    mid = 15.0 - 3.8 * k
    cv.plate(y < mid, PLATE, 2.5, bevel=2.4, bh=1.1, dome=0.9 * np.exp(-(A / 10.0) ** 2))
    top = 8.0 - 2.4 * k
    cv.plate(y < top, PLATE2, 3.4, bevel=2.2, bh=1.1)
    cv.plate((y < top) & (y >= top - 1.8), TRIM, 3.9, bevel=1.0, bh=0.6)
    cv.gem(8, 11.6, 2.4, 2.4, 3.4, diamond=False, bezel=1.4)
    motif(cv, pal)
    return cv


def arm_top(pal, seed):
    cv = Cv(16, 16, seed, wrap=False)
    x, y = cv.x, cv.y
    r = np.maximum(np.abs(x - 8), np.abs(y - 8))
    cv.plate(np.ones_like(x, dtype=bool), TRIM, 3.9, bevel=1.0, bh=0.6)
    cv.plate(r < 6, PLATE, 3.4, bevel=2.5, bh=1.4)
    cv.plate(np.hypot(x - 8, y - 8) < 2.6, TRIM, 4.6, bevel=1.5, bh=1.2)              # boss / spike base
    motif(cv, pal, 0.5)
    return cv


def flat(pal, seed, mat, w=16, h=16, tread=False):
    cv = Cv(w, h, seed, wrap=False)
    cv.plate(np.ones((h, w), dtype=bool), mat, 0.5, bevel=1.5, bh=0.6)
    if tread:
        cv.T -= ((cv.yi % 4) < 1) * 0.2
    return cv


def boots(pal, seed):
    cv = Cv(64, 48, seed)
    x, y = cv.x, cv.y
    F = np.minimum(np.abs(x - 24), 64 - np.abs(x - 24))           # distance around the leg from the shin
    ytop = np.full_like(F, 25.0)                                  # flat cuff (clean edge over the leggings)
    cv.plate(np.ones_like(F, dtype=bool), PLATE2, 0.6, bevel=0)
    cv.plate((F > 29) & (y < 39), DARK, 0.2, bevel=0)             # seam at the back of the calf
    shin = (F < 12.5) & (y < 38)
    cv.plate(shin, PLATE, 1.5, bevel=2.2, bh=1.0, dome=0.9 * np.exp(-(F / 3.2) ** 2))
    cv.plate((y < ytop + 4.6), PLATE2, 2.0, bevel=1.4, bh=0.8)    # cuff
    cv.plate((y < ytop + 2.2), TRIM, 2.5, bevel=1.0, bh=0.6)
    cv.plate((y >= 36.4) & (y < 39.4), TRIM, 1.9, bevel=1.0, bh=0.6)                 # ankle strap
    cv.plate(y >= 39.4, PLATE, 1.3, bevel=1.8, bh=0.9)
    toe = (F < 9.5) & (y >= 40.6)
    cv.plate(toe, PLATE2, 2.0, bevel=2.0, bh=1.1, dome=0.6 * np.exp(-(F / 5.0) ** 2))
    cv.plate(toe & (y < 42.6), TRIM, 2.6, bevel=0.9, bh=0.5)
    heel = (F > 23) & (y >= 40.6)
    cv.plate(heel, PLATE2, 1.9, bevel=1.6, bh=0.9)
    cv.plate(y >= 46, DARK, 1.0, bevel=0)                         # sole
    cv.gem(24, 43.6, 1.8, 1.8, 2.6, diamond=False, bezel=0.01)      # toe stud
    cv.glyph(pal["glyph"], 8, 32, e=0.8)                          # outer ankle rune
    motif(cv, pal, 0.8)
    cv.cut(y < ytop)
    return cv


def legs(pal, seed):
    cv = Cv(64, 48, seed)
    x, y = cv.x, cv.y
    F = np.minimum(np.abs(x - 24), 64 - np.abs(x - 24))
    O = np.minimum(np.abs(x - 13), 64 - np.abs(x - 13))           # distance from the outer-front edge
    cv.plate(np.ones_like(F, dtype=bool), MAIL, 0, bevel=0)
    # greave under the knee
    cv.plate((F < 13.5) & (y >= 23), PLATE2, 1.0, bevel=2.0, bh=0.9, dome=0.7 * np.exp(-(F / 3.5) ** 2))
    cv.plate((F >= 13.5) & (y >= 44), PLATE2, 0.8, bevel=1.2, bh=0.6)
    # cuisse: two lames on the thigh
    c_low = 17.5 - 0.10 * O
    cv.plate((O < 19) & (y < c_low), PLATE2, 1.3, bevel=2.0, bh=0.9)
    cv.plate((O < 19) & (y < c_low) & (y >= c_low - 2.3), TRIM, 1.8, bevel=1.0, bh=0.6)
    cv.plate((O < 17.5) & (y < 10.5 - 0.10 * O), PLATE, 1.9, bevel=2.2, bh=1.0, dome=0.6 * np.exp(-((x - 24) / 6.0) ** 2))
    cv.glyph(pal["glyph"], 8, 6, e=0.9)
    # poleyn (knee cop) with side wings
    cv.plate(((F / 11.5) ** 2 + ((y - 19.5) / 3.0) ** 2) < 1, PLATE2, 1.9, bevel=1.4, bh=0.8)
    knee = ((F / 8.2) ** 2 + ((y - 19.0) / 6.0) ** 2) < 1
    cv.plate(knee, PLATE2, 2.5, bevel=1.3, bh=0.8)
    cv.plate(((F / 5.4) ** 2 + ((y - 19.0) / 3.4) ** 2) < 1, PLATE, 2.7, bevel=2.0, bh=1.3)
    cv.gem(24, 19, 1.5, 2.6, 3.6, bezel=1.0)
    motif(cv, pal, 0.8)
    return cv


# ------------------------------------------------------------------ assemble 64x32-layout texture
def _paste(out, cv, pal, px, py):
    img = shade(cv, pal)
    out[py:py + cv.h, px:px + cv.w] = img


def worn(set_name, layer, vanilla_path=None):
    """256x128 equipment layer. 'humanoid' = chestplate + boots (helmet is a 3D item model),
    'humanoid_leggings' = belt + legs. vanilla_path is unused (kept for the old call signature)."""
    pal = SETS[set_name]
    out = np.zeros((32 * S, 64 * S, 4))
    sd = lambda part: _seed(set_name, layer, part)
    for part, (ox, oy, w, h, d) in BOXES.items():
        if part == "head":
            continue
        sx, sy = ox * S, (oy + d) * S                 # wrap strip: right | front | left | back
        tx, ty = (ox + d) * S, oy * S                 # top face
        bx = (ox + d + w) * S                         # bottom face
        if layer == "humanoid":
            if part == "body":
                _paste(out, chest(pal, sd(part)), pal, sx, sy)
            elif part == "arm":
                _paste(out, arm(pal, sd(part)), pal, sx, sy)
                _paste(out, arm_top(pal, sd("armtop")), pal, tx, ty)
                _paste(out, flat(pal, sd("hand"), DARK), pal, bx, ty)
            else:
                _paste(out, boots(pal, sd(part)), pal, sx, sy)
                _paste(out, flat(pal, sd("sole"), DARK, tread=True), pal, bx, ty)
        else:
            if part == "body":
                _paste(out, belt(pal, sd(part)), pal, sx, sy)
            elif part == "leg":
                _paste(out, legs(pal, sd(part)), pal, sx, sy)
                _paste(out, flat(pal, sd("legtop"), PLATE2), pal, tx, ty)
    return Image.fromarray(out.astype(np.uint8), "RGBA")


# ------------------------------------------------------------------ 3D helmet texture (same UV atlas as armor.helmet_model)
def helmet_texture(set_name):
    """64x64 atlas: front 0,0-32,32 | side 32,0-64,32 | gold 0,32 | accent 16,32 | metal 32,32 | dark 48,32 |
    horn 0,48-32,64 (16px tiles). The shell box uses 'front' on the face and 'side' everywhere else."""
    pal = SETS[set_name]
    out = np.zeros((64, 64, 4))
    # front: brow plate, glowing eye slit (the model adds a glow bar + nose guard), face plate with vents
    cv = Cv(32, 32, _seed(set_name, "helm_front"), wrap=False)
    x, y = cv.x, cv.y
    u = np.abs(x - 16)
    cv.plate(np.ones((32, 32), dtype=bool), PLATE2, 0.6, bevel=1.5, bh=0.6)
    brow_low = 14.0 - 0.10 * u + 2.5 * np.exp(-(u / 3.0) ** 2)
    cv.plate(y < brow_low, PLATE, 2.0, bevel=2.4, bh=1.2, dome=1.0 * np.exp(-((u / 12.0) ** 2 + ((y - 6) / 7.0) ** 2)))
    cv.plate((y < brow_low) & (y >= brow_low - 2.6), TRIM, 2.6, bevel=1.1, bh=0.7)
    cv.plate((u < 1.8) & (y < brow_low - 2.6), TRIM, 2.8, bevel=1.0, bh=0.7)          # crest line
    slit = (y >= brow_low) & (y < 20.5) & (u > 2.2) & (u < 13.5)
    cv.plate(slit, DARK, 0.0, bevel=0)
    eye = slit & (y >= 16.3) & (y < 19.0) & (u > 3.6) & (u < 11.5 - (y - 16.3) * 0.9)
    cv.glow(eye, np.clip(1.05 - (u - 3.6) / 12.0, 0.5, 1.0))
    for k in (4.5, 8.5, 12.5):                                                          # breath vents
        cv.plate((np.abs(u - k) < 0.9) & (y >= 23) & (y < 29), DARK, 0.0, bevel=0)
    cv.plate((y >= 20.5) & (y < 22.3) & (u > 2.2), TRIM, 1.6, bevel=0.8, bh=0.5)
    cv.gem(16, 6.0, 2.6, 3.6, 3.0, bezel=1.4)
    motif(cv, pal, 0.7)
    out[0:32, 0:32] = shade(cv, pal)
    # side (also back / top): dome plate, trim band continuing the brow line, neck lames
    cv = Cv(32, 32, _seed(set_name, "helm_side"), wrap=False)
    x, y = cv.x, cv.y
    cv.plate(np.ones((32, 32), dtype=bool), PLATE2, 0.6, bevel=1.5, bh=0.6)
    for k in range(3):
        cv.plate((y >= 15 + k * 5.6) & (y < 21.4 + k * 5.6), PLATE2, 1.4 - k * 0.3, bevel=1.8, bh=0.9)
    cv.plate(y < 13.5, PLATE, 2.0, bevel=2.4, bh=1.2, dome=0.9 * np.exp(-(((x - 16) / 12.0) ** 2 + ((y - 6) / 7.0) ** 2)))
    cv.plate((y >= 11.2) & (y < 13.8), TRIM, 2.6, bevel=1.1, bh=0.7)
    cv.glyph(pal["glyph"], 16, 6, e=0.85)
    motif(cv, pal, 0.7)
    out[0:32, 32:64] = shade(cv, pal)
    # flat swatches for the small boxes (rims, horns, crests)
    yy, xx = np.mgrid[0:16, 0:16]
    g = 0.78 - yy / 15 * 0.42 - xx / 15 * 0.12
    g = g + ((xx == 0) | (yy == 0)) * 0.10 - ((xx == 15) | (yy == 15)) * 0.14

    def sw(anchors, lo=0.0):
        r = _ramp(anchors)
        return r[np.clip(np.rint((g + lo) * (N - 1)), 1, N - 1).astype(int)]
    out[32:48, 0:16, :3] = sw(pal["trim"])
    glow = [_hex(c) for c in pal["glow"]]
    c = np.hypot(xx - 7.5, yy - 7.5) / 10.5
    acc = np.where((c < 0.35)[..., None], glow[2] * 0.6 + 100, np.where((c < 0.75)[..., None], glow[2], glow[1]))
    out[32:48, 16:32, :3] = np.clip(acc, 0, 255)
    out[32:48, 32:48, :3] = sw(pal["plate"])
    out[32:48, 48:64, :3] = sw(pal["plate2"], -0.22)
    hy, hx = np.mgrid[0:16, 0:32]
    horn = ["#1a120a", "#4a3a26", "#8c7652", "#c8b48c", "#efe2c0", "#fffaf0"] if set_name in ("bone", "draco") else pal["plate"]
    hr = _ramp(horn)
    hi = 0.80 - hy / 15 * 0.45 - (hx % 8 == 7) * 0.22 + (hx % 8 == 0) * 0.08
    out[48:64, 0:32, :3] = hr[np.clip(np.rint(hi * (N - 1)), 1, N - 1).astype(int)]
    out[32:48, :, 3] = 255
    out[48:64, 0:32, 3] = 255
    return Image.fromarray(np.clip(out, 0, 255).astype(np.uint8), "RGBA")
