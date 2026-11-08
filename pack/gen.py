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


def anim_meta(frametime=2):
    return {"animation": {"frametime": frametime, "interpolate": False}}


# --------------------------------------------------------------------------- recolor helpers
SETS = {
    "void": dict(stops=["#07030c", "#1b0a2e", "#3a1468", "#6f34c9"], accent="#e47bff", spark="#f6dcff"),
    "bone": dict(stops=["#5e5444", "#a89b7a", "#ddd2b2", "#fbf7ea"], accent="#ff2a2a", spark="#4a4234"),
    "voidbow": dict(stops=["#12061f", "#4a1d85", "#8a4be8", "#d9b3ff"], accent="#e47bff", spark="#f6dcff"),
    "draco": dict(stops=["#3d1400", "#9a3f00", "#ec8500", "#ffdc73"], accent="#ff3a1a", spark="#ffe9a8"),
    "blood": dict(stops=["#1a0003", "#5c0010", "#b3001e", "#ff4d5e"], accent="#ff1a33", spark="#ffc2c8"),
    "frost": dict(stops=["#06202e", "#1f6f94", "#6fd3f0", "#e8fbff"], accent="#9ff3ff", spark="#ffffff"),
    "shadow": dict(stops=["#050507", "#16161f", "#2e2e44", "#59597a"], accent="#8f6bff", spark="#c9b8ff"),
    "phoenix": dict(stops=["#3a0a00", "#a32800", "#ff6a00", "#ffd24d"], accent="#ffef7a", spark="#fff6c2"),
    "earth": dict(stops=["#1e1206", "#4d3013", "#8b5a2b", "#d7a65e"], accent="#7dff6a", spark="#e9ffd9"),
    "inferno": dict(stops=["#2a0500", "#8a1c00", "#ff4500", "#ffcf4a"], accent="#fff07a", spark="#ffffff"),
    "venom": dict(stops=["#0b1a00", "#2f5c00", "#55d400", "#c8ff7a"], accent="#e9ff5a", spark="#f4ffd0"),
    "soul": dict(stops=["#03161a", "#0d4f55", "#2fb9ad", "#a7fff4"], accent="#c6fff9", spark="#ffffff"),
    "storm": dict(stops=["#050d26", "#163f8f", "#4fa3ff", "#d6ecff"], accent="#fff36b", spark="#ffffff"),
    "celestial": dict(stops=["#4a3a12", "#b08a2e", "#ffe27a", "#fffbe0"], accent="#ffffff", spark="#fff6c2"),
    "night": dict(stops=["#020208", "#12122a", "#2b2b4a", "#5a5a8a"], accent="#7f7fff", spark="#c9c9ff"),
    "ender": dict(stops=["#020f0b", "#0b4d3a", "#2fd39a", "#c8ffe9"], accent="#e070ff", spark="#f3d9ff"),
    "gravity": dict(stops=["#10061f", "#3d1d7a", "#b57bff", "#efe0ff"], accent="#ffffff", spark="#f6dcff"),
    "bloodmoon": dict(stops=["#120002", "#4a000c", "#a0001e", "#ff6b7d"], accent="#ffd0d6", spark="#ffffff"),
    "ocean": dict(stops=["#021a24", "#0b5a7a", "#2fb5e8", "#cff3ff"], accent="#aef9ff", spark="#ffffff"),
    "crystal": dict(stops=["#0d2a33", "#3b8fa3", "#b4f3ff", "#ffffff"], accent="#e8fdff", spark="#ffffff"),
    "mythril": dict(stops=["#062a22", "#1f7a63", "#7fffd4", "#e9fff8"], accent="#ffffff", spark="#e9fff8"),
    "ruby": dict(stops=["#2a0010", "#7a0a2e", "#e0115f", "#ffb3cc"], accent="#ffe0ea", spark="#ffffff"),
    "sapphire": dict(stops=["#020c2a", "#0a2a7a", "#0f52ba", "#9fc2ff"], accent="#e0ecff", spark="#ffffff"),
    "emerald": dict(stops=["#03200f", "#0b6b34", "#2ecc71", "#c9ffe0"], accent="#f0fff5", spark="#ffffff"),
    "amethyst": dict(stops=["#1a0b2e", "#4b2a7a", "#9966cc", "#e6d5ff"], accent="#ffffff", spark="#f3eaff"),
    "sun": dict(stops=["#3a2000", "#a86a00", "#ffb300", "#fff2b0"], accent="#ffffff", spark="#fffbe0"),
    "abyss": dict(stops=["#02020e", "#0b0b33", "#1b1464", "#4a46b8"], accent="#7f7fff", spark="#d0d0ff"),
    "toxic": dict(stops=["#0d1a00", "#3d6b00", "#7fff00", "#e5ffb0"], accent="#f5ff7a", spark="#ffffff"),
    "obsidian": dict(stops=["#050208", "#1c1226", "#3c2a4d", "#7a5c99"], accent="#c48bff", spark="#ead6ff"),
}

