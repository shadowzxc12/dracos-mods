#!/usr/bin/env python3
"""Server Backpacks: show the 3D backpack model in the inventory/hand too (not just the flat icon),
and upscale the backpack textures 4x with Scale2x (smooth pixel-art edges) + subtle fabric detail.
usage: backpacks.py <pack_dir>   (run after the mod assets were copied into the pack)"""
import glob, json, os, random, sys
import numpy as np
from PIL import Image

PACK = sys.argv[1]
NS = os.path.join(PACK, "assets", "serverbackpacks")


def scale2x(a):
    h, w = a.shape[:2]
    p = np.pad(a, ((1, 1), (1, 1), (0, 0)), mode="edge")
    B, D, E, F, H = p[:-2, 1:-1], p[1:-1, :-2], p[1:-1, 1:-1], p[1:-1, 2:], p[2:, 1:-1]
    eq = lambda x, y: np.all(x == y, axis=-1)
    c = (~eq(B, H) & ~eq(D, F))[..., None]
    e0 = np.where(c & eq(D, B)[..., None], D, E)
    e1 = np.where(c & eq(B, F)[..., None], F, E)
    e2 = np.where(c & eq(D, H)[..., None], D, E)
    e3 = np.where(c & eq(H, F)[..., None], F, E)
    out = np.zeros((h * 2, w * 2, a.shape[2]), dtype=a.dtype)
    out[0::2, 0::2], out[0::2, 1::2], out[1::2, 0::2], out[1::2, 1::2] = e0, e1, e2, e3
    return out


def hd(path):
    im = np.array(Image.open(path).convert("RGBA"))
    if im.shape[0] > 64:
        return
    big = scale2x(scale2x(im)).astype(np.float64)
    h, w = big.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w]
    rnd = np.random.RandomState(len(path))
    weave = ((xx + yy) % 4 == 0) * -0.06 + ((xx - yy) % 4 == 0) * 0.04 + (rnd.rand(h, w) - 0.5) * 0.06
    big[..., :3] *= (1 + weave)[..., None]
    # thin darker stitch at the border of every flat colour region
    lum = big[..., :3] @ np.array([0.299, 0.587, 0.114])
    edge = (np.abs(np.diff(lum, axis=0, prepend=lum[:1])) > 18) | (np.abs(np.diff(lum, axis=1, prepend=lum[:, :1])) > 18)
    big[edge, :3] *= 0.85
    Image.fromarray(np.clip(big, 0, 255).astype(np.uint8), "RGBA").save(path)


DISPLAY = {
    "gui": {"rotation": [22, 205, 0], "translation": [0, 0.5, 0], "scale": [0.95, 0.95, 0.95]},
    "ground": {"rotation": [0, 180, 0], "translation": [0, 2, 0], "scale": [0.55, 0.55, 0.55]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.9, 0.9, 0.9]},
    "thirdperson_righthand": {"rotation": [0, 180, 0], "translation": [0, 1, 2], "scale": [0.6, 0.6, 0.6]},
    "thirdperson_lefthand": {"rotation": [0, 180, 0], "translation": [0, 1, 2], "scale": [0.6, 0.6, 0.6]},
    "firstperson_righthand": {"rotation": [0, 160, 0], "translation": [1, 2, 0], "scale": [0.55, 0.55, 0.55]},
    "firstperson_lefthand": {"rotation": [0, 200, 0], "translation": [1, 2, 0], "scale": [0.55, 0.55, 0.55]},
}

done = 0
for f in glob.glob(os.path.join(NS, "textures/item/model/*.png")):
    hd(f)
for f in glob.glob(os.path.join(NS, "items/*.json")):
    name = os.path.basename(f)[:-5]
    if not os.path.exists(os.path.join(NS, "models/item/model", name + ".json")):
        continue
    d = json.load(open(f))
    m = d.get("model", {})
    if m.get("type") != "minecraft:select" or "fallback" not in m:
        continue
    os.makedirs(os.path.join(NS, "models/item/gui3d"), exist_ok=True)
    json.dump({"parent": f"serverbackpacks:item/model/{name}", "display": DISPLAY},
              open(os.path.join(NS, "models/item/gui3d", name + ".json"), "w"))
    m["fallback"] = {"type": "minecraft:model", "model": f"serverbackpacks:item/gui3d/{name}"}
    json.dump(d, open(f, "w"))
    done += 1
print("3D backpacks:", done)
