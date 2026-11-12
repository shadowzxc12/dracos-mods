"""Hand-style pixel-art worn armor (128x64 = 2x vanilla).

Every face is laid out as distinct plates separated by 1px dark outlines. Each plate is shaded like
classic pixel art (light top/left rim, dark bottom/right rim, slight top-to-bottom gradient), with
metal trims (gold or silver) and a few glowing gems/runes in the set's accent colour.
"""
import numpy as np
from PIL import Image

import hq

F = 2  # pixels per vanilla pixel
BOXES = {"head": (0, 0, 8, 8, 8), "body": (16, 16, 8, 12, 4), "arm": (40, 16, 4, 12, 4), "leg": (0, 16, 4, 12, 4)}

O, P, P2, T, G, D = 0, 1, 2, 3, 4, 5   # outline, plate, plate (alt shade), trim, glow, dark gap

SETS = {
    #           outline    shadow     base       light      highlight  trim(gold/silver)
    "void":    (["#0d0618", "#2a1250", "#4b2391", "#7445c9", "#a985ff"], "gold", "#e070ff"),
    "draco":   (["#1a0505", "#4d0f0b", "#8c1f14", "#c8402a", "#f08060"], "gold", "#ffb030"),
    "inferno": (["#140605", "#3a120a", "#6a2410", "#a8441a", "#e08040"], "gold", "#ff6a00"),
    "frost":   (["#06101e", "#163a5e", "#2f6e9e", "#62a8d6", "#bfe8ff"], "silver", "#6ff6ff"),
    "storm":   (["#080b12", "#212b3e", "#3d4b66", "#6a7c9e", "#a8b8d8"], "silver", "#ffe94a"),
    "bone":    (["#2a241e", "#6e6556", "#a69c86", "#d4ccb6", "#f6f0e0"], "gold", "#ff3040"),
}
TRIM = {"gold": ["#3a2208", "#8a5a14", "#d09a2a", "#f6d066", "#fff4c0"],
        "silver": ["#20242c", "#5c6676", "#9aa6b8", "#d2dae6", "#ffffff"]}


def rgb(h):
    return np.array(hq.rgb(h), dtype=float)


def faces_of(ox, oy, w, h, d):
    return {"top": (ox + d, oy, ox + d + w, oy + d), "bottom": (ox + d + w, oy, ox + d + 2 * w, oy + d),
            "right": (ox, oy + d, ox + d, oy + d + h), "front": (ox + d, oy + d, ox + d + w, oy + d + h),
            "left": (ox + d + w, oy + d, ox + 2 * d + w, oy + d + h),
            "back": (ox + 2 * d + w, oy + d, ox + 2 * d + 2 * w, oy + d + h)}


class Face:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.reg = np.zeros((h, w), dtype=int)      # region id (0 = none yet)
        self.mat = {0: P}
        self.n = 0
        self.fill(0, 0, w, h, P)

    def fill(self, x0, y0, x1, y1, mat):
        self.n += 1
        self.reg[max(y0, 0):min(y1, self.h), max(x0, 0):min(x1, self.w)] = self.n
        self.mat[self.n] = mat
        return self.n

    def mask(self, m, mat):
        self.n += 1
        self.reg[m] = self.n
        self.mat[self.n] = mat

    def outline_row(self, y, x0=0, x1=None):
        if 0 <= y < self.h:
            self.reg[y, x0:self.w if x1 is None else x1] = -1

    def outline_col(self, x, y0=0, y1=None):
        if 0 <= x < self.w:
            self.reg[y0:self.h if y1 is None else y1, x] = -1