# new legendaries made from recolored vanilla textures: id -> (vanilla texture, set, model parent)
RECOLORED = {
    "blood_katana": ("netherite_sword", "blood", "minecraft:item/handheld"),
    "shadow_dagger": ("netherite_sword", "shadow", "minecraft:item/handheld"),
    "frost_trident": ("trident", "frost", "minecraft:item/handheld"),
    "phoenix_elytra": ("elytra", "phoenix", "minecraft:item/generated"),
    "earth_drill": ("netherite_shovel", "earth", "minecraft:item/handheld"),
    "inferno_greatsword": ("netherite_sword", "inferno", "minecraft:item/handheld"),
    "venom_fang": ("netherite_sword", "venom", "minecraft:item/handheld"),
    "glacier_axe": ("netherite_axe", "frost", "minecraft:item/handheld"),
    "soul_reaper": ("netherite_hoe", "soul", "minecraft:item/handheld"),
    "storm_axe": ("diamond_axe", "storm", "minecraft:item/handheld"),
    "earthshaker": ("mace", "earth", "minecraft:item/handheld_mace"),
    "celestial_blade": ("golden_sword", "celestial", "minecraft:item/handheld"),
    "night_stalker": ("netherite_sword", "night", "minecraft:item/handheld"),
    "ender_blade": ("netherite_sword", "ender", "minecraft:item/handheld"),
    "gravity_mace": ("mace", "gravity", "minecraft:item/handheld_mace"),
    "blood_moon_scythe": ("netherite_hoe", "bloodmoon", "minecraft:item/handheld"),
    "hellfire_axe": ("golden_axe", "inferno", "minecraft:item/handheld"),
    "ocean_trident": ("trident", "ocean", "minecraft:item/handheld"),
    "crystal_pickaxe": ("diamond_pickaxe", "crystal", "minecraft:item/handheld"),
    "mythril_shovel": ("diamond_shovel", "mythril", "minecraft:item/handheld"),
    "ruby_saber": ("netherite_sword", "ruby", "minecraft:item/handheld"),
    "sapphire_edge": ("diamond_sword", "sapphire", "minecraft:item/handheld"),
    "emerald_cleaver": ("netherite_axe", "emerald", "minecraft:item/handheld"),
    "amethyst_rapier": ("diamond_sword", "amethyst", "minecraft:item/handheld"),
    "sunfire_blade": ("golden_sword", "sun", "minecraft:item/handheld"),
    "abyssal_reaper": ("netherite_hoe", "abyss", "minecraft:item/handheld"),
    "toxic_cleaver": ("netherite_axe", "toxic", "minecraft:item/handheld"),
    "tempest_blade": ("diamond_sword", "storm", "minecraft:item/handheld"),
    "frostbite_dagger": ("diamond_sword", "frost", "minecraft:item/handheld"),
    "dragonbone_axe": ("netherite_axe", "bone", "minecraft:item/handheld"),
    "warlord_mace": ("mace", "draco", "minecraft:item/handheld_mace"),
    "skybreaker_mace": ("mace", "celestial", "minecraft:item/handheld_mace"),
    "kraken_trident": ("trident", "abyss", "minecraft:item/handheld"),
    "starfall_trident": ("trident", "celestial", "minecraft:item/handheld"),
    "vampire_fang": ("netherite_sword", "bloodmoon", "minecraft:item/handheld"),
    "plague_scythe": ("netherite_hoe", "venom", "minecraft:item/handheld"),
    "obsidian_greatsword": ("netherite_sword", "obsidian", "minecraft:item/handheld"),
    "phantom_blade": ("diamond_sword", "shadow", "minecraft:item/handheld"),
    "thunderclap_hammer": ("mace", "storm", "minecraft:item/handheld_mace"),
    "molten_pickaxe": ("netherite_pickaxe", "inferno", "minecraft:item/handheld"),
    "titan_pickaxe": ("netherite_pickaxe", "obsidian", "minecraft:item/handheld"),
    "lumberjack_axe": ("golden_axe", "earth", "minecraft:item/handheld"),
    "gaia_hoe": ("diamond_hoe", "emerald", "minecraft:item/handheld"),
    "quake_shovel": ("netherite_shovel", "earth", "minecraft:item/handheld"),
    "spirit_sickle": ("golden_hoe", "soul", "minecraft:item/handheld"),
    "inferno_mace": ("mace", "inferno", "minecraft:item/handheld_mace"),
    "glacier_trident": ("trident", "frost", "minecraft:item/handheld"),
    "royal_blade": ("golden_sword", "sun", "minecraft:item/handheld"),
    "voidwalker_dagger": ("netherite_sword", "void", "minecraft:item/handheld"),
    "nether_king_axe": ("golden_axe", "bloodmoon", "minecraft:item/handheld"),
    "dragon_blade": ("netherite_sword", "draco", "minecraft:item/handheld"),
    "void_scythe": ("netherite_hoe", "void", "minecraft:item/handheld"),
    "bone_reaper": ("netherite_hoe", "bone", "minecraft:item/handheld"),
    "thunder_hammer": ("mace", "storm", "minecraft:item/handheld_mace"),
    "shard_pickaxe": ("netherite_pickaxe", "amethyst", "minecraft:item/handheld"),
    "lava_axe": ("netherite_axe", "inferno", "minecraft:item/handheld"),
    "inferno_staff": ("golden_hoe", "inferno", "minecraft:item/handheld"),
    "frost_staff": ("golden_hoe", "frost", "minecraft:item/handheld"),
    "storm_staff": ("golden_hoe", "storm", "minecraft:item/handheld"),
    "staff_of_life": ("golden_hoe", "emerald", "minecraft:item/handheld"),
    "void_staff": ("golden_hoe", "void", "minecraft:item/handheld"),
    "wither_staff": ("golden_hoe", "shadow", "minecraft:item/handheld"),
    "nature_staff": ("golden_hoe", "venom", "minecraft:item/handheld"),
    "wind_blade": ("diamond_sword", "celestial", "minecraft:item/handheld"),
    "meteor_hammer": ("mace", "inferno", "minecraft:item/handheld_mace"),
    "eclipse_scythe": ("netherite_hoe", "obsidian", "minecraft:item/handheld"),
    "jumper_blade": ("netherite_sword", "sapphire", "minecraft:item/handheld"),
    "solar_greatsword": ("golden_sword", "sun", "minecraft:item/handheld"),
    "berserker_axe": ("netherite_axe", "blood", "minecraft:item/handheld"),
    "guardian_blade": ("netherite_sword", "crystal", "minecraft:item/handheld"),
    "hunter_dagger": ("diamond_sword", "earth", "minecraft:item/handheld"),
    "crimson_rapier": ("netherite_sword", "ruby", "minecraft:item/handheld"),
    "thunder_spear": ("trident", "storm", "minecraft:item/handheld"),
    "executioner_axe": ("netherite_axe", "bloodmoon", "minecraft:item/handheld"),
}


