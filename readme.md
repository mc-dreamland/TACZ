<p align="center">
    <img width="300" src="https://s2.loli.net/2024/04/30/NJrstR1QzpoLyIT.png" alt="title">
</p>
<hr>
<p align="center">Timeless and Classics Guns Zero</p>
<p align="center">
    <a href="https://www.curseforge.com/minecraft/mc-mods/timeless-and-classics-zero">
        <img src="http://cf.way2muchnoise.eu/full_timeless-and-classics-zero.svg" alt="CurseForge Download">
    </a>
    <img src="https://img.shields.io/badge/license-GNU GPL 3.0 | CC%20BY--NC--ND%204.0-green" alt="License">
    <br>
    <a href="https://jitpack.io/#MCModderAnchor/TACZ">
        <img src="https://jitpack.io/v/MCModderAnchor/TACZ.svg" alt="jitpack build">
    </a>
    <a href="https://crowdin.com/project/tacz">
        <img src="https://badges.crowdin.net/tacz/localized.svg" alt="crowdin">
    </a>
</p>
<p align="center">
    <a href="https://github.com/MCModderAnchor/TACZ/issues">Report Bug</a>    ·
    <a href="https://github.com/MCModderAnchor/TACZ/releases">View Release</a>    ·
    <a href="https://tacwiki.mcma.club/zh/">Wiki</a>
</p>

This branch of Timeless and Classics Guns Zero targets **Minecraft 1.21.11, NeoForge 21.11.45+, and Java 21**. The supported Minecraft version is exactly 1.21.11; NeoForge must remain on the 21.11 release line.

Paper 1.21.11 server support is in the independent [paper module](paper/README.md).
It uses this repository's NeoForge client bridge (protocol 4) and supports the bundled Bujidao gun pack. The client and Paper server both target Minecraft 1.21.11, so this pairing does not require a vanilla protocol translator.

## Build this branch

Use Java 21 and run `./gradlew test build` (`.\gradlew.bat test build` on Windows). The installable mod is `build/libs/tacz-1.21.11-neoforge-1.1.8+neoforge.1.21.11.hotfix2.jar`; its Lua, BCEL, and math dependencies are included by Jar-in-Jar. Do not install the sources JAR or an older artifact from the same output directory.

The port uses data components for item state, NeoForge payloads and attachments, and the 1.21.11 render-state/submit-collector APIs. Resource identifiers use Minecraft's `Identifier` API; rendering uses `RenderSetup` and explicit GPU samplers. Java addons that implement the old immediate-mode `IFunctionalRenderer` must migrate to `IFunctionalSubmitter`. Legacy gun-pack result NBT and attachment data are translated at their read boundaries. Back up worlds and external gun packs before a version migration; this does not promise automatic conversion of every Forge 1.20.1 world or third-party gun pack.

JEI, Cloth Config, Shoulder Surfing, Controllable, and Player Animation Library have updated integrations. Carry On and Iris hooks are included, but optional-mod combinations and shader packs require in-game verification. KubeJS sources are preserved outside the build in `optional-integrations/kubejs`; the old Oculus and Accelerated Rendering integrations are preserved in `src/legacy` and are not included in the NeoForge artifact.

The built-in pack contains the ten Bujidao guns. See [the pack guide](docs/bjd-gunpack.md) for installation and configuration. Optional-mod combinations, shader packs, and Paper multiplayer should be verified in game when updating a deployment.

Validation on NeoForge 21.11.45: `test build` passes 67 tests, including optional-channel send guards. The client starts and loads all ten gun displays. Native gun actions require their negotiated channel; the Paper handshake retries until Bukkit advertises its bridge channel, so joining a plugin or vanilla server does not send unsupported native payloads.

The rendering and API port incorporates GPL code from [TaCZ_Renovated](https://github.com/q14433686-arch/TaCZ_Renovated/tree/62532f1998322978ff1c05823870ccb6e28d3569), adapted to this repository's NeoForge version and Paper bridge. Original TACZ authorship and asset licensing remain as listed below.

## Notice

- If you have any bugs, you can visit [Issues](https://github.com/MCModderAnchor/TACZ/issues) to
  submit issues.

## Authors

- Programmer: `286799714`, `TartaricAcid`, `F1zeiL`, `xjqsh`, `ClumsyAlien`
- Artist: `NekoCrane`, `Receke`, `Pos_2333`

## Credits

- Other players who have helped me in any ways, and you

## License

- Code: [GNU GPL 3.0](https://www.gnu.org/licenses/gpl-3.0.txt)
- Assets: [CC BY-NC-ND 4.0](https://creativecommons.org/licenses/by-nc-nd/4.0/)

## Legacy Forge Maven example

The following coordinate describes the historical Forge release, not this NeoForge branch. No published Maven coordinate for this build is provided.

```groovy
repositories {
    maven {
        // Add curse maven to repositories
        name = "Curse Maven"
        url = "https://www.cursemaven.com"
        content {
            includeGroup "curse.maven"
        }
    }
}

dependencies {
    // You can see the https://www.cursemaven.com/
    // Choose one of the following three

    // If you want to use version tacz-1.20.1-1.1.6-release
    implementation fg.deobf("curse.maven:timeless-and-classics-zero-1028108:6632240-sources-6633203")
}
```
