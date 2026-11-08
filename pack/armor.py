"""Custom legendary armor: hand-designed icons (lit like the weapons) and HD worn textures.

Icons: each piece is drawn as vector shapes on a 192x192 canvas (helmet with set-specific horns, wings,
spikes or crest; chestplate with pauldrons and a glowing core; leggings with belt and knee guards;
boots with toe caps) and rendered through hq.lit_map.

Worn textures: 256x128 (4x the vanilla 64x32 layout). The covered pixels follow vanilla netherite so
the armor fits the model, but every face is repainted: lit metal plates with bevels, gold trims,
rivets, a set pattern (scales, ice, lava cracks, lightning, void veins, bone cracks) and glowing
details (visor, chest core, rune strips).
"""
import math
import os
import random

import numpy as np
from PIL import Image, ImageDraw

import hq

N = hq.R * hq.SS     # 192
METAL, ACCENT, GUARD, UNDER = hq.METAL, hq.ACCENT, hq.GUARD, hq.HANDLE


class Canvas:
    def __init__(self):
        self.g = np.zeros((N, N), dtype=np.int8)
        self.wrap = np.zeros((N, N), dtype=bool)

    def _mask(self, fn):
        im = Image.new("L", (N, N), 0)
        fn(ImageDraw.Draw(im))
        return np.array(im) > 127

    def poly(self, pts, mat, mirror=False):
        self.paint(lambda d: d.polygon(pts, fill=255), mat)
        if mirror:
            self.paint(lambda d: d.polygon([(N - 1 - x, y) for x, y in pts], fill=255), mat)

    def ell(self, box, mat, mirror=False):
        self.paint(lambda d: d.ellipse(box, fill=255), mat)
        if mirror:
            x0, y0, x1, y1 = box
            self.paint(lambda d: d.ellipse((N - 1 - x1, y0, N - 1 - x0, y1), fill=255), mat)

    def rect(self, box, mat, mirror=False):
        self.paint(lambda d: d.rectangle(box, fill=255), mat)
        if mirror:
            x0, y0, x1, y1 = box
            self.paint(lambda d: d.rectangle((N - 1 - x1, y0, N - 1 - x0, y1), fill=255), mat)

    def line(self, pts, w, mat=None, groove=False, mirror=False):
        def f(d, pp):
            d.line(pp, fill=255, width=w, joint="curve")
        m = self._mask(lambda d: f(d, pts))
        if mirror:
            m |= self._mask(lambda d: f(d, [(N - 1 - x, y) for x, y in pts]))
        if groove:
            self.wrap |= m & (self.g > 0)
        elif mat is not None:
            self.g[m] = mat

    def paint(self, fn, mat):
        m = self._mask(fn)
        if mat == 0:
            self.g[m] = 0
            self.wrap[m] = False
        else:
            self.g[m] = mat


def arc_pts(cx, cy, r0, r1, a0, a1, steps=24):
    """Crescent/horn polygon: outer arc then inner arc back, radii taper to the tip."""
    out, inn = [], []
    for i in range(steps + 1):
        t = i / steps
        a = math.radians(a0 + (a1 - a0) * t)
        w = (r1 - r0) * (1 - t) ** 0.8
        out.append((cx + math.cos(a) * (r0 + w), cy + math.sin(a) * (r0 + w)))
        inn.append((cx + math.cos(a) * r0, cy + math.sin(a) * r0))
    return out + inn[::-1]


