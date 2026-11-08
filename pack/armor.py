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
    c = Canvas()
    PIECES[piece](c, set_name)
    return hq.frames_from(hq.lit_map(c.g, c.wrap, st, metal_bevel=7.0), st, seed=len(set_name + piece), n_frames=12)


# ------------------------------------------------------------------ worn (humanoid) textures
K = 4  # scale of the 64x32 layout

def faces_of(ox, oy, w, h, d):
    """Minecraft box UV: returns dict face -> (x0, y0, x1, y1) in 64x32 pixels."""
    return {"top": (ox + d, oy, ox + d + w, oy + d), "bottom": (ox + d + w, oy, ox + d + 2 * w, oy + d),
            "right": (ox, oy + d, ox + d, oy + d + h), "front": (ox + d, oy + d, ox + d + w, oy + d + h),
            "left": (ox + d + w, oy + d, ox + 2 * d + w, oy + d + h), "back": (ox + 2 * d + w, oy + d, ox + 2 * d + 2 * w, oy + d + h)}


BOXES = {"head": (0, 0, 8, 8, 8), "hat": (32, 0, 8, 8, 8), "body": (16, 16, 8, 12, 4),
         "arm": (40, 16, 4, 12, 4), "leg": (0, 16, 4, 12, 4)}


def rgb(h):
    return hq.rgb(h)


def worn(set_name, layer, st, vanilla_path):
    src = Image.open(vanilla_path).convert("RGBA")
    W, H = src.size
    cover = np.array(src)[..., 3] > 0
    cover = np.kron(cover, np.ones((K * 64 // W, K * 32 // H), dtype=bool))
    out = np.zeros((32 * K, 64 * K, 4))
    rnd = random.Random(hash((set_name, layer)) & 0xffff)
    stops = st["stops"]
    ramp = [stops[0], stops[1], stops[2], stops[3]]
    gold = hq.tint(hq.GOLD, stops[2], 0.25)
    acc = rgb(st["accent"])

    def face(box, kind, part):
        x0, y0, x1, y1 = [v * K for v in box]
        w, h = x1 - x0, y1 - y0
        if w <= 0 or h <= 0:
            return
        yy, xx = np.mgrid[0:h, 0:w]
        # brushed metal + vertical light gradient + bevel at the face edges
        t = 0.62 - 0.28 * (yy / max(h - 1, 1)) + (np.array([[rnd.random() for _ in range(w)] for _ in range(h)]) - 0.5) * 0.06
        edge = np.minimum.reduce([xx, yy, w - 1 - xx, h - 1 - yy])
        t = t + np.where(edge == 0, 0.0, 0) + np.where((yy == 0) | (xx == 0), 0.2, 0) - np.where((yy == h - 1) | (xx == w - 1), 0.25, 0)
        col = hq.ramp(ramp, t)
        trim = (edge >= 1) & (edge <= 2)
        if kind != "top" and kind != "bottom":
            col[trim] = hq.ramp(gold, np.full(trim.sum(), 0.55) + (yy[trim] < h / 2) * 0.2)
        # set pattern
        pat = np.zeros((h, w), dtype=bool)
        glow = np.zeros((h, w), dtype=bool)
        if set_name == "draco":
            cx = (xx + (yy // 6) % 2 * 4) % 8 - 4
            pat = np.abs(np.hypot(cx, yy % 6) - 4.2) < 0.7
        elif set_name == "frost":
            pat = ((xx + yy) % 11 == 0) | ((xx - yy) % 13 == 0)
        elif set_name == "inferno":
            glow = ((np.sin(xx * 0.55 + yy * 0.2) * 3 + yy) % 14 < 1.2) & (edge > 3)
        elif set_name == "storm":
            glow = (np.abs(((yy // 5) % 2 * 2 - 1) * ((yy % 5) - 2.5) + w / 2 - xx) < 0.9) & (edge > 3) & (kind == "front")
        elif set_name == "void":
            glow = ((xx * 3 + yy * 5) % 23 == 0) & (edge > 3)
        elif set_name == "bone":
            pat = ((xx + 2 * yy) % 17 == 0) & (edge > 2)
        inner = edge > 2
        col[pat & inner] = col[pat & inner] * 0.72
        col[glow] = acc
        # rivets in the corners
        for (rx, ry) in ((4, 4), (w - 5, 4), (4, h - 5), (w - 5, h - 5)):
            if kind in ("front", "back", "left", "right") and w >= 12 and h >= 12:
                col[max(0, ry - 1):ry + 1, max(0, rx - 1):rx + 1] = hq.ramp(gold, np.array([0.9]))[0]
        # part-specific glowing details
        if part == "head" and kind == "front":
            ey = int(h * 0.22)
            col[ey:ey + 4, 5:w // 2 - 3] = acc
            col[ey:ey + 4, w // 2 + 3:w - 5] = acc
            col[ey - 1, 5:w - 5] = hq.ramp(gold, np.array([0.8]))[0]
            col[ey + 4:, w // 2 - 2:w // 2 + 2] = hq.ramp(ramp, np.array([0.9]))[0]
            col[ey + 4:, :4] = hq.ramp(gold, np.array([0.55]))[0]
            col[ey + 4:, w - 4:] = hq.ramp(gold, np.array([0.55]))[0]
        if part == "head" and kind == "top":
            col[:, w // 2 - 2:w // 2 + 2] = hq.ramp(gold, np.array([0.7]))[0]
        if part == "body" and kind == "front" and layer == "humanoid":
            cx, cy = w // 2, int(h * 0.36)
            dmd = np.abs(xx - cx) + np.abs(yy - cy)
            col[dmd <= 7] = hq.ramp(gold, np.array([0.75]))[0]
            col[dmd <= 4] = acc
            col[dmd <= 1] = acc * 0.5 + 127
            col[(yy == int(h * 0.62)) & inner] *= 0.7
            col[(yy == int(h * 0.78)) & inner] *= 0.7
        if part == "body" and layer == "humanoid_leggings" and kind in ("front", "back", "left", "right"):
            col[:int(h * 0.3)] = hq.ramp(gold, np.full((min(h, int(h * 0.3)), w), 0.6))
            if kind == "front":
                col[2:int(h * 0.3) - 2, w // 2 - 3:w // 2 + 3] = acc
        if part == "arm" and kind in ("left", "right", "front", "back"):
            col[:6] = hq.ramp(gold, np.full((6, w), 0.65))                # shoulder band
            if kind == "right" or kind == "left":
                col[10:h - 8, w // 2 - 1:w // 2 + 1] = acc
        if part == "leg" and layer == "humanoid":                        # boots
            if kind in ("front", "left", "right", "back"):
                col[int(h * 0.62):int(h * 0.68)] = acc if kind == "front" else hq.ramp(gold, np.array([0.6]))[0]
                col[int(h * 0.86):] = hq.ramp(gold, np.full((h - int(h * 0.86), w), 0.45))
        if part == "leg" and layer == "humanoid_leggings" and kind == "front":
            ky = int(h * 0.45)
            col[ky - 5:ky + 5, 3:w - 3] = hq.ramp(gold, np.array([0.7]))[0]
            col[ky - 2:ky + 2, w // 2 - 2:w // 2 + 2] = acc
        out[y0:y1, x0:x1, :3] = np.clip(col, 0, 255)
        out[y0:y1, x0:x1, 3] = 255

    for part, (ox, oy, w, h, d) in BOXES.items():
        for kind, box in faces_of(ox, oy, w, h, d).items():
            face(box, kind, part)
    out[..., 3] *= cover
    return Image.fromarray(out.astype(np.uint8), "RGBA")