def recolored_frames(name):
    tex, set_name, _ = RECOLORED[name]
    src = Image.open(os.path.join(VT, "item", tex + ".png")).convert("RGBA")
    img = recolor(src, set_name, len(name), pattern=False)
    acc = {(x, y) for y in range(16) for x in range(16) if src.getpixel((x, y))[3] > 0 and lum(src.getpixel((x, y))) > 175}
    a = hx(SETS[set_name]["accent"])
    for xy in acc:
        img.putpixel(xy, a)
    return hd_frames(img, acc, a, seed=len(name))


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
    img = armor_layer16(set_name, layer).resize((128, 64), Image.NEAREST)
    st = SETS[set_name]
    rnd = random.Random(hash((set_name, layer, 2)) & 0xffff)
    for y in range(64):
        for x in range(128):
            c = img.getpixel((x, y))
            if c[3] == 0:
                continue
            k = 1.0 + (_h(x, y, 9) - 0.5) * 0.16
            # bevel on every 2x2 source pixel block edge of the box faces
            if img.getpixel((x, y - 1))[3] == 0 if y > 0 else True:
                k += 0.18
            c = bright(c, k)
            if set_name == "void" and rnd.random() < 0.012:
                c = hx(st["spark"])
            if set_name == "draco" and (x + y) % 6 == 0 and y % 2 == 0:
                c = mix(c, hx("#ffe9a8"), 0.25)
            if set_name == "bone" and rnd.random() < 0.01:
                c = mix(c, hx("#4a4234"), 0.6)
            img.putpixel((x, y), c)
    return img