# ------------------------------------------------------------------ icons
def helmet(c, s):
    c.ell((34, 26, 158, 150), METAL)
    c.rect((0, 112, N, N), 0)                                        # cut the dome
    c.poly([(34, 86), (70, 92), (72, 164), (40, 150)], METAL, mirror=True)   # cheek guards
    c.rect((70, 104, 121, 168), UNDER)                               # face opening
    c.rect((60, 96, 131, 108), ACCENT)                               # visor slit
    c.poly([(88, 104), (103, 104), (100, 146), (91, 146)], METAL)     # nose guard
    c.rect((34, 108, 157, 118), GUARD)                               # brow trim
    c.rect((89, 26, 102, 108), GUARD)                                # crest ridge
    c.line([(52, 60), (60, 104)], 3, groove=True, mirror=True)
    if s == "draco":
        c.poly(arc_pts(40, 64, 26, 44, 200, 285), GUARD)
        c.poly(arc_pts(151, 64, 26, 44, -20, -105), GUARD)
    elif s == "bone":
        c.poly(arc_pts(58, 58, 18, 36, 160, 300), METAL)
        c.poly(arc_pts(133, 58, 18, 36, 20, -120), METAL)
    elif s == "frost":
        for x, h in ((64, 22), (96, 6), (128, 22), (44, 44), (148, 44)):
            c.poly([(x - 9, 60), (x + 9, 60), (x, h)], ACCENT)
    elif s == "inferno":
        c.poly([(70, 40), (80, 4), (92, 30), (100, 0), (110, 30), (122, 8), (124, 44)], ACCENT)
    elif s == "storm":
        for side in (1, -1):
            x = 30 if side == 1 else 161
            for k in range(3):
                c.poly([(x, 60 + k * 14), (x - side * 30, 30 + k * 18), (x - side * 18, 74 + k * 12)], GUARD)
    elif s == "void":
        c.poly([(84, 30), (108, 30), (96, -4)], ACCENT)
        c.ell((80, 120, 92, 130), ACCENT); c.ell((99, 120, 111, 130), ACCENT)


def chestplate(c, s):
    c.poly([(52, 30), (140, 30), (152, 66), (138, 172), (54, 172), (40, 66)], METAL)
    c.ell((10, 22, 74, 88), METAL, mirror=True)                      # pauldrons
    c.ell((16, 28, 68, 82), GUARD, mirror=True)
    c.ell((24, 36, 60, 74), METAL, mirror=True)
    c.ell((72, 10, 120, 50), 0)                                      # neck
    c.line([(66, 36), (96, 52), (126, 36)], 7, GUARD)                # collar
    c.rect((54, 150, 138, 164), GUARD)                               # belt
    for y in (112, 130):
        c.line([(60, y), (132, y)], 3, groove=True)
    c.line([(96, 56), (96, 150)], 3, groove=True)
    c.poly([(96, 62), (122, 88), (96, 114), (70, 88)], GUARD)        # core frame
    c.poly([(96, 70), (114, 88), (96, 106), (78, 88)], ACCENT)       # glowing core
    for x in (62, 130):
        c.ell((x - 4, 140, x + 4, 148), GUARD)
    if s == "draco":
        for k in range(4):
            c.poly([(20 + k * 12, 30), (8 + k * 12, 6), (30 + k * 12, 26)], GUARD)
            c.poly([(171 - k * 12, 30), (183 - k * 12, 6), (161 - k * 12, 26)], GUARD)
    elif s == "frost":
        for x in (22, 42, 150, 170):
            c.poly([(x - 7, 34), (x + 7, 34), (x, 6)], ACCENT)
    elif s == "storm":
        c.poly([(60, 120), (76, 120), (68, 136), (82, 136), (58, 166), (66, 142), (54, 142)], ACCENT)
    elif s == "inferno":
        c.line([(64, 70), (72, 100), (62, 126)], 4, ACCENT)
        c.line([(128, 70), (120, 100), (130, 126)], 4, ACCENT)
    elif s == "void":
        c.line([(40, 66), (70, 88)], 4, ACCENT, mirror=True)
    elif s == "bone":
        for y in (118, 134):
            c.line([(64, y), (128, y)], 7, METAL)


def leggings(c, s):
    c.poly([(42, 30), (150, 30), (150, 90), (104, 96), (100, 178), (128, 178), (128, 100)], METAL)
    c.poly([(42, 30), (150, 30), (148, 96), (128, 104), (126, 178), (100, 178), (96, 104), (92, 178), (66, 178), (64, 104),
            (44, 96)], METAL)
    c.rect((40, 26, 151, 50), GUARD)                                 # belt
    c.rect((86, 28, 105, 48), ACCENT)                                # buckle
    c.ell((52, 110, 90, 146), GUARD); c.ell((101, 110, 139, 146), GUARD)   # knee guards
    c.ell((60, 118, 82, 138), METAL); c.ell((109, 118, 131, 138), METAL)
    c.ell((67, 124, 75, 132), ACCENT); c.ell((116, 124, 124, 132), ACCENT)
    c.line([(58, 60), (62, 100)], 3, groove=True, mirror=True)
    c.line([(80, 54), (82, 100)], 3, groove=True, mirror=True)
    if s in ("storm", "inferno", "frost", "void"):
        c.line([(70, 152), (78, 172)], 4, ACCENT, mirror=True)


