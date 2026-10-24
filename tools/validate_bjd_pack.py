"""Validate the shipped BJD pack's resource graph without starting Minecraft."""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
PACK = ROOT / "src/main/resources/assets/tacz/custom/tacz_default_gun"
EXPECTED = {"ar_01", "ar_02", "hg_01", "hg_3", "sg_01", "sg_02", "sr_01", "sr_02", "sr_03", "sr_04"}
# GunDisplayInstance.checkSounds adds these even when a display omits them.
DEFAULT_SOUNDS = {"tacz:" + name for name in (
    "dry_fire", "fire_select", "head_hit", "flesh_hit", "kill",
    "melee_bayonet/melee_bayonet_01", "melee_stock/melee_stock_01", "melee_stock/melee_stock_02")}


def read(path):
    raw = path.read_text("utf-8-sig")
    raw = re.sub(r'"(?:\\.|[^"\\])*"|//[^\n]*|/\*[\s\S]*?\*/',
                 lambda match: match[0] if match[0].startswith('"') else "", raw)
    return json.loads(raw)


def resource(identifier, kind, directory, suffix=".json"):
    namespace, name = identifier.split(":", 1)
    assert re.fullmatch(r"[a-z0-9_.-]+", namespace), identifier
    assert re.fullmatch(r"[a-z0-9_./-]+", name) and ".." not in name.split("/"), identifier
    relative = pathlib.Path(kind) / namespace / directory / (name + suffix)
    path = PACK / relative
    if not path.is_file():
        path = ROOT / "src/main/resources" / relative
    assert path.is_file(), f"Missing {identifier}: {relative}"
    return path


def documents(directory):
    result = {}
    for path in (PACK / "data").glob("*/" + directory + "/**/*.json"):
        namespace = path.relative_to(PACK / "data").parts[0]
        name = path.relative_to(PACK / "data" / namespace / directory).with_suffix("").as_posix()
        result[namespace + ":" + name] = read(path)
    return result


def sound_effects(value):
    if isinstance(value, dict):
        # Bare event names in the source animations are not custom sound resources.
        effect = value.get("effect", "")
        if isinstance(effect, str) and ":" in effect:
            yield effect
        for child in value.values():
            yield from sound_effects(child)
    elif isinstance(value, list):
        for child in value:
            yield from sound_effects(child)


def validate_catalog():
    guns, ammos, attachments = (documents("index/" + kind) for kind in ("guns", "ammo", "attachments"))
    tags = documents("tacz_tags/attachments")
    used_ammo, used_attachments, used_tags = set(), set(), set()
    sounds = set(DEFAULT_SOUNDS)

    def visit_tag(identifier):
        if identifier in used_tags:
            return
        assert identifier in tags, f"Missing attachment tag: {identifier}"
        used_tags.add(identifier)
        for entry in tags[identifier]:
            if entry.startswith("#"):
                visit_tag(entry[1:])
            else:
                used_attachments.add(entry)

    def check_display(value):
        if isinstance(value, dict):
            sounds.update(value.get("sounds", {}).values())
            for key, child in value.items():
                if key in ("model", "texture", "slot", "hud") and isinstance(child, str) and ":" in child:
                    directory, suffix = ("geo_models", ".json") if key == "model" else ("textures", ".png")
                    resource(child, "assets", directory, suffix)
                check_display(child)
        elif isinstance(value, list):
            for child in value:
                check_display(child)

    for identifier, index in guns.items():
        data = read(resource(index["data"], "data", "data/guns"))
        used_ammo.add(data["ammo"])
        used_attachments.update(data.get("builtin_attachments", {}).values())
        visit_tag(identifier.replace(":", ":allow_attachments/", 1))
        display = read(resource(index["display"], "assets", "display/guns"))
        check_display(display)
        animations = read(resource(display["animation"], "assets", "animations", ".animation.json"))
        for animation in animations["animations"].values():
            sounds.update(sound_effects(animation.get("sound_effects", {})))

    assert ammos.keys() == used_ammo, f"Ammunition catalog differs from gun dependencies: {ammos.keys() ^ used_ammo}"
    assert attachments.keys() == used_attachments, f"Attachment catalog differs from gun dependencies: {attachments.keys() ^ used_attachments}"
    assert tags.keys() == used_tags, f"Unused attachment tags: {tags.keys() ^ used_tags}"
    for kind, catalog in (("ammo", ammos), ("attachments", attachments)):
        for index in catalog.values():
            if "data" in index:
                resource(index["data"], "data", "data/" + kind)
            check_display(read(resource(index["display"], "assets", "display/" + kind)))

    required_sounds = {resource(sound, "assets", "tacz_sounds", ".ogg") for sound in sounds}
    shipped_sounds = set((PACK / "assets").glob("*/tacz_sounds/**/*.ogg"))
    assert shipped_sounds == required_sounds, f"Unused sound resources: {shipped_sounds - required_sounds}"
    catalogs = {"gun": guns, "ammo": ammos, "attachment": attachments}
    for recipe in documents("recipes").values():
        result = recipe["result"]
        if result["type"] in catalogs:
            assert result["id"] in catalogs[result["type"]], f"Recipe for a removed item: {result}"
    for block in documents("data/blocks").values():
        for tab in block.get("tabs", []):
            for key, identifier in tab.get("icon", {}).get("nbt", {}).items():
                kind = {"GunId": "gun", "AmmoId": "ammo", "AttachmentId": "attachment"}.get(key)
                if kind:
                    assert identifier in catalogs[kind], f"Removed workbench icon: {identifier}"
    return len(ammos), len(attachments), len(sounds)


