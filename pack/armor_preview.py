"""Tiny software renderer: shows the worn armor on a player box model (dev tool, not used by the pack build).

    python3 pack/armor_preview.py <ref_dir> <out_dir>

Draws the vanilla armor boxes (outer layer inflated 1.0, leggings layer 0.5, left limbs mirrored) plus the
3D helmet item model on the head, orthographic camera, z-buffer, nearest-neighbour texels.
"""
import math
import os
import sys

import numpy as np
from PIL import Image

LIGHT = np.array([0.45, 0.75, -0.6])
LIGHT = LIGHT / np.linalg.norm(LIGHT)


def rot_y(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rot_x(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_axis(axis, a):
    c, s = math.cos(a), math.sin(a)
    if axis == "x":
        return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    if axis == "y":
        return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


class Scene:
    def __init__(self):
        self.quads = []   # (TL, TR, BL, tex, (u0, v0, u1, v1))

    def quad(self, tl, tr, bl, tex, uv):
        self.quads.append((np.array(tl, float), np.array(tr, float), np.array(bl, float), tex, uv))

    def box(self, lo, hi, tex, u, v, dims, inflate=0.0, mirror=False, k=1.0):
        """Entity-style box. lo/hi in player space (front = -z, player's right = +x). dims = (w, h, d) texels."""
        w, h, d = dims
        x0, y0, z0 = (c - inflate for c in lo)
        x1, y1, z1 = (c + inflate for c in hi)
        f = [
            ((x1, y1, z0), (x0, y1, z0), (x1, y0, z0), (u + d, v + d, u + d + w, v + d + h)),            # front
            ((x1, y1, z1), (x1, y1, z0), (x1, y0, z1), (u, v + d, u + d, v + d + h)),                    # right
            ((x0, y1, z0), (x0, y1, z1), (x0, y0, z0), (u + d + w, v + d, u + 2 * d + w, v + d + h)),    # left
            ((x0, y1, z1), (x1, y1, z1), (x0, y0, z1), (u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h)),  # back
            ((x1, y1, z1), (x0, y1, z1), (x1, y1, z0), (u + d, v, u + d + w, v + d)),                    # top
            ((x1, y0, z0), (x0, y0, z0), (x1, y0, z1), (u + d + w, v, u + d + 2 * w, v + d)),            # bottom
        ]
        for tl, tr, bl, uv in f:
            if mirror:
                tl, tr, bl = [(-p[0], p[1], p[2]) for p in (tl, tr, bl)]
            self.quad(tl, tr, bl, tex, tuple(c * k for c in uv))

    def item_model(self, model, tex, xf):
        """Block-model JSON elements; xf maps model coords (0..16) to player space."""
        k = tex.shape[1] / 16.0
        for e in model["elements"]:
            (x0, y0, z0), (x1, y1, z1) = e["from"], e["to"]
            corners = {
                "north": ((x1, y1, z0), (x0, y1, z0), (x1, y0, z0)),
                "south": ((x0, y1, z1), (x1, y1, z1), (x0, y0, z1)),
                "west": ((x0, y1, z0), (x0, y1, z1), (x0, y0, z0)),
                "east": ((x1, y1, z1), (x1, y1, z0), (x1, y0, z1)),
                "up": ((x0, y1, z0), (x1, y1, z0), (x0, y1, z1)),
                "down": ((x0, y0, z1), (x1, y0, z1), (x0, y0, z0)),
            }
            R, org = None, None
            if "rotation" in e:
                r = e["rotation"]
                R, org = rot_axis(r["axis"], math.radians(r["angle"])), np.array(r["origin"], float)
            for name, face in e["faces"].items():
                pts = [np.array(p, float) for p in corners[name]]
                if R is not None:
                    pts = [R @ (p - org) + org for p in pts]
                pts = [xf(p) for p in pts]
                self.quad(pts[0], pts[1], pts[2], tex, tuple(c * k for c in face["uv"]))

    def render(self, yaw, pitch=8, scale=12, size=(420, 560), center=(0, 17, 0), bg=None):
        W, H = size
        R = rot_x(math.radians(pitch)) @ rot_y(math.radians(yaw))
        col = np.zeros((H, W, 4), float)
        zb = np.full((H, W), 1e9)
        c = np.array(center, float)
        for tl, tr, bl, tex, (u0, v0, u1, v1) in self.quads:
            p0, p1, p3 = (R @ (p - c) for p in (tl, tr, bl))
            n = np.cross(p1 - p0, p3 - p0)
            ln = np.linalg.norm(n)
            if ln < 1e-9:
                continue
            n = n / ln
            if n[2] < 0:
                n = -n       # make it face the camera side (double-sided, like armor cutout no-cull)
            # camera looks along +z; n now points away from camera -> flip for lighting
            nl = -n
            b = 0.58 + 0.42 * max(0.0, float(nl @ LIGHT)) + 0.08 * max(0.0, float(nl @ np.array([0, 0, -1.0])))
            s0 = np.array([W / 2 - p0[0] * scale, H / 2 - p0[1] * scale])
            e1 = np.array([-(p1[0] - p0[0]) * scale, -(p1[1] - p0[1]) * scale])
            e3 = np.array([-(p3[0] - p0[0]) * scale, -(p3[1] - p0[1]) * scale])
            det = e1[0] * e3[1] - e1[1] * e3[0]
            if abs(det) < 1e-6:
                continue
            xs = [s0[0], s0[0] + e1[0], s0[0] + e3[0], s0[0] + e1[0] + e3[0]]
            ys = [s0[1], s0[1] + e1[1], s0[1] + e3[1], s0[1] + e1[1] + e3[1]]
            xa, xb = max(int(min(xs)), 0), min(int(max(xs)) + 2, W)
            ya, yb = max(int(min(ys)), 0), min(int(max(ys)) + 2, H)
            if xa >= xb or ya >= yb:
                continue
            gy, gx = np.mgrid[ya:yb, xa:xb]
            dx, dy = gx + 0.5 - s0[0], gy + 0.5 - s0[1]
            s = (dx * e3[1] - dy * e3[0]) / det
            t = (e1[0] * dy - e1[1] * dx) / det
            inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
            z = p0[2] + s * (p1[2] - p0[2]) + t * (p3[2] - p0[2])
            tu = np.clip(np.floor(u0 + s * (u1 - u0)).astype(int), 0, tex.shape[1] - 1)
            tv = np.clip(np.floor(v0 + t * (v1 - v0)).astype(int), 0, tex.shape[0] - 1)
            px = tex[tv, tu]
            ok = inside & (px[..., 3] > 25) & (z < zb[ya:yb, xa:xb])
            sub = col[ya:yb, xa:xb]
            sub[ok, :3] = np.clip(px[ok, :3] * b, 0, 255)
            sub[ok, 3] = 255
            zb[ya:yb, xa:xb][ok] = z[ok]
        img = Image.fromarray(col.astype(np.uint8), "RGBA")
        if bg is not None:
            back = Image.new("RGBA", size, bg)
            back.alpha_composite(img)
            img = back
        return img


def mannequin():
    """64x64-ish neutral 'skin' in the 64x32 classic layout (only what peeks out matters)."""
    t = np.zeros((32, 64, 4), float)
    t[..., :3] = (58, 60, 70)
    t[..., 3] = 255
    t[0:16, 0:32, :3] = (196, 150, 120)          # head
    t[20:32, 40:56, :3] = (58, 60, 70)
    t[29:32, 40:56, :3] = (196, 150, 120)        # hands
    t[11, 9:11, :3] = (40, 40, 60)
    t[11, 13:15, :3] = (40, 40, 60)
    return t


def tex_of(img):
    return np.array(img.convert("RGBA"), float)


def player(sc, outer=None, inner=None, helmet=None, head_from_layer=False, pieces="hclb"):
    """outer/inner: armor layer textures (numpy RGBA, any multiple of 64x32). helmet: (model_json, texture)."""
    skin = mannequin()
    parts = {  # name: (lo, hi, (u, v), (w, h, d), mirror)
        "head": ((-4, 24, -4), (4, 32, 4), (0, 0), (8, 8, 8), False),
        "body": ((-4, 12, -2), (4, 24, 2), (16, 16), (8, 12, 4), False),
        "rarm": ((4.02, 12, -2.03), (8.02, 24, 1.97), (40, 16), (4, 12, 4), False),
        "larm": ((4.02, 12, -2.03), (8.02, 24, 1.97), (40, 16), (4, 12, 4), True),
        "rleg": ((-0.1, 0, -2), (3.9, 12, 2), (0, 16), (4, 12, 4), False),
        "lleg": ((-0.1, 0, -2), (3.9, 12, 2), (0, 16), (4, 12, 4), True),
    }
    for name, (lo, hi, (u, v), dims, mir) in parts.items():
        sc.box(lo, hi, skin, u, v, dims, 0.0, mir)

    def layer(tex, names, inflate):
        k = tex.shape[1] / 64.0
        for name in names:
            lo, hi, (u, v), dims, mir = parts[name]
            sc.box(lo, hi, tex, u, v, dims, inflate, mir, k)

    if inner is not None and "l" in pieces:
        layer(inner, ("body", "rleg", "lleg"), 0.5)
    if outer is not None:
        if "b" in pieces:
            layer(outer, ("rleg", "lleg"), 1.0)
        if "c" in pieces:
            layer(outer, ("body", "rarm", "larm"), 1.0)
        if head_from_layer and "h" in pieces:
            layer(outer, ("head",), 1.0)
    if helmet is not None and "h" in pieces:
        model, tex = helmet
        sc.item_model(model, tex, lambda p: np.array([(p[0] - 8) * 0.625, (p[1] - 8) * 0.625 + 28, (p[2] - 8) * 0.625]))
    return sc


def views(outer, inner, helmet=None, head_from_layer=False, scale=15, size=(440, 640), bg=(24, 22, 30, 255),
          yaws=(-30, 150), pieces="hclb"):
    sc = player(Scene(), outer, inner, helmet, head_from_layer, pieces)
    ims = [sc.render(y, 8, scale, size, bg=bg) for y in yaws]
    out = Image.new("RGBA", (size[0] * len(ims), size[1]), bg)
    for i, im in enumerate(ims):
        out.paste(im, (i * size[0], 0))
    return out


def main():
    ref, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import armor
    import armor_px
    vt = os.path.join(ref, "assets/minecraft/textures/entity/equipment")
    sets = list(armor_px.SETS)
    big, small = [], []
    for s in sets:
        outer = tex_of(armor_px.worn(s, "humanoid", None))
        inner = tex_of(armor_px.worn(s, "humanoid_leggings", None))
        helm = (armor.helmet_model(s, "t"), tex_of(armor_px.helmet_texture(s)))
        v = views(outer, inner, helm)
        v.save(os.path.join(out, f"{s}.png"))
        big.append(v)
        small.append(views(outer, inner, helm, scale=4, size=(110, 170), yaws=(-28, 152)))
    W, H = big[0].size
    ov = Image.new("RGBA", (W * 3, H * 2 + 190), (24, 22, 30, 255))
    for i, v in enumerate(big):
        ov.paste(v, ((i % 3) * W, (i // 3) * H))
    for i, v in enumerate(small):        # roughly how big it is on screen in game
        ov.paste(v, (20 + i * 230, H * 2 + 10))
    ov.save(os.path.join(out, "overview.png"))
    if os.path.exists(os.path.join(vt, "humanoid/netherite.png")):
        o = tex_of(Image.open(os.path.join(vt, "humanoid/netherite.png")))
        i = tex_of(Image.open(os.path.join(vt, "humanoid_leggings/netherite.png")))
        views(o, i, None, head_from_layer=True).save(os.path.join(out, "_vanilla_netherite.png"))


if __name__ == "__main__":
    main()
