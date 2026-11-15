# splitplay

一台电脑、两个人、两个 Minecraft 窗口，**各用各的键盘和鼠标**。

按一个键，第二个游戏窗口在屏幕下半部分打开，任务栏自动让位；两个窗口各只听自己那套设备，
互不串台。每个窗口的鼠标指针由游戏自己画出来，不抢系统那个唯一的指针。

整个东西是**一个 jar**：没有第二个 exe，没有启动脚本，没有配套程序。

```
+--------------------------------------------------+
|  P1   键盘 VID_1C4F  +  鼠标 VID_10C4              |
|       自己的视角、自己的光标、自己的聊天框          |
+--------------------------------------------------+
|  P2   键盘 VID_2A7A  +  鼠标 VID_30FA              |
|       自己的视角、自己的光标、自己的聊天框          |
+--------------------------------------------------+
```

## 当前状态

| 部分 | 状态 |
|---|---|
| Raw Input 采集（JNA，不需要辅助 exe） | 完成，已用独立探针验证 |
| 按设备隔离与回放输入 | 完成 |
| 虚拟光标 | 完成，已在屏幕上验证 |
| 设备选择界面、按窗口保存设置 | 完成 |
| 一键开第二个窗口 → 上下分屏 → 隐藏任务栏 | 完成，Windows 10 实测通过 |
| 在真实整合包里运行 | 完成（Cobblemon 整合包，191 个 mod） |
| 自动开放局域网 | 未做（手动连 `127.0.0.1:<端口>`） |
| 两个窗口都支持中文输入法 | 未做（IME 是系统级的，不按设备分流） |
| 手柄 | 未做 |

## 前提条件

- Windows 10 / 11，64 位（输入层用到 `Raw Input` 和 `ClipCursor`）
- Minecraft **1.21.1** + **Fabric Loader 0.16+**，Java 21
- **两套键盘和鼠标**，必须是两个独立的物理设备
- 内存要够开两个客户端：8 GB 紧张，16 GB 舒服

一个 2.4G 接收器带出来的「无线键鼠套装」算一键盘一鼠标，可以正常使用。

## 安装

1. 把 `splitplay-0.1.0.jar` 放进实例的 `mods` 目录——**只放一个实例**，第二个窗口由模组自己拉起，
   不要手动准备第二个实例。
2. 启动游戏，在标题界面按 **F7**（或点「双人同屏」按钮）。第二个窗口出现在下半屏，任务栏消失。
3. **两个窗口各自**按 **F8**，勾选这个玩家的键盘和鼠标，点「保存」。已被另一个窗口占用的设备会置灰。
4. 在其中一个窗口进世界 → 「对局域网开放」→ 另一个窗口「直接连接」`127.0.0.1:<端口>`。

两个窗口跑在**同一个游戏目录**里，所以整合包的配置、资源包、自定义菜单在第二个窗口里同样生效。
只有设置文件是分开的：第一个窗口用 `config/splitplay.json`，第二个用 `config/splitplay-p2.json`。

## 快捷键

| 按键 | 作用 |
|---|---|
| `F7` | 开始双人模式（开第二个窗口、上下分屏、隐藏任务栏） |
| `Shift` + `F7` | 打开设备选择界面 |
| `F8` | 打开设备选择界面 |
| 界面里的「保存」/「取消」 | 写入 / 放弃这次选择 |

标题界面上的按钮做同样的事。有些整合包（FancyMenu）会重画主菜单、不渲染我们的按钮，所以保留了快捷键。

## 工作原理（一句话版）

Minecraft 原本从 GLFW 读输入，而 GLFW 只知道「有鼠标动了」，不知道是哪一个。splitplay 换成
`Raw Input`，它能报告**哪个设备**动了，然后：

1. 把原生 GLFW 的按键、字符、鼠标移动/点击/滚轮回调全部拦掉；
2. 把**属于本窗口设备**的事件，在原版即将消费它们的前一刻，用同样的原版方法
   （`KeyboardHandler.keyPress`、`MouseHandler.onMove` 等）喂回去；
3. 自己在窗口里画一个光标箭头，因为操作系统只有一个指针。

每个钩子、指针策略、为什么永不用 `GLFW_CURSOR_DISABLED`、第二个窗口怎么起来的，都在
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)（英文）。

## 已知限制

- **仅 Windows**，单机，必须两套键鼠。
- **第二个窗口是一整个新的 Minecraft 客户端**：整合包越大启动越慢（191 个 mod 约 40~90 秒），
  并且要单独占一份内存。
- **局域网要手动开**，暂时没有自动开房间。
- **中文输入法**同一时刻只能在一个窗口用；字母、数字、标点两边都正常（字符按设备分流，
  输入法是系统级的，分流不了）。
- **两个窗口会写同一个 `logs/latest.log`**（故意共用工作目录，原因见
  [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)），后启动的会截断前一个的日志。
- **把键盘/鼠标换到别的 USB 口**，设备路径会变；该窗口会自动退回原版输入并在日志里警告，
  重新按 `F8` 选一次即可。

## 兼容性

已在 **Cobblemon Official Modpack [Fabric]**（191 个 mod，含 FancyMenu、Konkrete、Melody、InvMove、
Sodium、Iris、EMI、JEI、Xaero 双图）和干净的 1.21.1 + Fabric 实例上验证过。
需要留意的是那些**自己也改写输入管线**的 mod。测试清单与风险分类见
[docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)（英文）。

## 构建

```powershell
.\gradlew.bat build                 # 构建模组（需要 JDK 21；Loom 首次会下载 MC）
.\release.ps1                       # 构建 + 11 套离线自检 + 打包 dist\splitplay-<version>.zip
```

`release.ps1` 只要有一套自检失败就拒绝打包。各套自检（键码表、命令行改写、占用文件、配置文件、
jar 内容、mixin 目标、按键状态机、设备界面几何、事件队列）的说明在
[docs/DEVELOPING.md](docs/DEVELOPING.md)（英文）。

## 文档

| 文件 | 内容 |
|---|---|
| [README.md](README.md) | 英文版说明（与本文同步） |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 模块划分、输入管线、指针策略、窗口排布、mixin 清单 |
| [docs/DEVELOPING.md](docs/DEVELOPING.md) | 工具链、构建、11 套自检、发布流程、代码约定 |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | 日志行对照表、症状→原因、恢复办法 |
| [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) | 已验证的整合包与 mod、风险类别、硬限制 |
| [INSTALL.txt](INSTALL.txt) | 五步安装说明（纯文本，随 zip 一起发布） |
| [CHANGELOG.md](CHANGELOG.md) | 每次改了什么、为什么改 |

## 许可

MIT，见 [LICENSE](LICENSE)。

「把输入按窗口拆开」这个思路来自 [Mouse&mouse](https://github.com/kafei520-CN/Mouse-mouse)（GPL-3.0）。
splitplay 是重写实现：没有复制它的代码，所以可以是 MIT。它的视角处理和光标思路被研究过并重新实现，
差异记在 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 里。
