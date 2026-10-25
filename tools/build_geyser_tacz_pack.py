"""Build matching Geyser mappings and NetEase packs from the supplied TACZ archive.

Usage: python tools/build_geyser_tacz_pack.py --source E:/0-Self/Download/TACZ.zip
       --geyser D:/WorkSpace/IDEA/Java/Geyser_netease_1.21
Outputs stay in this repository; installing them is a separate explicit operation.
"""
import argparse
import collections
import json
import pathlib
import re
import struct
import sys
import zipfile

sys.dont_write_bytecode = True
from validate_bjd_pack import PACK, ROOT, read

CARRIERS = {"gun": "minecraft:stick", "ammo": "minecraft:paper",
            "attachment": "minecraft:flint", "box": "minecraft:chest"}
DIRECTORIES = {"gun": "guns", "ammo": "ammo", "attachment": "attachments"}
BOX_NAMES = {"tacz:ammo_box": "铁弹药箱", "tacz:gold_ammo_box": "金弹药箱", "tacz:diamond_ammo_box": "钻石弹药箱"}
ZIP_TIME = (1980, 1, 1, 0, 0, 0)


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def png_size(data):
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "Expected PNG icon"
    return struct.unpack(">II", data[16:24])


def mapping_name(identifier):
    return identifier.replace(":", "_")


def remap_item_references(value, aliases):
    """Change only item identifiers; sound, particle, entity and geometry IDs keep their names."""
    if isinstance(value, dict):
        return {aliases.get(key, key): remap_item_references(child, aliases) for key, child in value.items()}
    if isinstance(value, list):
        return [remap_item_references(child, aliases) for child in value]
    if isinstance(value, str):
        if value in aliases:
            return aliases[value]
        return remap_quoted_item_ids(value, aliases)
    return value


def remap_quoted_item_ids(text, aliases):
    # Also covers Python configuration keys and item IDs inside quoted Molang expressions.
    pattern = r"(['\"])(" + "|".join(re.escape(key) for key in sorted(aliases, key=len, reverse=True)) + r")\1"
    return re.sub(pattern, lambda match: match[1] + aliases[match[2]] + match[1], text)


