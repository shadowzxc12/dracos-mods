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


def mixhex(a, b, k):
    ca, cb = rgb(a), rgb(b)
    c = ca * (1 - k) + cb * k
    return "#%02x%02x%02x" % tuple(int(v) for v in c)


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


def lit_map(g, wrap, st, metal_bevel=9.0, guard_cols=None, out_res=None, want_shade=False):
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
    # "clean" modded-weapon look: real steel with a tint of the set colour (not fully coloured plastic)
    STEEL = ["#15181e", "#3c434f", "#7d8796", "#c9d0da", "#ffffff"]
    tint_k = st.get("tint", 0.66)
    metal_ramp = [mixhex(STEEL[i], [stops[0], stops[1], stops[2], stops[3], "#ffffff"][i], tint_k) for i in range(5)]
    col = np.zeros((n, n, 3))
    t = 0.16 + diff * 0.76 + along * 0.08
    m = g == METAL
    col[m] = ramp(metal_ramp, t[m] * 0.92)
    col[m] += spec[m, None] * 255 * 0.55
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
    O = out_res or R
    F = n // O
    cov = solid.reshape(O, F, O, F).mean(axis=(1, 3))
    cs = (col * solid[..., None]).reshape(O, F, O, F, 3).sum(axis=(1, 3))
    cnt = solid.reshape(O, F, O, F).sum(axis=(1, 3))
    out = cs / np.maximum(cnt, 1)[..., None]
    alpha = cov >= 0.45
    # majority group per pixel
    grp = np.zeros((O, O), dtype=np.int8)
    best = np.zeros((O, O))
    for k in (METAL, ACCENT, GUARD, HANDLE):
        c = (g == k).reshape(O, F, O, F).mean(axis=(1, 3))
        better = c > best
        grp[better] = k
        best[better] = c[better]
    grp[~alpha] = 0
    if want_shade:
        sh = np.clip(diff * 0.85 + spec * 0.6 + along * 0.15, 0, 1.4)
        shs = (sh * solid).reshape(O, F, O, F).sum(axis=(1, 3)) / np.maximum(cnt, 1)
        return out, alpha, grp, shs
    return out, alpha, grp


def mixc(a, b, t):
    return a * (1 - t) + b * t


def pixel_art(litres, st, seed=0, n_frames=8, guard_cols=None):
    """Crisp hand-pixelled look (like classic server crate items): every part gets a small fixed palette,
    1px dark outline, light top-left edges, dark bottom-right edges, glowing accent pixels."""
    _, alpha, grp, shade = litres
    O = alpha.shape[0]
    s = [rgb(c) for c in st["stops"]]
    W = np.array([255.0, 255, 255])
    acc = rgb(st["accent"])
    gold = [rgb(c) for c in (guard_cols or tint(GOLD, st["stops"][2], 0.2))]
    leather = [rgb(c) for c in tint(LEATHER, st["stops"][1], 0.2)]
    P = {METAL: [mixc(s[0], s[1], 0.6), s[1], s[2], s[3], mixc(s[3], W, 0.6)],
         GUARD: [gold[1], gold[2], gold[3], gold[-1]],
         HANDLE: [leather[1], leather[2], leather[3]],
         ACCENT: [mixc(acc, s[1], 0.45), acc, mixc(acc, W, 0.65)]}
    outline = s[0] * 0.32
    inside = lambda y, x: 0 <= y < O and 0 <= x < O and alpha[y, x]
    idx = np.zeros((O, O), dtype=int)
    for k, pal in P.items():
        m = grp == k
        if not m.any():
            continue
        v = shade[m]
        lo, hi = np.percentile(v, 5), np.percentile(v, 95)
        idx[m] = np.clip(((v - lo) / max(hi - lo, 1e-6) * (len(pal) - 1) + 0.5).astype(int), 0, len(pal) - 1)
    # edge lighting: top-left rim bright, bottom-right rim dark (classic pixel-art shading)
    adj = idx.copy()
    for y in range(O):
        for x in range(O):
            if not alpha[y, x]:
                continue
            n = len(P[grp[y, x]])
            if not inside(y - 1, x) or not inside(y, x - 1):
                adj[y, x] = min(n - 1, idx[y, x] + 1)
            elif not inside(y + 1, x) or not inside(y, x + 1):
                adj[y, x] = max(0, idx[y, x] - 1)
    idx = adj
    acc_m = grp == ACCENT
    acc_core = ndimage.binary_erosion(acc_m)
    outl = ndimage.binary_dilation(alpha, structure=np.array([[0, 1, 0], [1, 1, 1], [0, 1, 0]])) & ~alpha
    rnd = random.Random(seed)
    shiny = [tuple(p) for p in zip(*np.nonzero((grp == METAL) & (idx >= 3)))]
    sparks = [(rnd.randrange(n_frames), shiny[rnd.randrange(len(shiny))]) for _ in range(2)] if shiny else []
    strip = np.zeros((O * n_frames, O, 4), dtype=np.uint8)
    for f in range(n_frames):
        ph = 0.5 + 0.5 * math.sin(2 * math.pi * f / n_frames)
        img = np.zeros((O, O, 4))
        for k, pal in P.items():
            m = grp == k
            for i, c in enumerate(pal):
                mm = m & (idx == i)
                img[mm, :3] = c
        # glowing accents: core pulses brighter
        img[acc_m, :3] = P[ACCENT][1] * (0.85 + 0.25 * ph)
        img[acc_core, :3] = mixc(P[ACCENT][1], P[ACCENT][2], 0.4 + 0.6 * ph)
        img[alpha, 3] = 255
        img[outl, :3] = outline
        img[outl, 3] = 255
        for sf, (sy, sx) in sparks:
            if (f - sf) % n_frames == 0:
                img[sy, sx, :3] = W
                for dy, dx in ((0, 1), (1, 0), (0, -1), (-1, 0)):
                    if inside(sy + dy, sx + dx):
                        img[sy + dy, sx + dx, :3] = mixc(img[sy + dy, sx + dx, :3], W, 0.5)
        strip[f * O:(f + 1) * O] = np.clip(img, 0, 255).astype(np.uint8)
    return Image.fromarray(strip, "RGBA")


