# BJD 枪包接入

素材来源为 `20251227十枪械射击换弹/TACZ枪包/BUJIDAO` 完整枪包。保留 `bujidao` 命名空间、模型、贴图、图标、瞄准定位和动画；普通枪械使用按类别设置的初始参数。素材目录中错误导出的 AR-02、HG-01 几何文件没有覆盖完整包内的正确模型。

内置包位于 `src/main/resources/assets/tacz/custom/tacz_default_gun/`。其中 `assets/bujidao/` 是客户端表现，`data/bujidao/` 是枪械参数、索引、配方和兼容配件。`tacz` 命名空间仅保留现有枪械需要的弹药、通用配件、工作台、声音和共享脚本；原 54 把枪的索引、参数、配方、模型、贴图与动画已移除。创造栏、工作台分类及奖励箱均改为新枪。

## 初始参数

伤害沿用对应类别的 TACZ 基础参数，可继续在 `data/bujidao/data/guns/` 或插件导出的 `guns/bujidao/` 调整。插件参数会同步给客户端；模型、纹理、声音和表现配置需要两端使用对应的新版本。

插件物品配置另支持最外层的 `modeldata`（与 `id`、`index`、`data` 同级）。布吉岛 10 把枪默认 `3000000–3000009`，6 种弹药 `3001000–3001005`，10 个配件 `3002000–3002009`；三个弹药箱通过 `config.yml` 的 `ammo-box.modeldata.0/1/2` 配置为 `3003000–3003002`。它写入原版 `minecraft:custom_model_data`，供基岩版物品映射使用；`0` 不设置，已有配置可手动补入编号后 `/tacz reload`。该字段不加入 TACZ 同步目录，客户端 MOD 仍只按 NBT/PDC 信息识别枪械，无需修改客户端枪包。完整说明见 [Paper 配置](../paper/README.md#配置) 与 [Geyser 对接](../integrations/geyser/README.md)。

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

命中击退沿用对应原版枪械的 `bullet.knockback`：AR-01/02、SR-01/02/03/04 为 0，HG-01/HG-3 为 0.075（Rhino 357），SG-01 为 0.15（M870），SG-02 为 0.875（DB Short）。Paper 通过原版受击击退机制应用这些力度，会衰减原有水平速度、限制地面上抬并计算击退抗性；多颗霰弹不再直接累加完整飞行方向速度。此修复只需更新插件 JAR，已有枪械配置的力度无需改写。

SR-01、SR-02 保留原包特殊自动武器定位，默认弹速 20 格/秒，存活时间分别 0.5/0.6 秒，弹道距离约 10/12 格。电弧和火焰由客户端沿弹道显示，不执行旧 Forge 混淆方法 Lua，不额外召唤闪电或施加独立伤害；实际命中、伤害和遮挡仍由服务器处理。火焰默认仅是视觉效果，`bullet.ignite` 可单独配置点燃规则。

电弧参数在 `assets/bujidao/display/guns/sr_01_display.json` 的 `ammo.electric_arc`，火焰参数在 SR-02 的 `ammo.particle`。粒子 `min_distance` 控制距射手的最小显示距离，默认 3 格、限制为 0–64 格，SR-02 设为 0.25 格以保留近距离火焰。客户端有粒子预算，未增加逐粒子 Plugin Message。

SG-01 保留逐发装填及射击内泵动；`script_param` 与客户端 `state_machine_param` 使用相同动画阶段时长。普通枪的装填完成点在动画时长的 80%，完整冷却与动画结束对齐。枪声及按比例对齐的装填、切枪、检视声音来自共享 TACZ 资源。

第一人称默认持枪在原 BUJIDAO 构图上向画面左侧平移 1.5 个模型单位（1 单位为 1/16 格，并非屏幕像素）。仅调整各模型 `idle_view.pivot`，按该节点的旋转补偿坐标，使高度、距离和角度保持不变。整套枪体与双手一起平移，`root`、动作、机瞄、瞄具和第三人称定位未改动；左移会随开镜过渡逐渐消失。这是初步构图校准，实际观感仍需在游戏中确认。

## 配件

4 个专用瞄具为 `bujidao:ar_02jing`、`bujidao:sr02`、`bujidao:sr03`、`bujidao:sr04`，分别适配 AR-02、SR-02、SR-03、SR-04，并作为对应枪的默认外观。SR-03/04 原包误写为 laser 的配件槽已修正为 scope。它们均有工作台配方。

这 4 个瞄具使用完整透明贴图平面绘制准星。客户端过滤分划时同时识别细线几何和带透明镂空的贴图平面，避免仅按模型尺寸把整张准星误当作遮光板剔除；纯色遮光板仍被过滤。普通渲染与 Iris 延后渲染共用同一份过滤结果，镜口遮罩无效时跳过该帧准星以免贴图越出镜框。仅需更新客户端 MOD，无需调整摄像机、枪包或服务端配置。

内置包仅注册这 10 把枪实际使用的 6 种弹药和 10 个配件：上述 4 个专用瞄具、`tacz:extended_mag_1/2/3`，以及 `tacz:ammo_mod_fmj`、`tacz:ammo_mod_hp`、`tacz:ammo_mod_i`。其余原版弹药、配件及其配方、模型、贴图、标签和物品翻译已清理。

声音保留 192 个实际引用的资源，覆盖枪声、换弹/检视动画、配件装卸与通用命中反馈。部分声音目录沿用原版枪械名称，因为布吉岛枪械仍在使用这些声音；这些目录不注册原版枪械。

所有枪可使用配置允许的扩容/弹药改装。未具备可靠定位的枪托、握把、枪口和激光槽没有开放，避免安装后出现悬空模型。兼容表在 `data/bujidao/tacz_tags/attachments/allow_attachments/`，插件将每把枪的兼容表导出到 `guns/bujidao/<id>.json` 的 `allow_attachments` 字段，通用配件标签另存于 `attachment_tags/`。

## 构建与部署

在相应分支使用该分支所需的 Java 版本；当前分支使用 Java 21。根目录 `build` 会同时构建 MOD、Paper 插件和默认配置 ZIP，Paper 使用自己的 Wrapper。

```powershell
.\gradlew.bat build
```

IDEA 重新加载 Gradle 项目后，在右侧面板运行根项目的 `build`。只构建插件可运行 `buildPaper`；只构建 MOD 可运行 `build -x buildPaper`。

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

静态资源检查：`python tools/validate_bjd_pack.py`，检查 10 把枪、6 种弹药、10 个配件的资源引用、动画骨骼、装填时序、动画声音、配方和工作台图标，并阻止重新混入闲置弹药、配件和声音。实际手部遮挡、开镜及画面效果还需要在游戏中逐枪验收。