def write_zip(path, files):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=pathlib.Path, required=True)
    parser.add_argument("--geyser", type=pathlib.Path, required=True)
    args = parser.parse_args()
    defaults = read(ROOT / "paper/src/main/resources/default-modeldata.json")
    output = ROOT / "build/distributions/geyser"
    output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.source) as archive:
        packs = {root: {name[len(root) + 1:]: archive.read(name) for name in archive.namelist()
                        if name.startswith(root + "/") and not name.endswith("/") and not name.endswith(".pyc")}
                 for root in ("tacz_resource_pack", "tacz_behavior_pack")}
    rp, bp = packs["tacz_resource_pack"], packs["tacz_behavior_pack"]
    # Read source definitions only to recover icon names and item aliases. Geyser sends
    # the actual item definitions/components; neither output pack should register them again.
    source_items = {}
    aliases = {}
    for files, directory in ((rp, "netease_items_res/"), (bp, "netease_items_beh/")):
        for filename in list(files):
            if not filename.startswith(directory):
                continue
            data = files.pop(filename)
            if filename.endswith(".json"):
                item = json.loads(data.decode("utf-8-sig"))["minecraft:item"]
                identifier = item["description"]["identifier"]
                aliases[identifier] = "heypixel:" + mapping_name(identifier)
                if directory == "netease_items_res/":
                    source_items[identifier] = item
    atlas = json.loads(rp["textures/item_texture.json"].decode("utf-8-sig"))
    translations = read(PACK / "assets/tacz/lang/zh_cn.json")
    translations.update(read(PACK / "assets/bujidao/lang/zh_cn.json"))
    with zipfile.ZipFile(args.geyser / "run/packs/bjd-server.zip") as archive:
        # Reuse the installed pack's generic box icon; the source TACZ archive has no box icon.
        box_icon = archive.read("bjd-server/resource_packs/Tarkov_RP/textures/tarkov/item/box.png")
    mapping = {"format_version": 1, "items": collections.OrderedDict()}
    rows = []
    for kind, items in defaults.items():
        entries = mapping["items"].setdefault(CARRIERS[kind], [])
        for identifier, modeldata in items.items():
            namespace, name = identifier.split(":", 1)
            aliases[identifier] = "heypixel:" + mapping_name(identifier)
            index = read(PACK / f"data/{namespace}/index/{DIRECTORIES[kind]}/{name}.json") if kind != "box" else {}
            display_name = translations.get(index.get("name"), BOX_NAMES.get(identifier, name))
            if identifier in source_items:
                icon = source_items[identifier]["components"]["minecraft:icon"]
                texture_path = atlas["texture_data"][icon]["textures"]
                icon_data = rp[texture_path + ".png"]
            else:
                icon = "tacz_" + kind + "_" + identifier.replace(":", "_")
                texture_path = "textures/tacz/icons/" + icon
                if kind == "box":
                    icon_data = box_icon
                else:
                    display_id = index["display"].split(":", 1)
                    display = read(PACK / f"assets/{display_id[0]}/display/{DIRECTORIES[kind]}/{display_id[1]}.json")
                    slot_namespace, slot_path = display["slot"].split(":", 1)
                    icon_data = (PACK / f"assets/{slot_namespace}/textures/{slot_path}.png").read_bytes()
                rp[texture_path + ".png"] = icon_data
                atlas["texture_data"][icon] = {"textures": texture_path}
            width, height = png_size(icon_data)
            entries.append({"name": mapping_name(identifier), "custom_model_data": modeldata, "display_name": display_name,
                            "icon": icon, "texture_size": 1 << (max(width, height) - 1).bit_length(),
                            "display_handheld": kind == "gun", "allow_offhand": True})
            rows.append((kind, identifier, modeldata, CARRIERS[kind], icon, "heypixel:" + mapping_name(identifier)))
    rp["textures/item_texture.json"] = encoded(atlas)
    # Match stock Geyser's heypixel:<name> registration without modifying Geyser code.
    for files in (rp, bp):
        for name, data in list(files.items()):
            if name.endswith(".json"):
                files[name] = encoded(remap_item_references(json.loads(data.decode("utf-8-sig")), aliases))
            elif name.endswith(".py"):
                files[name] = remap_quoted_item_ids(data.decode("utf-8-sig"), aliases).encode("utf-8")
    for carrier, entries in mapping["items"].items():
        for entry in entries:
            assert ":" not in entry["name"], entry
            assert atlas["texture_data"][entry["icon"]]["textures"] + ".png" in rp, entry
            attachable = "attachables/" + entry["name"] + ".json"
            if attachable in rp:
                description = json.loads(rp[attachable])["minecraft:attachable"]["description"]
                assert description["identifier"] == "heypixel:" + entry["name"], attachable
    assert not any(name.startswith("netease_items_res/") for name in rp)
    assert not any(name.startswith("netease_items_beh/") for name in bp)
    # Bump resource versions while retaining their UUIDs and existing behavior/scripts.
    for files in (rp, bp):
        manifest = json.loads(files["pack_manifest.json"].decode("utf-8-sig"))
        manifest["header"]["version"] = [0, 0, 4]
        for module in manifest["modules"]:
            module["version"] = [0, 0, 4]
        files["pack_manifest.json"] = encoded(manifest)
    assert len(rows) == 29 and len({row[2] for row in rows}) == 29
    for existing in (args.geyser / "run/custom_mappings").glob("*.json"):
        if existing.name == "tacz.json":
            continue
        data = read(existing)
        for carrier, entries in data.get("items", {}).items():
            for entry in entries:
                assert not any(carrier == row[3] and entry.get("custom_model_data") == row[2] for row in rows), existing
                assert entry.get("name") not in {mapping_name(row[1]) for row in rows}, existing
    source_mapping = ROOT / "integrations/geyser/tacz.json"
    source_mapping.parent.mkdir(parents=True, exist_ok=True)
    source_mapping.write_bytes(encoded(mapping))
    (output / "tacz.json").write_bytes(encoded(mapping))
    (output / "modeldata.tsv").write_text("kind\tid\tmodeldata\tcarrier\ticon\tbedrock_id\n" +
                                        "\n".join("\t".join(map(str, row)) for row in rows) + "\n", encoding="utf-8")
    write_zip(output / "TACZ-resource.zip", rp)
    write_zip(output / "TACZ-behavior.zip", bp)
    print(f"Validated {len(rows)} mappings, icons, textures, attachables and carrier/CMD collisions; duplicate item definitions omitted.")
    print(output)


if __name__ == "__main__":
    main()
