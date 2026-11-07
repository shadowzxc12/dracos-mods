"""Procedural 32x32 weapon/tool silhouettes so every legendary has its own look (not a recoloured vanilla item).

Each item is drawn in a local frame along the diagonal (handle bottom-left -> tip top-right):
  u = distance along the item, v = sideways offset (+v = towards bottom-right).
Parts are simple functions of (u, v) -> material char:
  '4' light, '3' mid, '2' dark (main material), 'A' glowing accent,
  'g'/'G' guard metal, 'h'/'H' handle wrap.
"""
import math

S = 32
O = (2.5, S - 2.5)                 # handle end (bottom-left)
T = (S - 2.5, 2.5)                 # tip (top-right)
L = math.hypot(T[0] - O[0], T[1] - O[1])
D = ((T[0] - O[0]) / L, (T[1] - O[1]) / L)
N = (-D[1], D[0])                  # +v = towards bottom-right


def uv(x, y):
    px, py = x + 0.5 - O[0], y + 0.5 - O[1]
    return px * D[0] + py * D[1], px * N[0] + py * N[1]


def shade(v, c, w):
    k = (v - c) / max(w, 1e-6)
    if k < -0.45:
        return "4"
    if k > 0.45:
        return "2"
    return "3"


# ------------------------------------------------------------------ parts
def handle(u, v, u0, u1, w=1.1, wrap=True):
    if u0 <= u <= u1 and abs(v) <= w:
        if wrap and int((u - u0) / 1.6) % 2 == 0:
            return "H"
        return "h"
    return None


def pommel(u, v, at, r=1.6, ch="g", gem=False):
    if (u - at) ** 2 + v ** 2 <= r * r:
        if gem and (u - at) ** 2 + v ** 2 <= (r * 0.5) ** 2:
            return "A"
        return ch
    return None


def guard(u, v, at, half, thick=1.2, curve=0.0, gem=False):
    du = u - at - curve * (v / max(half, 1)) ** 2
    if 0 <= du <= thick and abs(v) <= half:
        if gem and abs(v) < 1.1:
            return "A"
        return "G" if du > thick * 0.5 else "g"
    return None


def blade(u, v, u0, u1, width, center=lambda t: 0.0, fuller=False, serr=0, wave=0.0, tip=0.25, edge_glow=False):
    """width(t) and center(t) take t in 0..1 along the blade."""
    if not (u0 <= u <= u1):
        return None
    t = (u - u0) / (u1 - u0)
    w = width(t) * 1.75
    if t > 1 - tip:                                        # taper to a point
        w *= max(0.0, (1 - t) / tip)
    c = center(t) + (wave * math.sin(t * math.pi * 5) if wave else 0.0)
    if serr and v > c and int(u * 1.2) % serr == 0:        # notches on one edge
        w *= 0.55
    if abs(v - c) > w:
        return None
    if fuller and abs(v - c) < max(0.55, w * 0.18) and t < 0.85:
        return "A"
    if edge_glow and (v - c) > w - 0.9:
        return "A"
    return shade(v, c, w)


def disc(u, v, cu, cv, r, ch=None):
    d = math.hypot(u - cu, v - cv)
    if d <= r:
        return ch or shade(v, cv, r)
    return None


# ------------------------------------------------------------------ archetypes
def sword(p):
    hl = p.get("handle", 7.5)
    g = p.get("guard", 4.5) * 1.35
    def f(u, v):
        return (blade(u, v, hl + 1.2, L - 0.5, p.get("width", lambda t: 1.9), p.get("center", lambda t: 0.0),
                      fuller=p.get("fuller", False), serr=p.get("serr", 0), wave=p.get("wave", 0.0),
                      tip=p.get("tip", 0.25), edge_glow=p.get("edge_glow", False))
                or guard(u, v, hl, g, thick=1.8, curve=p.get("gcurve", 0.0) * 1.4, gem=p.get("ggem", True))
                or handle(u, v, 1.6, hl, w=1.35)
                or pommel(u, v, 1.6, p.get("pommel", 1.5) * 1.35, gem=p.get("pgem", True)))
    return f