def armor_layer16(set_name, layer):
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
    acc = {p for p in ARMOR_ACCENTS[piece] if base.getpixel(p)[3] > 0}
    return hd_frames(base, acc, hx(SETS[set_name]["accent"]), seed=hash((set_name, piece)) & 0xff)


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
        strings = {(x, y) for y in range(16) for x in range(16)
                   if img.getpixel((x, y))[:3] == hx("#e47bff")[:3]}
        out[n] = hd_frames(img, strings, hx("#e47bff"), seed=len(n))
    return out




# --------------------------------------------------------------------------- HD (32x32) rendering
def _h(*a):
    return (hash(a) & 0xffff) / 0xffff


def hd_frames(base16, accent16, accent, aura=True, n=FRAMES, seed=0, outline=None):
    """base16: 16x16 RGBA, accent16: set of (x,y) accent pixels. Returns 32 x (32*n) strip."""
    S = max(32, base16.size[0])
    big = base16.resize((S, S), Image.NEAREST)
    filled = lambda x, y: 0 <= x < S and 0 <= y < S and big.getpixel((x, y))[3] > 0
    if base16.size[0] == S:
        acc_big = set(accent16)          # already 32x32 (procedural shapes)
    else:
        acc_big = {(x * 2 + i, y * 2 + j) for (x, y) in accent16 for i in (0, 1) for j in (0, 1)}
    # distance to shape for aura
    dist = {}
    for y in range(S):
        for x in range(S):
            if filled(x, y):
                continue
            d = min((abs(dx) + abs(dy) for dx in range(-3, 4) for dy in range(-3, 4) if filled(x + dx, y + dy)), default=9)
            if d <= 3:
                dist[(x, y)] = d
    rnd = random.Random(seed)
    sparks = [(rnd.randrange(n), rnd.choice([(x, y) for y in range(S) for x in range(S) if filled(x, y)])) for _ in range(5)]
    strip = Image.new("RGBA", (S, S * n), (0, 0, 0, 0))
    for f in range(n):
        ph = 0.5 + 0.5 * math.sin(2 * math.pi * f / n)
        band = f * 4 - 6
        fr = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        for y in range(S):
            for x in range(S):
                c = big.getpixel((x, y))
                if c[3] == 0:
                    d = dist.get((x, y))
                    if aura and d is not None:
                        a = int((70, 38, 16)[d - 1] * (0.55 + 0.45 * ph))
                        fr.putpixel((x, y), accent[:3] + (a,))
                    continue
                is_out = outline is not None and c[:3] == outline[:3]
                if (x, y) in acc_big:
                    c = bright(accent, 0.75 + 0.4 * ph)
                    # hot core
                    if (x + y + f) % 4 == 0:
                        c = mix(c, (255, 255, 255, 255), 0.4)
                elif not is_out:
                    k = 1.0 + (_h(x, y, seed) - 0.5) * 0.14
                    if not filled(x - 1, y) or not filled(x, y - 1):
                        k += 0.22
                    elif not filled(x + 1, y) or not filled(x, y + 1):
                        k -= 0.22
                    c = bright(c, k)
                    if abs((x + (S - 1 - y)) - band) <= 1:
                        c = mix(c, (255, 255, 255, 255), 0.5)
                fr.putpixel((x, y), c)
        for (sf, (sx, sy)) in sparks:
            age = (f - sf) % n
            if age < 3:
                col = (255, 255, 255, 255) if age == 0 else mix(accent, (255, 255, 255, 255), 0.5)
                for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1))[: 5 if age == 1 else 1]:
                    if 0 <= sx + dx < S and 0 <= sy + dy < S:
                        fr.putpixel((sx + dx, sy + dy), col)
        strip.paste(fr, (0, S * f))
    return strip