def boots(c, s):
    for ox in (0, 98):
        c.rect((ox + 20, 50, ox + 74, 132), METAL)
        c.poly([(ox + 20, 128), (ox + 74, 128), (ox + 94, 146), (ox + 94, 172), (ox + 20, 172)], METAL)
        c.rect((ox + 16, 46, ox + 78, 62), GUARD)                    # cuff
        c.poly([(ox + 66, 140), (ox + 94, 146), (ox + 94, 172), (ox + 66, 172)], GUARD)   # toe cap
        c.rect((ox + 20, 164, ox + 94, 174), UNDER)                  # sole
        c.poly([(ox + 26, 66), (ox + 68, 66), (ox + 64, 118), (ox + 47, 128), (ox + 30, 118)], GUARD)   # shin plate
        c.poly([(ox + 32, 72), (ox + 62, 72), (ox + 59, 114), (ox + 47, 121), (ox + 35, 114)], METAL)
        c.ell((ox + 41, 84, ox + 53, 96), ACCENT)                    # gem
        c.rect((ox + 20, 134, ox + 64, 140), ACCENT)                 # strap
        c.line([(ox + 30, 146), (ox + 30, 166)], 3, groove=True)
        c.line([(ox + 46, 146), (ox + 46, 166)], 3, groove=True)
        if s == "storm":
            c.poly([(ox + 18, 66), (ox - 4, 50), (ox + 14, 82)], GUARD)
        if s == "frost":
            c.poly([(ox + 30, 46), (ox + 40, 46), (ox + 35, 26)], ACCENT)
            c.poly([(ox + 54, 46), (ox + 64, 46), (ox + 59, 26)], ACCENT)


PIECES = {"helmet": helmet, "chestplate": chestplate, "leggings": leggings, "boots": boots}


def icon(set_name, piece, st):
    st = pal(set_name, st)
    c = Canvas()
    PIECES[piece](c, set_name)
    s0 = st["stops"]
    trim = [s0[0], s0[0], s0[1], s0[2], s0[3]]          # dark metal trims instead of gold
    return hq.pixel_art(hq.lit_map(c.g, c.wrap, st, metal_bevel=7.0, guard_cols=trim, out_res=hq.PIX, want_shade=True), st,
                        guard_cols=trim, seed=len(set_name + piece), n_frames=12)


# ------------------------------------------------------------------ worn (humanoid) textures
from scipy import ndimage

K = 8  # scale of the 64x32 layout -> 512x256


def faces_of(ox, oy, w, h, d):
    """Minecraft box UV: returns dict face -> (x0, y0, x1, y1) in 64x32 pixels."""
    return {"top": (ox + d, oy, ox + d + w, oy + d), "bottom": (ox + d + w, oy, ox + d + 2 * w, oy + d),
            "right": (ox, oy + d, ox + d, oy + d + h), "front": (ox + d, oy + d, ox + d + w, oy + d + h),
            "left": (ox + d + w, oy + d, ox + 2 * d + w, oy + d + h), "back": (ox + 2 * d + w, oy + d, ox + 2 * d + 2 * w, oy + d + h)}


BOXES = {"head": (0, 0, 8, 8, 8), "body": (16, 16, 8, 12, 4), "arm": (40, 16, 4, 12, 4), "leg": (0, 16, 4, 12, 4)}
MM, MG, MA, MD = 0, 1, 2, 3   # metal, gold, accent (glow), dark


def rgb(h):
    return hq.rgb(h)


def bump(h, mask, height, bevel):
    """Raise `mask` by `height` with a soft bevel of `bevel` px."""
    if not mask.any():
        return
    d = ndimage.distance_transform_edt(mask)
    h += np.minimum(d / bevel, 1.0) * height * mask


