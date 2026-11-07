"""Validate assembled bujidao assets and report deleted dependencies without restoring them."""
import argparse
import hashlib
import json
import pathlib
import re
from assemble_bujidao_resources import AMMO_BY_GUN, AMMO_GROUPS, AMMO_MODELDATA, ammo_index, ammo_labels

ROOT = pathlib.Path(__file__).resolve().parents[1]
PACK = ROOT / "src/main/resources/assets/tacz/custom/tacz_bjd_gun"
EXPECTED = {"ar_01", "ar_02", "hg_01", "hg_3", "sg_01", "sg_02", "sr_01", "sr_02", "sr_03", "sr_04"}
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
    path = PACK / kind / namespace / directory / (name + suffix)
    if not path.is_file():
        path = ROOT / "src/main/resources" / kind / namespace / directory / (name + suffix)
    return path


def documents(directory):
    result = {}
    for path in (PACK / "data").glob("*/" + directory + "/**/*.json"):
        relative = path.relative_to(PACK / "data")
        namespace = relative.parts[0]
        name = path.relative_to(PACK / "data" / namespace / directory).with_suffix("").as_posix()
        result[namespace + ":" + name] = read(path)
    return result


def sound_effects(value):
    if isinstance(value, dict):
        effect = value.get("effect", "")
        if isinstance(effect, str) and ":" in effect:
            yield effect
        for child in value.values():
            yield from sound_effects(child)
    elif isinstance(value, list):
        for child in value:
            yield from sound_effects(child)


