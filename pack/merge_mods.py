#!/usr/bin/env python3
"""Merge the textures/models of server-side content mods (datapack-style mods that ship a resource pack) into the
Dracos pack without breaking each other:
  - new files are copied
  - atlases/*.json: the "sources" lists are combined
  - items/*.json for the same vanilla item: nested, so every mod's custom_model_data cases keep working
  - lang files: merged
usage: merge_mods.py <pack_dir> <mod.jar>...
"""
import json, os, sys, zipfile

PACK = sys.argv[1]


def load(b):
    return json.loads(b.decode("utf-8-sig"))


def plain_fallback(m):
    fb = m.get("fallback")
    return isinstance(fb, dict) and fb.get("type", "").endswith("model") and "fallback" not in fb


for jar in sys.argv[2:]:
    z = zipfile.ZipFile(jar)
    added = merged = 0
    for name in z.namelist():
        if not name.startswith("assets/") or name.endswith("/"):
            continue
        out = os.path.join(PACK, name)
        data = z.read(name)
        if not os.path.exists(out):
            os.makedirs(os.path.dirname(out), exist_ok=True)
            open(out, "wb").write(data)
            added += 1
            continue
        try:
            if "/atlases/" in name:
                a, b = json.load(open(out, encoding="utf-8-sig")), load(data)
                srcs = a.get("sources", [])
                for s in b.get("sources", []):
                    if s not in srcs:
                        srcs.append(s)
                a["sources"] = srcs
                json.dump(a, open(out, "w"), indent=1)
                merged += 1
            elif "/items/" in name and name.endswith(".json"):
                old, new = json.load(open(out, encoding="utf-8-sig")), load(data)
                if plain_fallback(new.get("model", {})):
                    new["model"]["fallback"] = old["model"]       # chain: new mod's cases, then the older ones
                    json.dump(new, open(out, "w"), indent=1)
                    merged += 1
                elif plain_fallback(old.get("model", {})):
                    old["model"]["fallback"] = new["model"]
                    json.dump(old, open(out, "w"), indent=1)
                    merged += 1
            elif "/lang/" in name and name.endswith(".json"):
                a = json.load(open(out, encoding="utf-8-sig"))
                for k, v in load(data).items():
                    a.setdefault(k, v)
                json.dump(a, open(out, "w"), indent=1, ensure_ascii=False)
                merged += 1
        except Exception as e:
            print("could not merge", name, e)
    print(f"{os.path.basename(jar)}: {added} files added, {merged} merged")