PIX = 32   # final texture size for the pixel-art look


def frames(name, st, seed=0):
    g, wrap = material(name)
    return frames_from(lit_map(g, wrap, st), st, seed)


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
        ak = np.clip(1 - (dist_out - 1) / 1.2, 0, 1) * (0.10 + 0.08 * ph)
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


def hue_ramp(dark, mid, light, accent_shadow="#160c2c", warm="#fff1cf", n=6):
    """Pixel-art ramp with hue shifting: shadows lean cold/purple, highlights lean warm."""
    d = mixc(rgb(dark), rgb(accent_shadow), 0.35)
    l = mixc(rgb(light), rgb(warm), 0.45)
    keys = [d * 0.75, d, rgb(mid), mixc(rgb(mid), rgb(light), 0.6), l, mixc(l, np.array([255.0, 255, 255]), 0.7)]
    return keys[:n]


def pixel_art2(litres, st, seed=0, n_frames=16, guard_cols=None, use_halo=True):
    """Vivid hand-pixelled legendary (crate/server style): hue-shifted ramps, coloured selective outline,
    spine highlight, shimmer band, glowing accent with a coloured halo and sparkle stars around the item."""
    _, alpha, grp, shade = litres
    O = alpha.shape[0]
    s = st["stops"]
    W = np.array([255.0, 255, 255])
    acc = rgb(st["accent"])
    gold = guard_cols or tint(GOLD, s[2], 0.25)
    leather = tint(LEATHER, s[1], 0.3)
    P = {METAL: hue_ramp(s[0], s[2], s[3]),
         GUARD: hue_ramp(gold[1], gold[2], gold[3], warm="#fff6c0", n=5),
         HANDLE: [rgb(leather[0]), rgb(leather[1]), rgb(leather[2]), rgb(leather[3])],
         ACCENT: [mixc(acc, rgb(s[0]), 0.45), acc, mixc(acc, W, 0.45), mixc(acc, W, 0.8)]}
    inside = lambda y, x: 0 <= y < O and 0 <= x < O and alpha[y, x]
    idx = np.zeros((O, O), dtype=int)
    for k, pal in P.items():
        m = grp == k
        if not m.any():
            continue
        v = shade[m]
        lo, hi = np.percentile(v, 4), np.percentile(v, 96)
        n = len(pal) - (1 if k == METAL else 0)          # keep the top metal colour for the spine glint
        idx[m] = np.clip(((v - lo) / max(hi - lo, 1e-6) * (n - 1) + 0.5).astype(int), 0, n - 1)
    adj = idx.copy()
    for y in range(O):
        for x in range(O):
            if not alpha[y, x]:
                continue
            n = len(P[grp[y, x]])
            if not inside(y - 1, x) or not inside(y, x - 1):
                adj[y, x] = min(n - 2 if grp[y, x] == METAL else n - 1, idx[y, x] + 1)
            elif not inside(y + 1, x) or not inside(y, x + 1):
                adj[y, x] = max(0, idx[y, x] - 1)
    idx = adj
    # spine glint: the brightest interior metal pixels get the top colour
    met = (grp == METAL) & ndimage.binary_erosion(alpha)
    if met.any():
        thr = np.percentile(shade[met], 90)
        idx[met & (shade >= thr)] = len(P[METAL]) - 1
    acc_m = grp == ACCENT
    acc_core = ndimage.binary_erosion(acc_m)
    cross = np.array([[0, 1, 0], [1, 1, 1], [0, 1, 0]])
    outl = ndimage.binary_dilation(alpha, structure=cross) & ~alpha
    # selective outline: dark version of the part it touches; next to glowing parts it glows too
    out_col = np.zeros((O, O, 3))
    near_acc = ndimage.binary_dilation(acc_m, structure=np.ones((3, 3))) & outl
    for k, pal in P.items():
        m = ndimage.binary_dilation(grp == k, structure=cross) & outl
        out_col[m] = pal[0] * 0.45
    halo = ndimage.binary_dilation(outl, structure=cross) & ~alpha & ~outl
    halo_acc = halo & ndimage.binary_dilation(acc_m, structure=np.ones((5, 5)))
    rnd = random.Random(seed)
    empty = [(y, x) for y in range(2, O - 2) for x in range(2, O - 2)
             if not ndimage.binary_dilation(alpha | outl, iterations=2)[y, x]] if False else None
    occ = ndimage.binary_dilation(alpha | outl, iterations=2)
    near = ndimage.binary_dilation(alpha, iterations=6) & ~occ
    spots = [tuple(p) for p in zip(*np.nonzero(near)) if 2 <= p[0] < O - 2 and 2 <= p[1] < O - 2]
    stars = []
    for i in range(3):
        if spots:
            stars.append((i * n_frames // 3 + rnd.randrange(2), spots[rnd.randrange(len(spots))]))
    yy, xx = np.mgrid[0:O, 0:O]
    diag = xx + (O - 1 - yy)
    strip = np.zeros((O * n_frames, O, 4), dtype=np.uint8)
    for f in range(n_frames):
        ph = 0.5 + 0.5 * math.sin(2 * math.pi * f / n_frames)
        band = (f * 2 * O) // n_frames * 2 - O // 2           # shimmer sweeps once per loop
        sh = (np.abs(diag - band) <= 1) & ((grp == METAL) | (grp == GUARD))
        img = np.zeros((O, O, 4))
        for k, pal in P.items():
            m = grp == k
            ii = np.where(sh & m, np.minimum(idx + 1, len(pal) - 1), idx)
            for i, c in enumerate(pal):
                img[m & (ii == i), :3] = c
        img[acc_m, :3] = mixc(P[ACCENT][1], P[ACCENT][2], ph * 0.6)
        img[acc_core, :3] = mixc(P[ACCENT][2], P[ACCENT][3], ph)
        img[alpha, 3] = 255
        img[outl, :3] = out_col[outl]
        if use_halo:
            img[near_acc, :3] = mixc(P[ACCENT][0], P[ACCENT][1], 0.3 + 0.5 * ph)
        img[outl, 3] = 255
        if use_halo and ph > 0.55:
            img[halo_acc, :3] = P[ACCENT][0]
            img[halo_acc, 3] = 255
        for sf, (sy, sx) in stars:
            age = (f - sf) % n_frames
            if age == 0 or age == 2:
                img[sy, sx, :3] = P[ACCENT][2]; img[sy, sx, 3] = 255
            elif age == 1:
                img[sy, sx, :3] = W; img[sy, sx, 3] = 255
                for dy, dx in ((0, 1), (1, 0), (0, -1), (-1, 0)):
                    img[sy + dy, sx + dx, :3] = P[ACCENT][2]; img[sy + dy, sx + dx, 3] = 255
        strip[f * O:(f + 1) * O] = np.clip(img, 0, 255).astype(np.uint8)
    return Image.fromarray(strip, "RGBA")