def axe(p):
    top = L - 2.0
    def head(u, v):
        kind = p.get("kind", "crescent")
        hu0, hu1 = top - p.get("len", 13.0), top
        if not (hu0 - 1 <= u <= hu1 + 1):
            return None
        t = (u - hu0) / (hu1 - hu0)
        if kind == "crescent":            # one big curved bit on the +v side
            r = 4.0 + 6.5 * math.sin(t * math.pi)
            if 0 <= t <= 1 and 0.9 < v < r:
                return "A" if v > r - 1.1 else shade(v, r / 2, r / 2)
        if kind == "double":              # bits on both sides
            r = 3.6 + 5.2 * math.sin(t * math.pi)
            if 0 <= t <= 1 and 0.9 < abs(v) < r:
                return "A" if abs(v) > r - 1.1 else shade(abs(v), r / 2, r / 2)
        if kind == "cleaver":             # square heavy blade
            if 0.1 <= t <= 1 and 0.9 < v < 9.0:
                return "A" if v > 7.9 else shade(v, 4.8, 4.2)
        if kind == "bearded":             # long lower beard
            r = 3.5 + 7.0 * (t ** 1.4)
            if 0 <= t <= 1 and 0.9 < v < r:
                return "A" if v > r - 1.1 else shade(v, r / 2, r / 2)
        return None
    def f(u, v):
        h = head(u, v)
        if h:
            return h
        if p.get("spike") and top - 1 <= u <= top + 2 and abs(v) < (top + 2 - u) * 0.8:
            return "3"
        if p.get("back") and top - 9 <= u <= top - 3 and -5.5 < v < -0.9:
            return "A" if v < -4.6 else "2"
        return handle(u, v, 1.2, top, w=1.3, wrap=p.get("wrap", True)) or pommel(u, v, 1.4, 1.7, gem=p.get("pgem", False))
    return f


def scythe(p):
    top = L - 3.5
    def f(u, v):
        # long curved blade sweeping back towards -u on the -v side
        cu, cv = top, 0.0
        if u >= top - p.get("reach", 19) and u <= top + 2:
            k = (top + 1.5 - u) / p.get("reach", 19)
            if 0 <= k <= 1:
                inner = -1.6 - 10.5 * math.sin(k * math.pi * 0.9) * p.get("curve", 1.0)
                outer = inner - (4.2 * (1 - k) + 0.9)
                if outer < v < inner:
                    if v < outer + 1.1:
                        return "A"
                    return shade(v, (inner + outer) / 2, (inner - outer) / 2)
        if p.get("double") and top - 9 <= u <= top + 1 and 1.2 < v < 1.2 + 5.5 * (top + 1 - u) / 10:
            return "A" if v > 0.2 + 5.5 * (top + 1 - u) / 10 else "2"
        return (disc(u, v, top + 0.4, 0, 2.0, "g") or handle(u, v, 1.2, top, w=1.25, wrap=p.get("wrap", True))
                or pommel(u, v, 1.4, 1.7, gem=True))
    return f


def mace(p):
    top = L - 6.5
    def f(u, v):
        kind = p.get("kind", "spiked")
        if kind == "spiked":
            if disc(u, v, top, 0, 5.4):
                return "A" if math.hypot(u - top, v) < 1.8 else shade(v, 0, 5.4)
            ang = math.atan2(v, u - top)
            r = math.hypot(u - top, v)
            if 5.4 <= r <= 8.6 and abs((ang * 8 / (2 * math.pi)) % 1 - 0.5) < 0.2 * (8.6 - r) / 3.2:
                return "4"
        if kind == "hammer":
            if top - 4.5 <= u <= top + 4.5 and abs(v) <= 8.2:
                if abs(v) > 7.0:
                    return "A"
                return shade(u, top, 4.5)
        if kind == "flanged":
            if top - 6 <= u <= top + 5:
                for k in range(-2, 3):
                    if abs(v - k * 2.7) < 1.1 and abs(u - top) < 6 - abs(k) * 1.2:
                        return "A" if k == 0 else "3"
        if kind == "star":
            ang = math.atan2(v, u - top)
            r = math.hypot(u - top, v)
            rr = 4.6 + 4.0 * abs(math.cos(ang * 2.5))
            if r <= rr:
                return "A" if r < 2.2 else shade(v, 0, rr)
        return handle(u, v, 1.2, top - 3, w=1.4) or pommel(u, v, 1.4, 2.0, gem=True)
    return f


