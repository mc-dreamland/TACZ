# BJD 枪包接入

素材来源为 `20251227十枪械射击换弹/TACZ枪包/BUJIDAO` 完整枪包。保留 `bujidao` 命名空间、模型、贴图、图标、瞄准定位和动画；普通枪械使用按类别设置的初始参数。素材目录中错误导出的 AR-02、HG-01 几何文件没有覆盖完整包内的正确模型。

内置包位于 `src/main/resources/assets/tacz/custom/tacz_default_gun/`。其中 `assets/bujidao/` 是客户端表现，`data/bujidao/` 是枪械参数、索引、配方和兼容配件。`tacz` 命名空间仅保留弹药、通用配件、工作台、声音和共享脚本；原 54 把枪的索引、参数、配方、模型、贴图与动画已移除。创造栏、工作台分类及奖励箱均改为新枪。

## 初始参数

伤害沿用对应类别的 TACZ 基础参数，可继续在 `data/bujidao/data/guns/` 或插件导出的 `guns/bujidao/` 调整。插件参数会同步给客户端；模型、纹理、声音和表现配置需要两端使用对应的新版本。

| ID | 类型 | 弹药 | 基础弹容 | RPM |
| --- | --- | --- | ---: | ---: |
| `bujidao:ar_01` | 步枪 | `tacz:762x39` | 30 | 600 |
| `bujidao:ar_02` | 步枪 | `tacz:556x45` | 30 | 750 |
| `bujidao:hg_01` | 手枪 | `tacz:357mag` | 6 | 240 |
| `bujidao:hg_3` | 手枪 | `tacz:357mag` | 6 | 180 |
| `bujidao:sg_01` | 逐发装填霰弹枪 | `tacz:12g` | 6（含膛内） | 90 |
| `bujidao:sg_02` | 双发霰弹枪 | `tacz:12g` | 2 | 120 |
| `bujidao:sr_01` | 电弧自动武器 | `tacz:762x39` | 100 | 600 |
| `bujidao:sr_02` | 火焰自动武器 | `tacz:762x39` | 100 | 600 |
| `bujidao:sr_03` | 栓动狙击枪 | `tacz:338` | 8 | 60 |
| `bujidao:sr_04` | 半自动狙击枪 | `tacz:50bmg` | 10 | 120 |

除 SG-01 外，弹容遵循原 MOD 的弹匣容量语义，闭膛枪可另存一发膛内弹。扩容后的数值也在各枪配置中。

SR-01、SR-02 保留原包特殊自动武器定位，默认弹速 20 格/秒，存活时间分别 0.5/0.6 秒，弹道距离约 10/12 格。电弧和火焰由客户端沿弹道显示，不执行旧 Forge 混淆方法 Lua，不额外召唤闪电或施加独立伤害；实际命中、伤害和遮挡仍由服务器处理。火焰默认仅是视觉效果，`bullet.ignite` 可单独配置点燃规则。

电弧参数在 `assets/bujidao/display/guns/sr_01_display.json` 的 `ammo.electric_arc`，火焰参数在 SR-02 的 `ammo.particle`。粒子 `min_distance` 控制距射手的最小显示距离，默认 3 格、限制为 0–64 格，SR-02 设为 0.25 格以保留近距离火焰。客户端有粒子预算，未增加逐粒子 Plugin Message。

SG-01 保留逐发装填及射击内泵动；`script_param` 与客户端 `state_machine_param` 使用相同动画阶段时长。普通枪的装填完成点在动画时长的 80%，完整冷却与动画结束对齐。枪声及按比例对齐的装填、切枪、检视声音来自共享 TACZ 资源。

## 配件

4 个专用瞄具为 `bujidao:ar_02jing`、`bujidao:sr02`、`bujidao:sr03`、`bujidao:sr04`，分别适配 AR-02、SR-02、SR-03、SR-04，并作为对应枪的默认外观。SR-03/04 原包误写为 laser 的配件槽已修正为 scope。它们均有工作台配方。

所有枪可使用配置允许的扩容/弹药改装。未具备可靠定位的枪托、握把、枪口和激光槽没有开放，避免安装后出现悬空模型。兼容表在 `data/bujidao/tacz_tags/attachments/allow_attachments/`，插件将每把枪的兼容表导出到 `guns/bujidao/<id>.json` 的 `allow_attachments` 字段，通用配件标签另存于 `attachment_tags/`。

## 构建与部署

在相应分支使用该分支所需的 Java 版本构建客户端；Paper 模块始终使用 Java 21。

```powershell
.\gradlew.bat build
cd paper
.\gradlew.bat test build
```

- 客户端完整枪包：`build/distributions/tacz-bjd-gunpack.zip`。
- 插件目录配置：`paper/build/distributions/tacz-paper-bjd-config.zip`。
- MOD 与插件 JAR：分别在 `build/libs/` 和 `paper/build/libs/`。

新 MOD 会按现有机制导出更新后的 `tacz/tacz_default_gun/`。如果手动安装 ZIP，替换该整个目录，ZIP 内容的 `gunpack.meta.json`、`assets/`、`data/` 应直接位于目录内。不要再同时加载旧 BUJIDAO 副本，以免同 ID 配置覆盖。本项目 `run/` 原本没有已部署的游戏或插件配置，因此没有修改外部游戏实例。

新 Paper 数据目录首次启动会自动导出全部新配置。已经启动过的服务器，停服后先备份 `plugins/TACZ/`，将其 `guns/`、`ammos/`、`attachments/`、`recipes/`、`attachment_tags/` 五个目录整体替换为配置 ZIP 中的同名目录，并保留 ZIP 的 `.pack-files-v1`。不要仅覆盖同名文件，否则旧枪 JSON 会继续被加载。单独更新 JAR 不会覆盖已有的权威配置目录。

```text
/tacz give Steve gun bujidao:ar_01
/tacz give Steve ammo tacz:762x39 60
/tacz give Steve attachment bujidao:ar_02jing
/tacz workbench
/tacz list gun
```

新枪命令必须写完整 `bujidao:` 前缀。`/tacz supplies` 仍仅限 OP，可领取手持枪兼容的弹药与配件。未实现旧枪物品迁移。

静态资源检查：`python tools/validate_bjd_pack.py`，检查 10 把枪/4 瞄具的资源引用、动画骨骼、装填时序、声音和配方。实际手部遮挡、开镜及画面效果还需要在游戏中逐枪验收。