def render(face, pal, trim, glow, edge_dark=True):
    w, h = face.w, face.h
    out = np.zeros((h, w, 3))
    reg = face.reg
    P5 = [rgb(c) for c in pal]
    T5 = [rgb(c) for c in trim]
    gl = rgb(glow)
    white = np.array([255.0, 255, 255])
    for y in range(h):
        for x in range(w):
            r = reg[y, x]
            if r < 0:
                out[y, x] = P5[0]
                continue
            m = face.mat.get(r, P)
            up = reg[y - 1, x] if y > 0 else None
            dn = reg[y + 1, x] if y < h - 1 else None
            lf = reg[y, x - 1] if x > 0 else None
            rt = reg[y, x + 1] if x < w - 1 else None
            top = up != r
            bot = dn != r
            left = lf != r
            right = rt != r
            if m == G:
                core = not (top or bot or left or right)
                out[y, x] = (gl * 0.4 + white * 0.6) if core else gl
                continue
            if m == D:
                out[y, x] = P5[0] * 0.6 + P5[1] * 0.4
                continue
            ramp = T5 if m == T else P5
            i = 2 if m != P2 else 1.6
            # gentle vertical gradient inside the region
            ys = np.where((reg == r).any(axis=1))[0]
            if len(ys) > 2:
                t = (y - ys[0]) / max(ys[-1] - ys[0], 1)
                i += 0.45 - t * 0.9
            if top or left:
                i += 1.1
            if bot or right:
                i -= 1.0
            i = int(round(min(max(i, 1), 4)))
            out[y, x] = ramp[i]
    # glow bleed onto neighbours
    gm = np.isin(reg, [k for k, v in face.mat.items() if v == G])
    if gm.any():
        from scipy import ndimage
        b = ndimage.gaussian_filter(gm.astype(float), 0.8) * (~gm)
        out += b[..., None] * gl[None, None] * 0.6
    if edge_dark:  # face outline so boxes read as separate plates
        out[0, :] = np.maximum(out[0, :], 0)
        out[-1, :] = out[-1, :] * 0.7
        out[:, -1] = out[:, -1] * 0.8
    return np.clip(out, 0, 255)


# ------------------------------------------------------------------ part layouts (sizes in 2x px)
def body_chest(kind, w, h):
    f = Face(w, h)
    if kind == "front":
        f.fill(0, 0, w, 2, T)                     # collar
        f.outline_row(2)
        cx = w // 2
        f.fill(0, 3, cx - 1, 11, P)               # pecs
        f.fill(cx + 1, 3, w, 11, P)
        f.fill(cx - 1, 3, cx + 1, 11, T)          # sternum trim
        f.fill(cx - 1, 5, cx + 1, 8, G)           # core gem
        f.outline_row(11)
        for y0, y1 in ((12, 15), (16, 19), (20, 23)):
            f.fill(1, y0, cx, y1, P2)
            f.fill(cx, y0, w - 1, y1, P2)
            f.outline_row(y1)
            f.outline_col(cx, y0, y1)
        f.fill(0, 12, 1, 24, T)
        f.fill(w - 1, 12, w, 24, T)
        f.fill(0, 23, w, 24, T)
    elif kind == "back":
        f.fill(0, 0, w, 2, T)
        f.outline_row(2)
        cx = w // 2
        f.fill(0, 3, cx - 1, 12, P)
        f.fill(cx + 1, 3, w, 12, P)
        f.fill(cx - 1, 3, cx + 1, 24, T)          # spine
        f.outline_row(12, 0, cx - 1)
        f.outline_row(12, cx + 1)
        f.fill(0, 13, cx - 1, 23, P2)
        f.fill(cx + 1, 13, w, 23, P2)
        f.fill(0, 23, w, 24, T)
    elif kind in ("left", "right"):
        f.fill(0, 0, w, 11, P)
        f.outline_row(11)
        f.fill(0, 12, w, 23, P2)
        f.fill(0, 23, w, 24, T)
    else:
        f.fill(0, 0, w, h, T)
        f.fill(1, 1, w - 1, h - 1, P)
    return f


def body_belt(kind, w, h):
    f = Face(w, h)
    if kind in ("front", "back", "left", "right"):
        f.fill(0, 0, w, h, P2)
        f.fill(0, h - 6, w, h - 2, T)            # belt
        f.outline_row(h - 7)
        f.outline_row(h - 2)
        f.fill(0, h - 1, w, h, P)
        if kind == "front":
            cx = w // 2
            f.fill(cx - 2, h - 6, cx + 2, h - 2, D)
            f.fill(cx - 1, h - 5, cx + 1, h - 3, G)   # buckle gem
    else:
        f.fill(0, 0, w, h, T)
    return f