def trident(p):
    top = L - 0.5
    def f(u, v):
        prongs = p.get("prongs", 3)
        base = top - 11
        if base - 2 <= u <= base and abs(v) <= 5.8:
            return "g" if abs(v) < 5 else "A"
        offs = {3: (-4.6, 0.0, 4.6), 2: (-3.0, 3.0), 1: (0.0,)}[prongs]
        for o in offs:
            tipu = top if o == 0 else top - 2.5
            if base <= u <= tipu and abs(v - o) <= 1.4 * max(0.25, (tipu - u) / 10 + 0.5):
                if p.get("hooks") and o != 0 and tipu - 3.2 < u < tipu - 0.8 and abs(v - o) > 0.3:
                    return "A"
                return "A" if abs(v - o) < 0.5 else shade(v, o, 1.4)
        if prongs == 1 and base <= u <= top and abs(v) <= 4.0 * (top - u) / 11:
            return "A" if abs(v) < 0.6 else shade(v, 0, 4.0)
        return handle(u, v, 0.8, base - 2, w=1.15, wrap=p.get("wrap", False)) or (
            "A" if (int(u) % 5 == 0 and abs(v) <= 1.15 and u < base - 2) else None)
    return f


def pickaxe(p):
    top = L - 6
    def f(u, v):
        kind = p.get("kind", "pick")
        # head crosses the handle (perpendicular), curved back
        if kind in ("pick", "crystal"):
            for side in (-1, 1):
                for s in range(0, 90):
                    k = s / 90
                    hv = side * k * 13.0
                    hu = top + 2.5 - 6.0 * k * k
                    w = 2.4 * (1 - k) + 0.6
                    if (u - hu) ** 2 + (v - hv) ** 2 <= w * w:
                        if kind == "crystal" and k > 0.55:
                            return "A"
                        return "A" if k > 0.85 else shade(u, hu, w)
        if kind == "drill":
            if top - 9 <= u <= top + 5.5:
                w = 5.2 * (top + 5.5 - u) / 14.5
                if abs(v) <= w:
                    return "A" if int((u + v) * 1.1) % 3 == 0 else shade(v, 0, w)
        return handle(u, v, 1.2, top + 1, w=1.3) or pommel(u, v, 1.4, 1.7, gem=p.get("pgem", False))
    return f


def shovel(p):
    top = L - 10
    def f(u, v):
        if top <= u <= top + 10:
            t = (u - top) / 10
            w = 5.2 * math.sin(math.pi * (0.25 + 0.75 * t)) if p.get("kind", "spade") == "spade" else 4.8 * (1 - t) + 0.8
            if abs(v) <= w:
                return "A" if t > 0.8 or abs(v) > w - 0.6 else shade(v, 0, w)
        return handle(u, v, 1.2, top, w=1.3) or guard(u, v, top - 1.8, 3.4, thick=1.6)
    return f


def hoe(p):
    top = L - 2.5
    def f(u, v):
        if top - 8.5 <= u <= top + 1.5 and -8 <= v <= 1.2:
            k = (top + 1.5 - u) / 10
            edge = -8 + 5.0 * k
            if v >= edge and (u >= top - 2.0 or v <= -3.4):
                return "A" if v < edge + 1.1 else shade(v, -4, 4)
        return handle(u, v, 1.2, top, w=1.25) or pommel(u, v, 1.4, 1.7, gem=True)
    return f


def staff(p):
    top = L - 7.5
    kind = p.get("kind", "orb")
    def f(u, v):
        if kind == "orb":
            if disc(u, v, top, 0, 6.0):
                r = math.hypot(u - top, v)
                return "A" if r < 3.3 else ("4" if v < -2.0 else "3")
            ang = math.atan2(v, u - top)
            r = math.hypot(u - top, v)
            if 6.0 < r < 7.6 and (ang % (math.pi / 2)) < 0.5:            # cage prongs
                return "g"
        if kind == "crystal":
            if top - 5.5 <= u <= top + 7.5 and abs(v) <= 4.6 * (1 - abs(u - top - 1.0) / 6.6):
                return "A" if abs(v) < 1.4 else shade(v, 0, 4.4)
            if top - 7.5 <= u <= top - 5.0 and abs(v) <= 4.6:
                return "g"
        if kind == "flame":
            t = (u - (top - 4)) / 12
            if 0 <= t <= 1 and abs(v) <= 5.2 * math.sin(math.pi * t) * (1 - 0.35 * t):
                return "A" if abs(v) < 1.9 * (1 - t) + 0.4 else ("4" if t > 0.55 else "3")
            if top - 6 <= u <= top - 4 and abs(v) <= 4.2:
                return "g"
        if kind == "skull":
            if disc(u, v, top, 0, 5.6):
                if (abs(v + 2.3) < 1.2 or abs(v - 2.3) < 1.2) and abs(u - top - 0.9) < 1.2:
                    return "A"
                return "4" if u > top - 2.0 else "3"
        if kind == "leaf":
            for side in (-1, 1):
                cu, cv = top + 1.0, side * 3.6
                if ((u - cu) / 7.0) ** 2 + ((v - cv) / 3.4) ** 2 <= 1:
                    return "A" if abs(v - cv) < 0.5 else "3"
        if kind == "moon":
            if disc(u, v, top, 0, 6.4) and not disc(u, v, top + 2.2, -2.2, 5.0):
                return "A" if math.hypot(u - top, v) > 5.3 else "4"
        if kind == "cross":
            if (abs(v) <= 1.6 and top - 4.5 <= u <= top + 6.5) or (abs(u - top - 1.5) <= 1.6 and abs(v) <= 5.2):
                return "A" if abs(v) < 0.7 or abs(u - top - 1.5) < 0.7 else "4"
        return handle(u, v, 0.8, top - 4, w=1.25, wrap=p.get("wrap", True)) or pommel(u, v, 1.2, 1.6, gem=True)
    return f