def validate():
    manifest = read(PACK / "assembly.json")
    assert read(PACK / "gunpack.meta.json")["namespace"] == "bujidao"
    indexes = documents("index/guns")
    assert set(indexes) == {"bujidao:" + name for name in EXPECTED}
    ammunition = documents("index/ammo")
    assert set(ammunition) == {"bujidao:" + bullet for bullet in AMMO_GROUPS}
    for bullet in AMMO_GROUPS:
        assert ammunition["bujidao:" + bullet] == ammo_index(bullet)
        assert read(resource(f"bujidao:{bullet}_display", "assets", "display/ammo")) == {}
    modeldata = read(ROOT / "paper/src/main/resources/default-modeldata.json")["ammo"]
    assert modeldata == {"bujidao:" + bullet: number for bullet, number in AMMO_MODELDATA.items()}
    missing = {}

    def need(identifier, kind, directory, suffix=".json"):
        path = resource(identifier, kind, directory, suffix)
        if not path.is_file():
            missing[(kind + "/" + directory, identifier)] = path.relative_to(ROOT).as_posix()
            return None
        return path

    def need_tag(identifier, seen):
        if identifier in seen:
            return
        seen.add(identifier)
        path = need(identifier, "data", "tacz_tags/attachments")
        if path:
            for entry in read(path):
                if entry.startswith("#"):
                    need_tag(entry[1:], seen)
                else:
                    need(entry, "data", "index/attachments")

    def check_lua(path, seen):
        if path in seen:
            return
        seen.add(path)
        for module in re.findall(r'require\(["\']([^"\']+)["\']\)', path.read_text("utf-8")):
            namespace, name = module.split("_", 1)
            dependency = need(namespace + ":" + name, "assets", "scripts", ".lua")
            if dependency:
                check_lua(dependency, seen)

    sound_ids = set()
    for identifier, index in sorted(indexes.items()):
        name = identifier.split(":")[1]
        data_path = need(index["data"], "data", "data/guns")
        display_path = need(index["display"], "assets", "display/guns")
        assert data_path and display_path, identifier
        data, display = read(data_path), read(display_path)
        assert data["ammo"] == AMMO_BY_GUN[name], identifier
        need(data["ammo"], "data", "index/ammo")
        need_tag(identifier.replace(":", ":allow_attachments/", 1), set())
        for attachment in data.get("builtin_attachments", {}).values():
            need(attachment, "data", "index/attachments")
        if data.get("script"):
            need(data["script"], "data", "scripts", ".lua")
        state = need(display["state_machine"], "assets", "scripts", ".lua")
        if state:
            check_lua(state, set())
        if display.get("player_animator_3rd"):
            need(display["player_animator_3rd"], "assets", "player_animator")
        if display.get("use_default_animation"):
            default_animation = ROOT / "src/main/resources/assets/tacz/animations" / (display["use_default_animation"] + "_default.animation.json")
            if not default_animation.is_file():
                missing[("internal/animations", display["use_default_animation"])] = default_animation.relative_to(ROOT).as_posix()
        model_path = need(display["model"], "assets", "geo_models")
        assert model_path, identifier
        model = read(model_path)["minecraft:geometry"][0]
        bones = {bone["name"] for bone in model["bones"]}
        assert len(bones) == len(model["bones"]), identifier
        assert all(bone.get("parent", "") in bones | {""} for bone in model["bones"])
        for field in ("texture", "slot", "hud"):
            assert need(display[field], "assets", "textures", ".png"), (identifier, field)
        if display.get("muzzle_flash"):
            assert need(display["muzzle_flash"]["texture"], "assets", "textures", ".png")
        animation_path = need(display["animation"], "assets", "animations", ".animation.json")
        assert animation_path, identifier
        animations = read(animation_path)["animations"]
        for action in animations.values():
            assert action.get("bones", {}).keys() <= bones, identifier
        if name == "sg_01":
            assert data["script_param"] == display["state_machine_param"]
            for field, action in (("intro", "reload_intro"), ("intro_empty", "reload_intro_empty"),
                                  ("loop", "reload_loop"), ("ending", "reload_end")):
                assert data["script_param"][field] == animations[action]["animation_length"]
        else:
            for mode in ("empty", "tactical"):
                assert data["reload"]["feed"][mode] <= data["reload"]["cooldown"][mode]
                assert data["reload"]["cooldown"][mode] == animations["reload_" + mode]["animation_length"]
        configured = display["sounds"]
        animation_sounds = {effect for action in animations.values()
                            for effect in sound_effects(action.get("sound_effects", {}))}
        if name == "hg_3":
            assert not configured and not animation_sounds
        else:
            assert {"shoot", "shoot_3p", "draw", "reload_empty", "reload_tactical", "flesh_hit", "head_hit"} <= configured.keys()
            sound_ids.update(configured.values())
            sound_ids.update(animation_sounds)
            # These are injected by GunDisplayInstance, even if no source clip was supplied.
            for sound in DEFAULT_SOUNDS:
                if sound.split(":", 1)[1].split("/")[0] not in configured:
                    need(sound, "assets", "tacz_sounds", ".ogg")
        assert not re.search(r"ak47|m4a1|rhino357|deagle|m870|db_short|ai_awp|m107", display_path.read_text("utf-8"), re.I)

    for sound in sound_ids:
        assert sound.startswith("bujidao:weapons/")
        assert need(sound, "assets", "tacz_sounds", ".ogg"), sound
    for identifier, index in documents("index/attachments").items():
        assert identifier.startswith("bujidao:")
        assert need(index["data"], "data", "data/attachments")
        display = read(need(index["display"], "assets", "display/attachments"))
        assert need(display["model"], "assets", "geo_models")
        for field in ("texture", "slot"):
            assert need(display[field], "assets", "textures", ".png")

    # Verify immutable geometry, textures and gameplay parameters against the approved backup.
    changed = {f"assets/bujidao/{kind}/{name}{suffix}" for name in EXPECTED
               for kind, suffix in (("display/guns", "_display.json"), ("animations", ".animation.json"))}
    for relative, expected in manifest["backup_sha256"].items():
        assert hashlib.sha256((ROOT / "aaa" / relative).read_bytes()).hexdigest() == expected, relative
        if relative.startswith("data/bujidao/data/guns/"):
            original = read(ROOT / "aaa" / relative)
            original["ammo"] = AMMO_BY_GUN[pathlib.Path(relative).stem.removesuffix("_data")]
            assert read(PACK / relative) == original, relative
        elif relative in ("assets/bujidao/lang/zh_cn.json", "assets/bujidao/lang/en_us.json"):
            original = read(ROOT / "aaa" / relative)
            original.update(ammo_labels(pathlib.Path(relative).stem))
            assert read(PACK / relative) == original, relative
        elif relative == "bjd-guns.json":
            original = read(ROOT / "aaa" / relative)
            for gun in original["guns"]:
                gun["ammo"] = AMMO_BY_GUN[gun["id"].split(":", 1)[1]]
            assert read(PACK / relative) == original, relative
        elif relative not in changed:
            assert hashlib.sha256((PACK / relative).read_bytes()).hexdigest() == expected, relative
    audio_root = PACK / "assets/bujidao/tacz_sounds"
    assert {p.relative_to(audio_root).as_posix() for p in audio_root.rglob("*.ogg")} == manifest["audio_sha256"].keys()
    for relative, expected in manifest["audio_sha256"].items():
        assert not relative.startswith("weapons/hg_02/")
        for path in (audio_root / relative, ROOT / "sounds" / relative):
            assert hashlib.sha256(path.read_bytes()).hexdigest() == expected, path
    assert len(manifest["audio_sha256"]) == 48
    referenced = {identifier.split(":", 1)[1] + ".ogg" for identifier in sound_ids}
    assert set(manifest["audio_sha256"]) - referenced == set(manifest["alternative_audio"])
    template = ROOT / "src/main/resources/assets/bujidao/sounds/gun_template.ogg"
    assert hashlib.sha256(template.read_bytes()).hexdigest() == manifest["audio_sha256"]["weapons/ar_01/shoot.ogg"]
    entry = read(ROOT / "src/main/resources/assets/tacz/sounds.json")
    assert entry["gun"]["sounds"][0]["name"] == "bujidao:gun_template"
    return {
        "pack": PACK.relative_to(ROOT).as_posix(), "guns": len(indexes),
        "ammunition": len(ammunition), "ammo_groups": AMMO_GROUPS,
        "scopes": len(documents("index/attachments")), "provided_audio": 48,
        "referenced_audio": len(referenced), "alternative_audio": manifest["alternative_audio"],
        "silent_gun": "bujidao:hg_3", "source_hashes": "verified",
        "missing_dependencies": [{"kind": kind, "id": identifier, "path": path}
                                 for (kind, identifier), path in sorted(missing.items())],
        "note": "Assembly checks pass separately from runtime completeness. Deleted common Mod UI, shaders and other engine assets have not been restored.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-missing", action="store_true", help="Accept known dependency gaps, not a runtime acceptance check")
    parser.add_argument("--report", type=pathlib.Path)
    args = parser.parse_args()
    report = validate()
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if report["missing_dependencies"] and not args.allow_missing:
        raise SystemExit("Assembly is present, but runtime dependencies are missing. Use --allow-missing only for the agreed partial resource assembly.")


if __name__ == "__main__":
    main()
