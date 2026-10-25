# TACZ / Geyser 物品映射

`tacz.json` 使用 `format_version: 1`，注册 29 个物品。编号取自 `paper/src/main/resources/default-modeldata.json`；插件、配置 ZIP 与 mapping 共用这份固定对应关系。

| 类别 | 数量 | Java 载体 | 默认 CustomModelData |
| --- | ---: | --- | --- |
| 布吉岛枪械 | 10 | `minecraft:stick` | 3000000–3000009 |
| 弹药 | 6 | `minecraft:paper` | 3001000–3001005 |
| 配件 | 10 | `minecraft:flint` | 3002000–3002009 |
| 弹药箱 | 3 | `minecraft:chest` | 3003000–3003002 |

枪械依次为 AR-01、AR-02、HG-01、HG-3、SG-01、SG-02、SR-01、SR-02、SR-03、SR-04。完整逐项表由生成器输出为 `build/distributions/geyser/modeldata.tsv`。

## 生成与安装

以用户提供的网易 `TACZ.zip` 为基础，保留原有枪械模型、动画、脚本与 4 个瞄具，并补充 6 种弹药、6 个公共配件和 3 个弹药箱的图标。枪械、瞄具图标沿用 ZIP，弹药/公共配件图标直接复用当前 MOD 枪包；弹药箱共用现有 Geyser 世界包的通用箱子图标，通过名称区分等级，本次没有制作三套独立的弹药箱模型。输入 ZIP 不修改。

物品由 Geyser 根据 mapping 注册并下发名称、图标等组件，输出资源包不包含 `netease_items_res/`，输出行为包不包含 `netease_items_beh/`。资源包保留 `textures/item_texture.json`、PNG、attachable、模型、动画等显示资源。行为包仅保留原包的 Python 表现脚本、弹体实体等内容；当前枪械 attachable 使用隐藏几何，手持显示仍依赖这些脚本，因此此处只清理重复物品定义，没有移除整个行为包或改写手持渲染方案。

```powershell
python tools/build_geyser_tacz_pack.py --source E:/0-Self/Download/TACZ.zip --geyser D:/WorkSpace/IDEA/Java/Geyser_netease_1.21
```

输出对应安装位置：

| 输出文件 | Geyser 项目内的位置 |
| --- | --- |
| `build/distributions/geyser/tacz.json` | `run/custom_mappings/tacz.json` |
| `build/distributions/geyser/TACZ-resource.zip` | `run/packs/ResourcePacks/TACZ.zip` |
| `build/distributions/geyser/TACZ-behavior.zip` | `run/packs/BehaviorPacks/TACZ.zip` |

生成器验证所有映射的基岩版物品 ID、图标和 PNG 引用，并检查与 Geyser 现有 mapping 的载体/编号冲突。此次任务已复制到上述位置；不替换原 `gunspack.json`，也不重写已有的 `bjd-server.zip`。如还需要离线分发世界包，可用 Geyser 项目的世界包打包流程重新打包。

沿用现有 Geyser 的 `heypixel:<name>` 注册规则，Geyser 源码无需修改。mapping 的 `name` 不带冒号：例如 `bujidao_ar_01`、`tacz_762x39`，实际基岩版物品 ID 为 `heypixel:bujidao_ar_01`、`heypixel:tacz_762x39`。生成器同步替换资源包、行为包、attachable 和 Python 配置中的物品 ID；音效、粒子、弹体实体和模型几何的资源 ID 保持原样。Paper PDC 中的 `bujidao:ar_01`、`tacz:762x39` 等型号 ID 也保持原样。

更新 Paper 插件并采用新默认编号后，重启 Geyser，再重新连接基岩版客户端。既有 Paper 物品 JSON 不会自动覆盖，可从新的 `tacz-paper-bjd-config.zip` 补入 `modeldata`，保留已调整的伤害、射速等参数；弹药箱可在 `config.yml` 覆盖默认编号。Java MOD 无需更新。此前针对 Geyser 命名空间的代码修改已撤回。此处仅完成物品身份、外观资源和 CMD 映射，没有新增网易脚本与 `tacz:paper` 的动作协议转换。