ARCH = {"staff": staff, "sword": sword, "axe": axe, "scythe": scythe, "mace": mace, "trident": trident,
        "pickaxe": pickaxe, "shovel": shovel, "hoe": hoe}

W = lambda a, b=None: (lambda t: a) if b is None else (lambda t: a + (b - a) * t)
CURVE = lambda amt: (lambda t: amt * math.sin(t * math.pi * 0.9))

# id -> (archetype, params)
SHAPES = {
    # page 1/2 extras that used vanilla shapes
    "blood_katana": ("sword", dict(width=W(1.3), center=CURVE(-1.6), guard=2.6, tip=0.18, edge_glow=True, pommel=1.1)),
    "shadow_dagger": ("sword", dict(handle=8, width=W(2.3, 1.2), guard=3.6, gcurve=1.5, tip=0.45)),
    "frost_trident": ("trident", dict(prongs=3, hooks=True)),
    "earth_drill": ("pickaxe", dict(kind="drill")),
    "inferno_greatsword": ("sword", dict(width=W(3.0, 2.4), guard=6, fuller=True, tip=0.2, handle=4.5, pommel=1.8)),
    "venom_fang": ("sword", dict(width=W(1.6, 0.9), center=CURVE(1.2), serr=3, guard=3.2, tip=0.35)),
    "glacier_axe": ("axe", dict(kind="double")),
    "soul_reaper": ("scythe", dict(curve=1.0)),
    "storm_axe": ("axe", dict(kind="crescent", spike=True)),
    "earthshaker": ("mace", dict(kind="hammer")),
    "celestial_blade": ("sword", dict(width=W(2.2, 1.4), guard=5.5, gcurve=-1.6, fuller=True, pommel=1.8)),
    "night_stalker": ("sword", dict(width=W(1.5), center=CURVE(-2.2), guard=2.2, tip=0.15, edge_glow=True)),
    "ender_blade": ("sword", dict(width=W(2.0), wave=0.7, guard=4.2, fuller=True)),
    "gravity_mace": ("mace", dict(kind="star")),
    "blood_moon_scythe": ("scythe", dict(curve=1.25, double=True)),
    "hellfire_axe": ("axe", dict(kind="bearded", back=True)),
    "ocean_trident": ("trident", dict(prongs=2, hooks=True, wrap=True)),
    "crystal_pickaxe": ("pickaxe", dict(kind="crystal", pgem=True)),
    "mythril_shovel": ("shovel", dict(kind="spade")),
    # page 3/4
    "ruby_saber": ("sword", dict(width=W(1.7, 1.3), center=CURVE(-1.2), guard=4.0, gcurve=1.2, edge_glow=True)),
    "sapphire_edge": ("sword", dict(width=W(2.2), guard=5.0, fuller=True, tip=0.3)),
    "emerald_cleaver": ("axe", dict(kind="cleaver")),
    "amethyst_rapier": ("sword", dict(width=W(1.0, 0.6), guard=2.6, gcurve=2.2, tip=0.4, pommel=1.8)),
    "sunfire_blade": ("sword", dict(width=W(2.0), wave=0.9, guard=5.5, gcurve=-1.2, edge_glow=True)),
    "abyssal_reaper": ("scythe", dict(curve=1.35)),
    "toxic_cleaver": ("axe", dict(kind="cleaver", spike=True)),
    "tempest_blade": ("sword", dict(width=W(1.8, 2.4), guard=4.6, fuller=True, serr=4)),
    "frostbite_dagger": ("sword", dict(handle=9, width=W(1.8, 1.0), guard=3.0, tip=0.5, edge_glow=True)),
    "dragonbone_axe": ("axe", dict(kind="bearded", spike=True, back=True)),
    "warlord_mace": ("mace", dict(kind="flanged")),
    "skybreaker_mace": ("mace", dict(kind="star")),
    "kraken_trident": ("trident", dict(prongs=3, hooks=True, wrap=True)),
    "starfall_trident": ("trident", dict(prongs=1)),
    "vampire_fang": ("sword", dict(width=W(1.4, 0.8), center=CURVE(1.6), serr=2, guard=3.0, gcurve=1.8, tip=0.4)),
    "plague_scythe": ("scythe", dict(curve=0.85, double=True)),
    "obsidian_greatsword": ("sword", dict(handle=4.2, width=W(3.3, 2.9), guard=6.5, fuller=True, tip=0.16, pommel=2.0)),
    "phantom_blade": ("sword", dict(width=W(1.6), wave=0.5, guard=3.4, gcurve=-1.0, tip=0.3)),
    "thunderclap_hammer": ("mace", dict(kind="hammer")),
    "molten_pickaxe": ("pickaxe", dict(kind="pick", pgem=True)),
    "titan_pickaxe": ("pickaxe", dict(kind="drill")),
    "lumberjack_axe": ("axe", dict(kind="double", wrap=False)),
    "gaia_hoe": ("hoe", dict()),
    "quake_shovel": ("shovel", dict(kind="point")),
    "spirit_sickle": ("scythe", dict(curve=0.7)),
    "inferno_mace": ("mace", dict(kind="spiked")),
    "glacier_trident": ("trident", dict(prongs=2)),
    "royal_blade": ("sword", dict(width=W(2.3, 1.7), guard=6.0, gcurve=-2.0, fuller=True, pommel=2.0)),
    "voidwalker_dagger": ("sword", dict(handle=8.5, width=W(2.0, 1.1), center=CURVE(-1.0), guard=3.2, tip=0.5, edge_glow=True)),
    "nether_king_axe": ("axe", dict(kind="crescent", back=True, spike=True)),
    # staffs + new weapons
    "inferno_staff": ("staff", dict(kind="flame")),
    "frost_staff": ("staff", dict(kind="crystal")),
    "storm_staff": ("staff", dict(kind="orb")),
    "staff_of_life": ("staff", dict(kind="cross")),
    "void_staff": ("staff", dict(kind="moon")),
    "wither_staff": ("staff", dict(kind="skull")),
    "nature_staff": ("staff", dict(kind="leaf", wrap=False)),
    "wind_blade": ("sword", dict(width=W(1.4, 2.0), center=CURVE(1.4), guard=3.6, gcurve=-1.4, tip=0.3, edge_glow=True)),
    "meteor_hammer": ("mace", dict(kind="spiked")),
    "eclipse_scythe": ("scythe", dict(curve=1.4, double=True)),
    "jumper_blade": ("sword", dict(width=W(1.9, 1.3), guard=5.2, gcurve=1.8, fuller=True, tip=0.28)),
    "solar_greatsword": ("sword", dict(handle=4.5, width=W(3.2, 2.6), guard=6.8, gcurve=-1.5, fuller=True, edge_glow=True, tip=0.18)),
    "berserker_axe": ("axe", dict(kind="double", spike=True)),
    "guardian_blade": ("sword", dict(width=W(2.4), guard=6.5, fuller=True, tip=0.22, pommel=2.0)),
    "hunter_dagger": ("sword", dict(handle=9.5, width=W(1.6, 1.2), center=CURVE(0.8), guard=2.6, tip=0.55)),
    "crimson_rapier": ("sword", dict(width=W(0.9, 0.55), guard=3.0, gcurve=2.6, tip=0.35, edge_glow=True, pommel=1.7)),
    "thunder_spear": ("trident", dict(prongs=1, wrap=True)),
    "executioner_axe": ("axe", dict(kind="cleaver", back=True, len=15.0)),
}


def grid(name):
    """32x32 list of strings with material chars ('.' = empty)."""
    arch, params = SHAPES[name]
    f = ARCH[arch](params)
    rows = []
    for y in range(S):
        row = ""
        for x in range(S):
            u, v = uv(x, y)
            row += f(u, v) or "."
        rows.append(row)
    return rows
