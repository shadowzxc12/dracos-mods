"""High quality legendary renderer.

Shapes from shapes.py are sampled at 3x resolution, turned into a height map (bevelled edges, a ridge
down the middle of blades, rounded handles), lit like 3D metal (diffuse + specular, light from the
top-left) and downsampled to a crisp 64x64 pixel-art texture with a dark outline. The animation adds
a pulsing glow on the accent parts, a shine sweeping along the metal and a few twinkles.
"""
import math
import random

import numpy as np
from PIL import Image
from scipy import ndimage

import shapes

R = 64          # final texture size
SS = 3          # supersampling
FRAMES = 16

METAL, ACCENT, GUARD, HANDLE = 1, 2, 3, 4
GROUP = {"1": METAL, "2": METAL, "3": METAL, "4": METAL, "5": METAL, "A": ACCENT,
         "g": GUARD, "G": GUARD, "h": HANDLE, "H": HANDLE}

GOLD = ["#2a1804", "#6b4310", "#b8801f", "#f2c552", "#fff3b8"]
LEATHER = ["#140b06", "#2e1a0e", "#4e2f1a", "#76492a", "#a26d43"]


def rgb(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=np.float64)


def ramp(cols, t):
    """Sample a colour ramp (list of hex) at t (array 0..1)."""
    c = np.stack([rgb(x) for x in cols])
    t = np.clip(t, 0, 1) * (len(cols) - 1)
    i = np.minimum(t.astype(int), len(cols) - 2)
    f = (t - i)[..., None]
    return c[i] * (1 - f) + c[i + 1] * f


def tint(cols, toward, k):
    out = []
    for c in cols:
        a = rgb(c) * (1 - k) + rgb(toward) * k
        out.append("#%02x%02x%02x" % tuple(int(v) for v in a))
    return out


def material(name):
    rows = shapes.grid(name, R * SS)
    n = R * SS
    g = np.zeros((n, n), dtype=np.int8)
    wrap = np.zeros((n, n), dtype=bool)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                g[y, x] = GROUP[ch]
                if ch in "HG":
                    wrap[y, x] = True
    return g, wrap


def lit(name, st):
    g, wrap = material(name)
    return lit_map(g, wrap, st)


def lit_map(g, wrap, st, metal_bevel=9.0, guard_cols=None):
    """g: (R*SS)^2 group map, wrap: grooves. Returns (rgb float 64x64x3, alpha bool, group map) at 64."""
    n = g.shape[0]
    solid = g > 0
    blade = (g == METAL) | (g == ACCENT)
    # ---- height map: each part bevels from its own outline
    h = np.zeros((n, n))
    d_blade = ndimage.distance_transform_edt(blade)
    h += np.where(blade, np.minimum(d_blade, metal_bevel) * 0.9 - wrap * blade * 2.0, 0)                    # blade: bevel + ridge
    d_g = ndimage.distance_transform_edt(g == GUARD)
    h += np.where(g == GUARD, 3 + np.sqrt(np.minimum(d_g, 6.0)) * 2.4 - wrap * 1.2, 0)
    d_h = ndimage.distance_transform_edt(g == HANDLE)
    h += np.where(g == HANDLE, np.sqrt(np.minimum(d_h, 5.0)) * 2.6 - wrap * 1.3, 0)
    h = ndimage.gaussian_filter(h, 1.1) * solid
    gy, gx = np.gradient(h)
    nz = np.ones_like(h)
    nx, ny = -gx * 1.3, -gy * 1.3
    ln = np.sqrt(nx * nx + ny * ny + nz * nz)
    nx, ny, nz = nx / ln, ny / ln, nz / ln
    L = np.array([-0.62, -0.62, 0.48]); L /= np.linalg.norm(L)
    H = L + np.array([0, 0, 1.0]); H /= np.linalg.norm(H)
    diff = np.clip(nx * L[0] + ny * L[1] + nz * L[2], 0, 1)
    spec = np.clip(nx * H[0] + ny * H[1] + nz * H[2], 0, 1) ** 28
    # gentle gradient along the item: darker near the grip, brighter at the tip
    yy, xx = np.mgrid[0:n, 0:n]
    along = np.clip((xx + (n - 1 - yy)) / (2 * n), 0, 1)

    stops = st["stops"]
    metal_ramp = [stops[0], stops[1], stops[2], stops[3], "#ffffff"]
    col = np.zeros((n, n, 3))
    t = 0.24 + diff * 0.66 + along * 0.1
    m = g == METAL
    col[m] = ramp(metal_ramp, t[m] * 0.92)
    col[m] += spec[m, None] * 255 * 0.85
    a = g == ACCENT
    acc = rgb(st["accent"])
    d_a = ndimage.distance_transform_edt(a)
    core = np.clip(d_a / 3.0, 0, 1)
    col[a] = acc * (0.72 + 0.3 * diff[a, None]) + (255 - acc) * (0.45 * core[a, None]) + spec[a, None] * 120
    gd = g == GUARD
    col[gd] = ramp(guard_cols or tint(GOLD, stops[2], 0.22), 0.1 + diff[gd] * 0.85) + spec[gd, None] * 200
    hd = g == HANDLE
    col[hd] = ramp(tint(LEATHER, stops[1], 0.25), 0.08 + diff[hd] * 0.8) + spec[hd, None] * 40
    # ambient occlusion where parts meet (handle under the guard etc.)
    near_other = ndimage.distance_transform_edt(~(gd | blade)) if gd.any() else None
    if near_other is not None:
        ao = np.clip(near_other / 6.0, 0.55, 1.0)
        col[hd] *= ao[hd, None]
    col = np.clip(col, 0, 255)

    # ---- downsample (box filter, colour averaged over covered samples)
    cov = solid.reshape(R, SS, R, SS).mean(axis=(1, 3))
    cs = (col * solid[..., None]).reshape(R, SS, R, SS, 3).sum(axis=(1, 3))
    cnt = solid.reshape(R, SS, R, SS).sum(axis=(1, 3))
    out = cs / np.maximum(cnt, 1)[..., None]
    alpha = cov >= 0.4
    # majority group per pixel
    grp = np.zeros((R, R), dtype=np.int8)
    best = np.zeros((R, R))
    for k in (METAL, ACCENT, GUARD, HANDLE):
        c = (g == k).reshape(R, SS, R, SS).mean(axis=(1, 3))
        better = c > best
        grp[better] = k
        best[better] = c[better]
    grp[~alpha] = 0
    return out, alpha, grp


