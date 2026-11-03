#!/usr/bin/env python3
"""Generates the Dracos SMP resource pack (custom legendary textures).
usage: gen.py <vanilla_ref_dir> <out_dir>"""
import json, math, os, random, sys
from PIL import Image

REF = sys.argv[1]
OUT = sys.argv[2]
NS = "dracos_core"
VT = os.path.join(REF, "assets/minecraft/textures")


def hx(c):
    c = c.lstrip("#")
    return (int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16), 255)


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)


def bright(c, f):
    return tuple(max(0, min(255, int(c[i] * f))) for i in range(3)) + (c[3],)


def save(img, rel):
    p = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    img.save(p)


def wjson(obj, rel):
    p = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w") as f:
        json.dump(obj, f, indent=2)


# --------------------------------------------------------------------------- sprites
SPRITES = {
    "dragon_blade": [
        "............AA44",
        "...........A4432",
        "..........A44322",
        ".........A4432.2",
        "........A4432...",
        ".......A4432.2..",
        "..g...A4432.....",
        "..gG.A4432.2....",
        "...GgA432.......",
        "...GrGG2........",
        "....GGgg........",
        "...hHG..g.......",
        "..hH....g.......",
        ".hH.............",
        "rh..............",
        "r...............",
    ],
    "void_scythe": [
        "................",
        "...2233334444A..",
        "..2333AAAAA44hH.",
        ".233A22....hH...",
        ".23A2.....hH....",
        ".2A2.....hH.....",
        ".2A.....hH......",
        ".22....hH.......",
        "..2...hH........",
        ".....hH.........",
        "....hH..........",
        "...hH...........",
        "..hH............",
        ".hH.............",
        "AH..............",
        "A...............",
    ],
    "bone_reaper": [
        ".............444",
        "............4432",
        "...........44322",
        "..........4432.2",
        ".........2332...",
        "........4432.2..",
        ".......4432.....",
        "..ggg.2332.2....",
        "..gGGg432.......",
        "..gAGA32........",
        "...gGGg.........",
        "...hgg..........",
        "..hH............",
        ".hH.............",
        "gh..............",
        "g...............",
    ],
    "thunder_hammer": [
        "................",
        "......444433322.",
        "......433333A32.",
        "......43333AA32.",
        "......4333AA332.",
        "......433AA3332.",
        "......433A33332.",
        "......222222222.",
        ".......hH.......",
        "......hH........",
        ".....hH.........",
        "....hH..........",
        "...hH...........",
        "..hH............",
        ".AH.............",
        "AA..............",
    ],
    "shard_pickaxe": [
        "................",
        "....4444333.....",
        "...44AA333332...",
        "....4233333A32..",
        "..........3A32..",
        ".........hH432..",
        "........hH..A32.",
        ".......hH...432.",
        "......hH....A32.",
        ".....hH.....432.",
        "....hH.......2..",
        "...hH...........",
        "..hH............",
        ".hH.............",
        "hH..............",
        "................",
    ],
    "lava_axe": [
        "................",
        "........4433....",
        ".......43AA332..",
        "......43AAAA332.",
        "......4AA2AAA32.",
        ".....hH22.2AA32.",
        "....hH.....A332.",
        "...hH.......22..",
        "..hH............",
        ".hH.............",
        "hH..............",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
}

# re-center sprites that only use the top half (axe)
SPRITES["lava_axe"] = ["................"] * 3 + SPRITES["lava_axe"][:13]

PAL = {
    "dragon_blade": dict(o="#14051f", **{"2": "#4b1a7a", "3": "#8a2be2", "4": "#e2b8ff", "A": "#ff5cf0"},
                         g="#ffb000", G="#8a5a00", h="#2a1a12", H="#5c3a22", r="#ff2050"),
    "void_scythe": dict(o="#07020d", **{"2": "#1a0a2e", "3": "#3d1670", "4": "#b38aff", "A": "#e066ff"},
                        h="#120a1c", H="#3a2752"),
    "bone_reaper": dict(o="#2b251b", **{"2": "#9e9478", "3": "#d8cfb0", "4": "#fbf6e6", "A": "#ff2a2a"},
                        g="#c9bd98", G="#6e6450", h="#4a3a2a", H="#7a6a55"),
    "thunder_hammer": dict(o="#10131f", **{"2": "#2f3a57", "3": "#5f7299", "4": "#c7d6ff", "A": "#fff35a"},
                           h="#3a2a1a", H="#6a4a2a"),
    "shard_pickaxe": dict(o="#05262f", **{"2": "#0f6f8a", "3": "#2fc4e6", "4": "#c8fbff", "A": "#ffffff"},
                          h="#2a1d14", H="#5a3e26"),
    "lava_axe": dict(o="#0a0404", **{"2": "#160b0b", "3": "#2b1d1d", "4": "#5a3a3a", "A": "#ff7a00"},
                     h="#2a1a10", H="#5a3a22"),
}

FRAMES = 16


def sprite_frames(name):
    rows = SPRITES[name]
    assert len(rows) == 16 and all(len(r) == 16 for r in rows), name
    pal = {k: hx(v) for k, v in PAL[name].items()}
    strip = Image.new("RGBA", (16, 16 * FRAMES), (0, 0, 0, 0))
    for f in range(FRAMES):
        pulse = 0.75 + 0.35 * (0.5 + 0.5 * math.sin(2 * math.pi * f / FRAMES))
        band = f * 2 - 2  # shine travels along x - y
        for y in range(16):
            for x in range(16):
                ch = rows[y][x]
                if ch == ".":
                    # outline: any filled 4-neighbour
                    if any(0 <= x + dx < 16 and 0 <= y + dy < 16 and rows[y + dy][x + dx] != "."
                           for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                        strip.putpixel((x, y + 16 * f), pal["o"])
                    continue
                c = pal[ch]
                if ch == "A":
                    c = bright(c, pulse)
                elif ch in "234" and abs((x + (15 - y)) - band) <= 1:
                    c = mix(c, (255, 255, 255, 255), 0.45)
                strip.putpixel((x, y + 16 * f), c)
    return strip


def anim_meta(frametime=2):
    return {"animation": {"frametime": frametime, "interpolate": False}}


# --------------------------------------------------------------------------- recolor helpers
SETS = {
    "void": dict(stops=["#07030c", "#1b0a2e", "#3a1468", "#6f34c9"], accent="#e47bff", spark="#f6dcff"),
    "bone": dict(stops=["#5e5444", "#a89b7a", "#ddd2b2", "#fbf7ea"], accent="#ff2a2a", spark="#4a4234"),
    "voidbow": dict(stops=["#12061f", "#4a1d85", "#8a4be8", "#d9b3ff"], accent="#e47bff", spark="#f6dcff"),
    "draco": dict(stops=["#3d1400", "#9a3f00", "#ec8500", "#ffdc73"], accent="#ff3a1a", spark="#ffe9a8"),
}


def grad(stops, t):
    t = max(0.0, min(1.0, t))
    n = len(stops) - 1
    i = min(int(t * n), n - 1)
    return mix(hx(stops[i]), hx(stops[i + 1]), t * n - i)


def lum(p):
    return 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]


def recolor(img, set_name, seed, pattern=True):
    img = img.convert("RGBA")
    w, h = img.size
    px = [img.getpixel((x, y)) for y in range(h) for x in range(w)]
    ls = [lum(p) for p in px if p[3] > 0]
    lo, hi = min(ls), max(ls)
    st = SETS[set_name]
    rnd = random.Random(seed)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    for y in range(h):
        for x in range(w):
            p = img.getpixel((x, y))
            if p[3] == 0:
                continue
            t = (lum(p) - lo) / (hi - lo + 1e-6)
            c = grad(st["stops"], t)
            if pattern:
                if set_name == "void":
                    # glowing veins on diagonals + sparkles
                    if (x * 3 + y * 5) % 11 == 0 and t > 0.25:
                        c = mix(c, hx(st["accent"]), 0.75)
                    elif rnd.random() < 0.04:
                        c = hx(st["spark"])
                elif set_name == "bone":
                    # cracks
                    if (x + 2 * y) % 13 == 0 and rnd.random() < 0.7:
                        c = mix(c, hx(st["spark"]), 0.7)
                elif set_name == "draco":
                    # dragon scales
                    if (y % 3 == 0 and (x + (y // 3) * 2) % 4 == 0):
                        c = mix(c, hx("#5a1e00"), 0.6)
                    elif rnd.random() < 0.03:
                        c = hx(st["spark"])
            out.putpixel((x, y), (c[0], c[1], c[2], p[3]))
    return out


def armor_layer(set_name, layer):
    src = Image.open(os.path.join(VT, "entity/equipment", layer, "netherite.png"))
    img = recolor(src, set_name, hash((set_name, layer)) & 0xffff)
    st = SETS[set_name]
    acc = hx(st["accent"])
    if layer == "humanoid":
        # helmet front face is at (8..16, 8..16): glowing eyes / visor
        for x in (9, 10, 13, 14):
            if img.getpixel((x, 11))[3] > 0:
                img.putpixel((x, 11), acc)
        # chest front (20..28, 20..32): core / ribs / emblem
        if set_name == "bone":
            for yy in range(21, 30, 2):
                for xx in range(20, 28):
                    if img.getpixel((xx, yy))[3] > 0 and xx not in (23, 24):
                        img.putpixel((xx, yy), hx("#5e5444"))
        for (xx, yy) in ((23, 23), (24, 23), (23, 24), (24, 24)):
            if img.getpixel((xx, yy))[3] > 0:
                img.putpixel((xx, yy), acc)
    else:
        # belt accent on leggings (body front, top rows)
        for xx in range(20, 28):
            if img.getpixel((xx, 20))[3] > 0:
                img.putpixel((xx, 20), acc)
    return img


ARMOR_ACCENTS = {
    # icon pixels to light up with the accent colour (animated)
    "helmet": [(6, 7), (9, 7)],
    "chestplate": [(7, 6), (8, 6), (7, 7), (8, 7)],
    "leggings": [(6, 2), (7, 2), (8, 2), (9, 2)],
    "boots": [(4, 11), (11, 11)],
}


def armor_icon(set_name, piece):
    src = Image.open(os.path.join(VT, "item", f"netherite_{piece}.png"))
    base = recolor(src, set_name, hash((set_name, piece)) & 0xffff)
    acc = hx(SETS[set_name]["accent"])
    strip = Image.new("RGBA", (16, 16 * FRAMES), (0, 0, 0, 0))
    for f in range(FRAMES):
        pulse = 0.7 + 0.4 * (0.5 + 0.5 * math.sin(2 * math.pi * f / FRAMES))
        fr = base.copy()
        for (x, y) in ARMOR_ACCENTS[piece]:
            if fr.getpixel((x, y))[3] > 0:
                fr.putpixel((x, y), bright(acc, pulse))
        band = f * 2 - 2
        for y in range(16):
            for x in range(16):
                p = fr.getpixel((x, y))
                if p[3] > 0 and abs((x + (15 - y)) - band) <= 0 and (x, y) not in ARMOR_ACCENTS[piece]:
                    fr.putpixel((x, y), mix(p, (255, 255, 255, 255), 0.35))
        strip.paste(fr, (0, 16 * f))
    return strip


def bow_textures():
    out = {}
    for n in ("bow", "bow_pulling_0", "bow_pulling_1", "bow_pulling_2"):
        src = Image.open(os.path.join(VT, "item", n + ".png")).convert("RGBA")
        img = recolor(src, "voidbow", 7, pattern=False)
        # string: originally light grey thin line -> bright void accent
        for y in range(16):
            for x in range(16):
                p = src.getpixel((x, y))
                if p[3] > 0 and abs(p[0] - p[1]) < 12 and abs(p[1] - p[2]) < 12 and lum(p) > 150:
                    img.putpixel((x, y), hx("#e47bff"))
        out[n] = img
    return out


# --------------------------------------------------------------------------- build
def item_model(name, parent="minecraft:item/handheld", tex=None):
    wjson({"parent": parent, "textures": {"layer0": f"{NS}:item/{tex or name}"}},
          f"assets/{NS}/models/item/{name}.json")


def item_def(name, model=None):
    wjson({"model": model or {"type": "minecraft:model", "model": f"{NS}:item/{name}"}},
          f"assets/{NS}/items/{name}.json")


def main():
    os.makedirs(OUT, exist_ok=True)
    wjson({"pack": {"description": "§6§lDracos SMP §r§7- legendary items",
                    "min_format": [75, 0], "max_format": [99, 0]}}, "pack.mcmeta")

    for name in SPRITES:
        save(sprite_frames(name), f"assets/{NS}/textures/item/{name}.png")
        wjson(anim_meta(), f"assets/{NS}/textures/item/{name}.png.mcmeta")
        parent = "minecraft:item/handheld_mace" if name == "thunder_hammer" else "minecraft:item/handheld"
        item_model(name, parent)
        item_def(name)

    # void bow (keeps vanilla pulling animation)
    for n, img in bow_textures().items():
        tex = "void_" + n
        save(img, f"assets/{NS}/textures/item/{tex}.png")
        wjson({"parent": "minecraft:item/bow", "textures": {"layer0": f"{NS}:item/{tex}"}},
              f"assets/{NS}/models/item/{tex}.json")
    m = lambda t: {"type": "minecraft:model", "model": f"{NS}:item/{t}"}
    item_def("void_bow", {
        "type": "minecraft:condition", "property": "minecraft:using_item",
        "on_false": m("void_bow"),
        "on_true": {"type": "minecraft:range_dispatch", "property": "minecraft:use_duration", "scale": 0.05,
                    "entries": [{"threshold": 0.65, "model": m("void_bow_pulling_1")},
                                {"threshold": 0.9, "model": m("void_bow_pulling_2")}],
                    "fallback": m("void_bow_pulling_0")}})

    # armor sets
    for s in ("void", "bone", "draco"):
        save(armor_layer(s, "humanoid"), f"assets/{NS}/textures/entity/equipment/humanoid/{s}.png")
        save(armor_layer(s, "humanoid_leggings"), f"assets/{NS}/textures/entity/equipment/humanoid_leggings/{s}.png")
        wjson({"layers": {"humanoid": [{"texture": f"{NS}:{s}"}],
                          "humanoid_leggings": [{"texture": f"{NS}:{s}"}]}},
              f"assets/{NS}/equipment/{s}.json")
        for piece in ("helmet", "chestplate", "leggings", "boots"):
            name = f"{s}_{piece}"
            save(armor_icon(s, piece), f"assets/{NS}/textures/item/{name}.png")
            wjson(anim_meta(3), f"assets/{NS}/textures/item/{name}.png.mcmeta")
            item_model(name, "minecraft:item/generated")
            item_def(name)

    # pack icon
    icon = sprite_frames("void_scythe").crop((0, 0, 16, 16)).resize((128, 128), Image.NEAREST)
    bg = Image.new("RGBA", (128, 128), hx("#120a1c"))
    bg.alpha_composite(icon)
    bg.save(os.path.join(OUT, "pack.png"))


if __name__ == "__main__":
    main()
