"""Validate the shipped BJD pack's resource graph without starting Minecraft."""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
PACK = ROOT / "src/main/resources/assets/tacz/custom/tacz_default_gun"
EXPECTED = {"ar_01", "ar_02", "hg_01", "hg_3", "sg_01", "sg_02", "sr_01", "sr_02", "sr_03", "sr_04"}


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
    print("BJD pack valid: 10 guns, 4 scopes, resources, animations, timings and special-effect configurations.")


if __name__ == "__main__":
    validate()