def frames(name, st, seed=0):
    return frames_from(lit(name, st), st, seed)


def frames_from(litres, st, seed=0, n_frames=FRAMES):
    FRAMES = n_frames
    base, alpha, grp = litres
    outline_c = rgb(st["stops"][0]) * 0.35
    acc = rgb(st["accent"])
    spark = rgb(st.get("spark", "#ffffff"))
    outl = ndimage.binary_dilation(alpha, structure=np.array([[0, 1, 0], [1, 1, 1], [0, 1, 0]])) & ~alpha
    # soft aura outside the outline
    dist_out = ndimage.distance_transform_edt(~(alpha | outl))
    aura = (dist_out > 0) & (dist_out <= 2.2)
    rnd = random.Random(seed)
    metal_px = list(zip(*np.nonzero((grp == METAL) | (grp == ACCENT))))
    twinkles = [(rnd.randrange(FRAMES), metal_px[rnd.randrange(len(metal_px))]) for _ in range(4)] if metal_px else []
    yy, xx = np.mgrid[0:R, 0:R]
    diag = xx + (R - 1 - yy)
    strip = np.zeros((R * FRAMES, R, 4), dtype=np.uint8)
    for f in range(FRAMES):
        ph = 0.5 + 0.5 * math.sin(2 * math.pi * f / FRAMES)
        img = base.copy()
        a = grp == ACCENT
        img[a] = img[a] * (0.86 + 0.24 * ph)
        # shine sweep along the metal (one pass per loop, then a pause)
        band = f * 13 - 20
        shine = np.clip(1 - np.abs(diag - band) / 4.5, 0, 1) * ((grp == METAL) | (grp == GUARD))
        img = img + (255 - img) * shine[..., None] * 0.55
        rgba = np.zeros((R, R, 4))
        rgba[..., :3] = np.clip(img, 0, 255)
        rgba[..., 3] = alpha * 255
        rgba[outl, :3] = outline_c
        rgba[outl, 3] = 255
        ak = np.clip(1 - (dist_out - 1) / 1.6, 0, 1) * (0.18 + 0.14 * ph)
        rgba[aura, :3] = acc
        rgba[aura, 3] = ak[aura] * 255
        for sf, (sy, sx) in twinkles:
            age = (f - sf) % FRAMES
            if age < 3:
                r = 2 - age if age < 2 else 1
                for dy, dx in [(0, 0)] + [(0, k) for k in range(-r, r + 1)] + [(k, 0) for k in range(-r, r + 1)]:
                    y, x = sy + dy, sx + dx
                    if 0 <= y < R and 0 <= x < R and alpha[y, x]:
                        w = 1.0 if (dy, dx) == (0, 0) else 0.6
                        rgba[y, x, :3] = rgba[y, x, :3] * (1 - w) + spark * w
        strip[f * R:(f + 1) * R] = np.clip(rgba, 0, 255).astype(np.uint8)
    return Image.fromarray(strip, "RGBA")