def sprite_frames(name):
    rows = SPRITES[name]
    assert len(rows) == 16 and all(len(r) == 16 for r in rows), name
    pal = {k: hx(v) for k, v in PAL[name].items()}
    base = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    acc = set()
    for y in range(16):
        for x in range(16):
            ch = rows[y][x]
            if ch == ".":
                if any(0 <= x + dx < 16 and 0 <= y + dy < 16 and rows[y + dy][x + dx] != "."
                       for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    base.putpixel((x, y), pal["o"])
                continue
            base.putpixel((x, y), pal[ch])
            if ch == "A":
                acc.add((x, y))
    return hd_frames(base, acc, pal["A"], seed=hash(name) & 0xff, outline=pal["o"])


def shape_frames(name):
    """Unique lit, bevelled 64x64 legendary from shapes.py + hq.py."""
    import hq
    return hq.frames(name, SETS[RECOLORED[name][1]], seed=len(name))


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
        if name in RECOLORED:
            continue  # drawn as a smooth shape below
        save(sprite_frames(name), f"assets/{NS}/textures/item/{name}.png")
        wjson(anim_meta(), f"assets/{NS}/textures/item/{name}.png.mcmeta")
        parent = "minecraft:item/handheld_mace" if name == "thunder_hammer" else "minecraft:item/handheld"
        item_model(name, parent)
        item_def(name)

    import shapes
    for name, (_, _, parent) in RECOLORED.items():
        frames = shape_frames(name) if name in shapes.SHAPES else recolored_frames(name)
        save(frames, f"assets/{NS}/textures/item/{name}.png")
        wjson(anim_meta(), f"assets/{NS}/textures/item/{name}.png.mcmeta")
        item_model(name, parent)
        item_def(name)

    # void bow (keeps vanilla pulling animation)
    for n, img in bow_textures().items():
        tex = "void_" + n
        save(img, f"assets/{NS}/textures/item/{tex}.png")
        wjson(anim_meta(), f"assets/{NS}/textures/item/{tex}.png.mcmeta")
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
    for s in ("void", "bone", "draco", "frost", "inferno", "storm"):
        import armor
        for lay in ("humanoid", "humanoid_leggings"):
            save(armor.worn(s, lay, SETS[s], os.path.join(VT, "entity/equipment", lay, "netherite.png")),
                 f"assets/{NS}/textures/entity/equipment/{lay}/{s}.png")
        wjson({"layers": {"humanoid": [{"texture": f"{NS}:{s}"}],
                          "humanoid_leggings": [{"texture": f"{NS}:{s}"}]}},
              f"assets/{NS}/equipment/{s}.json")
        for piece in ("helmet", "chestplate", "leggings", "boots"):
            name = f"{s}_{piece}"
            save(armor.icon(s, piece, SETS[s]), f"assets/{NS}/textures/item/{name}.png")
            wjson(anim_meta(3), f"assets/{NS}/textures/item/{name}.png.mcmeta")
            item_model(name, "minecraft:item/generated")
            if piece == "helmet":
                # 3D helmet when worn, flat icon everywhere else
                save(armor.helmet_texture(s, SETS[s]), f"assets/{NS}/textures/item/{name}_3d.png")
                wjson(armor.helmet_model(s, f"{NS}:item/{name}_3d"), f"assets/{NS}/models/item/{name}_3d.json")
                item_def(name, {"type": "minecraft:select", "property": "minecraft:display_context",
                                "cases": [{"when": "head", "model": {"type": "minecraft:model", "model": f"{NS}:item/{name}_3d"}}],
                                "fallback": {"type": "minecraft:model", "model": f"{NS}:item/{name}"}})
            else:
                item_def(name)

    # pack icon
    icon = shape_frames("void_scythe").crop((0, 0, 64, 64)).resize((128, 128), Image.NEAREST)
    bg = Image.new("RGBA", (128, 128), hx("#120a1c"))
    bg.alpha_composite(icon)
    bg.save(os.path.join(OUT, "pack.png"))


if __name__ == "__main__":
    main()