def validate():
    indexes = list((PACK / "data").glob("*/index/guns/**/*.json"))
    assert {p.stem for p in indexes} == EXPECTED and len(indexes) == 10
    assert all("bujidao" in p.parts for p in indexes), "Default guns remain registered"
    guns = {}
    for index_path in indexes:
        name = index_path.stem
        index = read(index_path)
        data = read(resource(index["data"], "data", "data/guns"))
        display = read(resource(index["display"], "assets", "display/guns"))
        guns[name] = data
        resource(data["ammo"], "data", "index/ammo")
        model = read(resource(display["model"], "assets", "geo_models"))["minecraft:geometry"][0]
        bones = {bone["name"] for bone in model["bones"]}
        assert len(bones) == len(model["bones"]), f"Duplicate bones: {name}"
        assert all(bone.get("parent", "") in bones | {""} for bone in model["bones"]), name
        for key in ["texture", "hud", "slot"]:
            resource(display[key], "assets", "textures", ".png")
        if display.get("muzzle_flash"):
            resource(display["muzzle_flash"]["texture"], "assets", "textures", ".png")
        resource(display["state_machine"], "assets", "scripts", ".lua")
        if data.get("script"):
            resource(data["script"], "data", "scripts", ".lua")
        animations = read(resource(display["animation"], "assets", "animations", ".animation.json"))["animations"]
        required = {"static_idle", "draw", "put_away", "shoot", "reload_empty", "reload_tactical", "inspect"}
        if name != "sg_01":
            required.add("inspect_empty")
        assert required <= animations.keys(), f"Missing actions: {name} {required - animations.keys()}"
        for action, animation in animations.items():
            assert animation.get("bones", {}).keys() <= bones, f"Unknown animated bone: {name}/{action}"
        if name != "sg_01":
            for mode in ["empty", "tactical"]:
                assert data["reload"]["feed"][mode] <= data["reload"]["cooldown"][mode]
                assert data["reload"]["cooldown"][mode] == animations["reload_" + mode]["animation_length"]
        else:
            params = data["script_param"]
            assert params == display["state_machine_param"], "SG-01 client/server timing mismatch"
            for field, action in [("intro", "reload_intro"), ("intro_empty", "reload_intro_empty"),
                                  ("loop", "reload_loop"), ("ending", "reload_end")]:
                assert params[field] == animations[action]["animation_length"]
            assert params["loop_feed"] <= params["loop"]
        if data["bolt"] == "manual_action":
            assert animations["bolt"].get("bones") and animations["bolt"]["animation_length"] > 0
        for sound in display["sounds"].values():
            resource(sound, "assets", "tacz_sounds", ".ogg")
        assert "shoot" in display["sounds"] and "shoot_3p" in display["sounds"], name
        for slot, attachment in data.get("builtin_attachments", {}).items():
            assert slot + "_pos" in bones, f"Missing {slot} mount: {name}"
            attachment_index = read(resource(attachment, "data", "index/attachments"))
            assert attachment_index["type"] == slot, f"Wrong attachment slot: {attachment}"

    recipes = [read(p) for p in (PACK / "data").glob("*/recipes/**/*.json")]
    gun_results = [recipe["result"]["id"] for recipe in recipes if recipe["result"]["type"] == "gun"]
    assert set(gun_results) == {"bujidao:" + gun for gun in EXPECTED} and len(gun_results) == 10
    for path in (PACK / "data/bujidao/index/attachments").glob("*.json"):
        index = read(path)
        resource(index["data"], "data", "data/attachments")
        display = read(resource(index["display"], "assets", "display/attachments"))
        resource(display["model"], "assets", "geo_models")
        for field in ["texture", "slot"]:
            resource(display[field], "assets", "textures", ".png")
        assert index["type"] == "scope" and display["scope"]
    arc = read(PACK / "assets/bujidao/display/guns/sr_01_display.json")["ammo"]["electric_arc"]
    assert arc["max_particles"] > 0
    flame = read(PACK / "assets/bujidao/display/guns/sr_02_display.json")["ammo"]["particle"]
    assert flame["name"] == "minecraft:flame" and flame["count"] > 0
    assert not list((PACK / "data/tacz/index/guns").glob("*.json"))
    ammos, attachments, sounds = validate_catalog()
    print(f"BJD pack valid: 10 guns, {ammos} ammunition types, {attachments} attachments, {sounds} sounds; "
          "resource references, recipes, animations, timings and special effects verified.")


if __name__ == "__main__":
    validate()