# Dark, sleek fantasy palettes for the armor (separate from the weapon palettes): dark metal + neon glow.
PAL = {
    "draco":   dict(stops=["#0c0304", "#360b09", "#741a10", "#c33a1c"], accent="#ff8a1e", spark="#ffe0a0"),
    "inferno": dict(stops=["#0a0505", "#24100a", "#43190d", "#7f3214"], accent="#ff5a00", spark="#ffd080"),
    "frost":   dict(stops=["#040b16", "#0f2c48", "#27628c", "#8ccbec"], accent="#5ff6ff", spark="#ffffff"),
    "storm":   dict(stops=["#06080e", "#192131", "#38465f", "#8597ba"], accent="#ffe94a", spark="#ffffff"),
    "void":    dict(stops=["#050309", "#140924", "#2c154c", "#57309a"], accent="#d46bff", spark="#f6dcff"),
    "bone":    dict(stops=["#2e2a26", "#7e776a", "#c7beaa", "#f2ede0"], accent="#ff2a3a", spark="#ffd0d0"),
}


def pal(set_name, st):
    return PAL.get(set_name, st)


def paint_face(set_name, layer, part, kind, w, h, rnd):
    """Returns (material, height) for one box face. Smooth armour plates; the seams between plates glow."""
    mat = np.full((h, w), MM, dtype=np.int8)
    ht = np.zeros((h, w))
    yy, xx = np.mgrid[0:h, 0:w]
    edge = np.minimum.reduce([xx, yy, w - 1 - xx, h - 1 - yy])
    side = kind in ("front", "back", "left", "right")
    bump(ht, edge >= 0, 4.0, 4)                 # rounded plate edges

    def seam(m, glow=True, depth=2.5):
        ht[m] -= depth
        if glow:
            mat[m] = MA

    def plate(m, height=2.5, bevel=3):
        bump(ht, m, height, bevel)

    inner = edge >= 5
    # ---------------- subtle set texture on the plates (engraved, not glowing)
    if set_name == "draco" and part in ("arm", "leg"):
        r = 9
        cy = yy % r
        cx = (xx + ((yy // r) % 2) * r // 2) % r - r / 2
        dd = np.hypot(cx, cy)
        ht += np.clip(1 - dd / (r * 0.75), 0, 1) * 1.6 * inner
    elif set_name == "inferno":
        n = np.sin(xx * 0.29 + np.sin(yy * 0.15) * 3) + np.sin(yy * 0.21 + xx * 0.06)
        crack = (np.abs(n) < 0.06) & inner & ((part != "body") | (kind != "front"))
        seam(crack, True, 1.5)
    elif set_name == "frost":
        lat = ((np.abs((xx + yy) % 22 - 11) < 0.6) | (np.abs((xx - yy) % 22 - 11) < 0.6)) & inner
        ht[lat] += 1.0
    elif set_name == "void":
        stars = inner & np.array([[rnd.random() < 0.004 for _ in range(w)] for _ in range(h)])
        mat[stars] = MA
    elif set_name == "bone":
        rib = inner & ((yy % 12) < 5) & (part != "body" or kind != "front")
        plate(rib, 1.4, 2)
    elif set_name == "storm" and side and kind in ("left", "right"):
        zig = np.abs(xx - w / 2 - ((yy // 8) % 2 * 2 - 1) * ((yy % 8) - 4) * 0.9) < 1.1
        seam(zig & inner, True, 1.0)

    # ---------------- plate layouts
    if part == "body" and layer == "humanoid":
        if kind == "front":
            collar = yy < 7
            plate(collar, 3, 2)
            v = np.abs(yy - (h * 0.40 + np.abs(xx - w / 2) * -0.22)) < 1.1      # pec line (V)
            seam(v & (edge >= 3))
            seam((np.abs(xx - w / 2) < 1.1) & (yy > h * 0.40) & (edge >= 3))
            for k in (0.62, 0.8):
                seam((np.abs(yy - h * k) < 1.0) & (np.abs(xx - w / 2) < w * 0.32))
            plate((yy > h * 0.40) & (np.abs(xx - w / 2) < w * 0.34) & (yy < h * 0.95), 1.5, 2)   # abs
            # core reactor
            cx, cy = w / 2, h * 0.27
            r = np.hypot(xx - cx, yy - cy)
            ring = r < 9
            mat[ring] = MD
            ht[ring] = 7 - r[ring] * 0.1
            core = r < 6
            mat[core] = MA
            ht[core] = 9 - r[core] * 0.6
        elif kind == "back":
            seam((np.abs(xx - w / 2) < 1.1) & (edge >= 3))
            seam((np.abs(yy - (h * 0.30 + np.abs(xx - w / 2) * 0.45)) < 1.0) & (edge >= 3))
            plate(np.abs(xx - w / 2) < 5, 2, 2)
        elif kind in ("left", "right"):
            seam((np.abs(yy - h * 0.5) < 1.0) & (edge >= 3))
    if part == "body" and layer == "humanoid_leggings" and side:
        belt = yy < h * 0.28
        mat[belt] = MD
        plate(belt, 3, 2)
        if kind == "front":
            b = (np.abs(xx - w / 2) < 6) & (yy > 4) & (yy < h * 0.28 - 4)
            mat[b] = MA
            ht[b] += 2
    if part == "arm":
        # layered pauldron: three overlapping curved plates
        for k, y0 in enumerate((0, 10, 20)):
            arc = yy < y0 + 12 - np.abs(xx - w / 2) * 0.25
            plate(arc & (yy >= y0 - 2), 3.5 - k * 0.6, 3)
            seam(np.abs(yy - (y0 + 12 - np.abs(xx - w / 2) * 0.25)) < 0.9, glow=(k == 2))
        brace = yy > h * 0.62
        plate(brace, 2.5, 3)
        seam(np.abs(yy - h * 0.62) < 1.0)
        if kind in ("left", "right"):
            seam((np.abs(xx - w / 2) < 1.0) & (yy > h * 0.66) & (yy < h - 6))
    if part == "leg" and layer == "humanoid" and side:            # boots
        top = h * 0.55
        seam(np.abs(yy - top) < 1.0)
        plate(yy > h * 0.82, 3.5, 3)                                # toe cap
        seam(np.abs(yy - h * 0.82) < 0.9, glow=False)
        if kind == "front":
            shin = (yy > top + 3) & (yy < h * 0.8) & (np.abs(xx - w / 2) < w * 0.32)
            plate(shin, 2, 2)
            seam((np.abs(xx - w / 2) < 1.0) & (yy > top + 4) & (yy < h * 0.78))
    if part == "leg" and layer == "humanoid_leggings" and side:
        seam(np.abs(yy - h * 0.34) < 1.0)
        kc = h * 0.52
        if kind == "front":
            kr = np.hypot((xx - w / 2) * 1.0, (yy - kc) * 1.2)
            knee = kr < 11
            plate(knee, 4, 3)
            mat[(kr > 9.5) & knee] = MD
            g = kr < 3
            mat[g] = MA
            ht[g] += 1.5
        else:
            seam(np.abs(yy - kc) < 0.9, glow=False)
    if part == "head" and kind == "front":
        ey = int(h * 0.36)
        eyes = (np.abs(yy - ey) < 2) & (edge >= 5) & (np.abs(xx - w / 2) > 3)
        mat[eyes] = MA
        ht[eyes] -= 1
        seam((np.abs(xx - w / 2) < 1.0) & (yy > ey + 3) & (edge >= 4), glow=False)
    return mat, ht


def light(mat, ht, st, along):
    stops = st["stops"]
    acc = rgb(st["accent"])
    hs = ndimage.gaussian_filter(ht, 0.9)
    gy, gx = np.gradient(hs)
    nx, ny, nz = -gx * 1.1, -gy * 1.1, np.ones_like(hs)
    ln = np.sqrt(nx ** 2 + ny ** 2 + nz ** 2)
    nx, ny, nz = nx / ln, ny / ln, nz / ln
    L = np.array([-0.35, -0.7, 0.62]); L /= np.linalg.norm(L)
    H = L + np.array([0, 0, 1.0]); H /= np.linalg.norm(H)
    diff = np.clip(nx * L[0] + ny * L[1] + nz * L[2], 0, 1)
    spec = np.clip(nx * H[0] + ny * H[1] + nz * H[2], 0, 1) ** 30
    col = np.zeros(mat.shape + (3,))
    t = (diff - 0.45) * 1.5 + 0.40 + along
    m = mat == MM
    col[m] = hq.ramp([stops[0], stops[1], stops[2], stops[3]], t[m])
    col[m] += (spec[m, None] * (180 + 0.3 * rgb(stops[3])))
    d = (mat == MD) | (mat == MG)
    col[d] = hq.ramp([stops[0], stops[0], stops[1], stops[2]], t[d]) + spec[d, None] * 120
    a = mat == MA
    # glow: bright core, coloured edges; also tint neighbouring metal (light bleed)
    col[a] = acc * 0.95 + (255 - acc) * 0.45
    bleed = ndimage.gaussian_filter(a.astype(float), 3.0) * (~a)
    col += bleed[..., None] * acc[None, None] * 1.4
    return np.clip(col, 0, 255)


def worn(set_name, layer, st, vanilla_path):
    st = pal(set_name, st)
    src = Image.open(vanilla_path).convert("RGBA")
    W, H = src.size
    cover = np.array(src)[..., 3] > 0
    cover = np.kron(cover, np.ones((K * 64 // W, K * 32 // H), dtype=bool))
    if layer == "humanoid":
        cover[16 * K:32 * K, 40 * K:56 * K] = True     # full sleeves
        cover[0:16 * K, 0:32 * K] = False              # head is the 3D helmet now
    out = np.zeros((32 * K, 64 * K, 4))
    mats = np.zeros((32 * K, 64 * K), dtype=np.int8)
    rnd = random.Random(hash((set_name, layer)) & 0xffff)
    for part, (ox, oy, w, h, d) in BOXES.items():
        for kind, box in faces_of(ox, oy, w, h, d).items():
            x0, y0, x1, y1 = [v * K for v in box]
            fw, fh = x1 - x0, y1 - y0
            mat, ht = paint_face(set_name, layer, part, kind, fw, fh, rnd)
            yy = np.mgrid[0:fh, 0:fw][0]
            along = (0.08 - 0.16 * yy / max(fh - 1, 1)) if kind not in ("top", "bottom") else 0.05
            out[y0:y1, x0:x1, :3] = light(mat, ht, st, along)
            out[y0:y1, x0:x1, 3] = 255
            mats[y0:y1, x0:x1] = mat
    out[..., 3] *= cover
    return Image.fromarray(posterize(out, mats, 4, st).astype(np.uint8), "RGBA")


def posterize(img, mats, f, st):
    """Downsample by f and snap every pixel to a small palette per material -> crisp pixel-art look."""
    Hh, Ww = img.shape[0] // f, img.shape[1] // f
    col = img[..., :3].reshape(Hh, f, Ww, f, 3).mean(axis=(1, 3))
    alpha = img[..., 3].reshape(Hh, f, Ww, f).mean(axis=(1, 3)) > 127
    # majority material, but any glow in the block wins (keeps thin glowing seams)
    m4 = mats.reshape(Hh, f, Ww, f)
    mat = np.zeros((Hh, Ww), dtype=np.int8)
    best = np.zeros((Hh, Ww))
    for k in (MM, MD, MG, 9):
        c = (m4 == k).mean(axis=(1, 3))
        mat[c > best] = k
        best = np.maximum(best, c)
    mat[(m4 == MA).mean(axis=(1, 3)) >= 0.25] = MA
    s = [rgb(c) for c in st["stops"]]
    W = np.array([255.0, 255, 255])
    acc = rgb(st["accent"])
    P = {MM: [s[0] * 0.8 + s[1] * 0.2, s[1], (s[1] + s[2]) / 2, s[2], s[3], s[3] * 0.5 + W * 0.5],
         MD: [s[0] * 0.6, s[0], s[1]],
         MG: [s[0] * 0.6, s[0], s[1]],
         MA: [acc * 0.75 + s[1] * 0.25, acc, acc * 0.45 + W * 0.55]}
    out = np.zeros((Hh, Ww, 4))
    out[..., :3] = col
    lum = col @ np.array([0.299, 0.587, 0.114])
    for k, pal_ in P.items():
        m = mat == k
        if not m.any():
            continue
        pl = np.array([c @ np.array([0.299, 0.587, 0.114]) for c in pal_])
        i = np.abs(lum[m][:, None] - pl[None, :]).argmin(axis=1)
        out[m, :3] = np.array(pal_)[i]
    out[..., 3] = alpha * 255
    return np.clip(out, 0, 255)


# ------------------------------------------------------------------ 3D helmets (worn on the head)
def helmet_texture(set_name, st):
    st = pal(set_name, st)
    """64x64 atlas for the 3D helmet model. UV units (0..16) -> 4px each.
    front 0,0-8,8 | side 8,0-16,8 | gold 0,8-4,12 | accent 4,8-8,12 | metal 8,8-12,12 | dark 12,8-16,12 | horn 0,12-8,16"""
    out = np.zeros((64, 64, 4))
    hm = np.full((64, 64), MM, dtype=np.int8)
    rnd = random.Random(len(set_name))
    for (kind, x0) in (("front", 0), ("left", 32)):
        mat, ht = paint_face(set_name, "humanoid", "head", kind, 32, 32, rnd)
        if kind == "front":
            ey = 11
            eyes = (np.abs(np.mgrid[0:32, 0:32][0] - ey) < 2) & (np.abs(np.mgrid[0:32, 0:32][1] - 16) > 2) & \
                   (np.abs(np.mgrid[0:32, 0:32][1] - 16) < 12)
            mat[eyes] = MA
        yy = np.mgrid[0:32, 0:32][0]
        out[0:32, x0:x0 + 32, :3] = light(mat, ht, st, 0.08 - 0.16 * yy / 31)
        hm[0:32, x0:x0 + 32] = mat
        out[0:32, x0:x0 + 32, 3] = 255
    yy, xx = np.mgrid[0:16, 0:16]
    stops = st["stops"]
    gold = hq.tint(hq.GOLD, stops[2], 0.25)
    sw = lambda cols, lo, hi: hq.ramp(cols, lo + (hi - lo) * (1 - yy / 15) * 0.7 + (1 - xx / 15) * (hi - lo) * 0.3)
    out[32:48, 0:16, :3] = sw([stops[0], stops[1], stops[2], stops[3]], 0.15, 0.75)
    acc = rgb(st["accent"])
    out[32:48, 16:32, :3] = np.clip(acc[None, None] * (0.8 + 0.3 * (1 - yy / 15))[..., None] + 40 * (1 - yy / 15)[..., None], 0, 255)
    met = [stops[0], stops[1], stops[2], stops[3], "#ffffff"]
    out[32:48, 32:48, :3] = sw(met, 0.35, 0.85)
    out[32:48, 48:64, :3] = sw(met, 0.1, 0.45)
    hy, hx_ = np.mgrid[0:16, 0:32]
    horn_cols = ["#2a2018", "#6b5a44", "#c9b68e", "#fbf3dc"] if set_name in ("bone", "draco") else met
    ridges = (hx_ % 6 < 1).astype(float)
    out[48:64, 0:32, :3] = hq.ramp(horn_cols, 0.3 + 0.55 * (1 - hy / 15) - ridges * 0.25)
    out[32:64, :, 3] = 255
    out[48:64, 32:, 3] = 0
    hm[32:48, 16:32] = MA
    hm[32:48, 48:64] = MD
    hm[48:64, 0:32] = 9
    return Image.fromarray(posterize(out, hm, 2, st).astype(np.uint8), "RGBA")


UV = {"front": [0, 0, 8, 8], "side": [8, 0, 16, 8], "gold": [0, 8, 4, 12], "acc": [4, 8, 8, 12],
      "metal": [8, 8, 12, 12], "dark": [12, 8, 16, 12], "horn": [0, 12, 8, 16]}


def box(fr, to, uv="metal", front_uv=None, rot=None):
    faces = {}
    for f in ("north", "south", "east", "west", "up", "down"):
        faces[f] = {"uv": UV[uv], "texture": "#t"}
    if front_uv:
        faces["north"]["uv"] = UV[front_uv]
    e = {"from": fr, "to": to, "faces": faces}
    if rot:
        e["rotation"] = rot
    return e


def mirror(e):
    m = dict(e)
    m["from"] = [16 - e["to"][0], e["from"][1], e["from"][2]]
    m["to"] = [16 - e["from"][0], e["to"][1], e["to"][2]]
    if "rotation" in e:
        r = dict(e["rotation"])
        r["origin"] = [16 - r["origin"][0], r["origin"][1], r["origin"][2]]
        if r["axis"] in ("y", "z"):
            r["angle"] = -r["angle"]
        m["rotation"] = r
    m["faces"] = dict(e["faces"])
    m["faces"]["east"], m["faces"]["west"] = e["faces"]["west"], e["faces"]["east"]
    return m


def rot(angle, axis, origin):
    return {"angle": angle, "axis": axis, "origin": origin}


def helmet_model(set_name, tex):
    E = []
    # shell: sides/back/top use the plated side face, the front face has the visor
    shell = box([0, 3, 0], [16, 16, 16], "side", front_uv="front")
    E.append(shell)
    E.append(box([-0.5, 3, -0.5], [16.5, 5, 16.5], "dark"))                 # lower rim
    E.append(box([-0.3, 5, -0.3], [16.3, 5.4, 16.3], "acc"))                # glowing seam
    E.append(box([7, 15.5, -0.5], [9, 17.5, 16.5], "dark"))                  # crest ridge
    side_guard = box([-0.6, 0, -0.6], [3, 9, 6], "metal")                     # cheek guards
    E += [side_guard, mirror(side_guard)]
    E.append(box([1.5, 8.5, -0.8], [14.5, 10, -0.2], "acc"))                   # visor glow bar
    E.append(box([7.2, 2, -0.9], [8.8, 10.5, -0.2], "dark"))                   # nose guard
    if set_name == "draco":                                                   # swept-back dragon horns
        for k, (x, y, z, s) in enumerate([(-1.5, 10, 6, 3.6), (-3.2, 12.2, 8, 3.0), (-4.3, 14.5, 10.2, 2.4), (-4.9, 16.6, 12.4, 1.8), (-5.1, 18.4, 14.3, 1.2)]):
            h = box([x, y, z], [x + s, y + s, z + s], "horn")
            E += [h, mirror(h)]
        for k in range(4):                                                    # spine plates
            E.append(box([7.4, 17, 2 + k * 3.5], [8.6, 19.5 - k * 0.4, 4 + k * 3.5], "acc"))
    elif set_name == "bone":                                                  # big ram horns curling down
        pts = [(-1.5, 11, 5), (-3.5, 12.5, 7), (-4.8, 11.5, 9.5), (-4.6, 9, 11.5), (-3.4, 7, 12)]
        for k, (x, y, z) in enumerate(pts):
            s = 3.6 - k * 0.5
            h = box([x, y, z], [x + s, y + s, z + s], "horn")
            E += [h, mirror(h)]
    elif set_name == "frost":                                                 # ice crown
        for k, (x, z, hh) in enumerate([(2, 2, 5), (5.5, 1, 7), (9.5, 1, 7), (13, 2, 5), (7.25, 0.5, 9.5), (2, 9, 4), (13, 9, 4)]):
            E.append(box([x - 0.75 + (0.75 if x > 8 else 0), 16, z], [x + 0.75, 16 + hh, z + 1.5], "acc",
                         rot=rot(22.5 if x < 7 else (-22.5 if x > 9 else 0), "z", [x, 16, z])))
    elif set_name == "inferno":                                               # flame crest
        for k in range(6):
            hh = [3, 5, 7.5, 9, 7, 4][k]
            E.append(box([6.5, 16, 1 + k * 2.5], [9.5, 16 + hh, 3.5 + k * 2.5], "acc"))
            E.append(box([7, 16, 1.5 + k * 2.5], [9, 16 + hh * 0.6, 3 + k * 2.5], "dark"))
    elif set_name == "storm":                                                 # winged helm
        for k in range(4):
            wv = box([-1.2, 9 + k * 1.8, 5 + k * 1.2], [-0.4, 11 + k * 1.8, 14 + k * 1.5], "metal" if k % 2 else "acc",
                     rot=rot(-22.5, "z", [-0.4, 9 + k * 1.8, 6]))
            E += [wv, mirror(wv)]
    elif set_name == "void":                                                  # tall fin + side spikes
        E.append(box([7.25, 16, 3], [8.75, 23, 13], "acc", rot=rot(-22.5, "x", [8, 16, 8])))
        E.append(box([7, 16, 2], [9, 19, 14], "dark"))
        sp = box([-2, 11, 6], [0, 13, 9], "acc", rot=rot(22.5, "z", [0, 12, 7]))
        E += [sp, mirror(sp)]
    for e in E:
        for k in ("from", "to"):
            e[k] = [max(-16, min(32, v)) for v in e[k]]
    return {"textures": {"t": tex, "particle": tex}, "elements": E,
            "display": {"head": {"translation": [0, 0, 0], "scale": [1, 1, 1]}}}