def arm(kind, w, h):
    f = Face(w, h)
    if kind == "top":
        f.fill(0, 0, w, h, T)
        f.fill(1, 1, w - 1, h - 1, P)
        return f
    if kind == "bottom":
        f.fill(0, 0, w, h, P2)
        return f
    f.fill(0, 0, w, 8, P)                         # pauldron
    f.fill(0, 7, w, 9, T)                         # pauldron rim
    f.outline_row(9)
    f.fill(0, 10, w, 15, P2)                      # upper arm
    f.outline_row(15)
    f.fill(0, 16, w, 18, T)                       # bracer cuff
    f.fill(0, 18, w, 24, P)                       # bracer
    f.outline_row(23)
    if kind in ("right", "front"):
        f.fill(w // 2 - 1, 19, w // 2 + 1, 22, G)  # rune
    if kind == "right":
        f.fill(w // 2 - 1, 2, w // 2 + 1, 5, G)
    return f


def leg_pants(kind, w, h):
    f = Face(w, h)
    if kind in ("top", "bottom"):
        f.fill(0, 0, w, h, P2)
        return f
    f.fill(0, 0, w, 10, P)                        # thigh
    f.outline_row(10)
    f.fill(0, 11, w, 15, T if kind == "front" else P2)   # knee
    f.outline_row(15)
    f.fill(0, 16, w, 24, P2)                      # shin
    if kind == "front":
        f.fill(w // 2 - 1, 12, w // 2 + 1, 14, G)
        f.fill(w - 1, 0, w, 24, D)                # inner gap between legs
    if kind == "back":
        f.fill(0, 0, 1, 24, D)
    if kind == "right":
        f.fill(w // 2 - 1, 2, w // 2 + 1, 9, G)   # outer stripe
    if kind == "left":
        f.fill(0, 0, w, 24, D)
    return f


def leg_boot(kind, w, h):
    f = Face(w, h)
    if kind == "top":
        f.fill(0, 0, w, h, T)
        return f
    if kind == "bottom":
        f.fill(0, 0, w, h, D)
        return f
    f.fill(0, 0, w, 14, P2)
    f.fill(0, 14, w, 16, T)                       # cuff
    f.outline_row(16)
    f.fill(0, 17, w, 20, P)
    f.outline_row(20)
    f.fill(0, 21, w, 24, T if kind == "front" else P)   # toe cap
    if kind == "front":
        f.fill(w // 2 - 1, 17, w // 2 + 1, 19, G)
    return f


def worn(set_name, layer, vanilla_path):
    pal, trim_k, glow = SETS[set_name]
    trim = TRIM[trim_k]
    src = Image.open(vanilla_path).convert("RGBA")
    a = np.array(src.resize((64 * F, 32 * F), Image.NEAREST))[..., 3] > 0
    if layer == "humanoid":
        a[16 * F:32 * F, 40 * F:56 * F] = True       # full sleeves
        a[0:16 * F, 0:32 * F] = False                # head is the 3D helmet
    out = np.zeros((32 * F, 64 * F, 4))
    for part, (ox, oy, w, h, d) in BOXES.items():
        if part == "head":
            continue
        for kind, (x0, y0, x1, y1) in faces_of(ox, oy, w, h, d).items():
            fw, fh = (x1 - x0) * F, (y1 - y0) * F
            if part == "body":
                face = body_chest(kind, fw, fh) if layer == "humanoid" else body_belt(kind, fw, fh)
            elif part == "arm":
                face = arm(kind, fw, fh)
            else:
                face = leg_boot(kind, fw, fh) if layer == "humanoid" else leg_pants(kind, fw, fh)
            out[y0 * F:y1 * F, x0 * F:x1 * F, :3] = render(face, pal, trim, glow)
            out[y0 * F:y1 * F, x0 * F:x1 * F, 3] = 255
    out[..., 3] *= a
    return Image.fromarray(out.astype(np.uint8), "RGBA")
