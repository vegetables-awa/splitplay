# splitplay

Minecraft 1.21.1（Fabric）本地双人同屏。一个客户端，两个窗口，一人一套键盘鼠标。

在标题界面按 `F7`，第二个窗口开在屏幕下半部分，任务栏自动隐藏。两个窗口只响应各自的键盘和鼠标，
有各自的视角和各自的光标。

## 安装

把 `splitplay-0.1.0.jar` 放进实例的 `mods` 目录，就这一步。

## 功能

- 分屏：`F7` 开第二个窗口，两个窗口都变成无边框、上下排列，任务栏隐藏。关掉任一窗口，任务栏恢复。
- 独立输入：在每个窗口按 `F8`，选这个玩家的键盘和鼠标。已被另一个窗口占用的设备会置灰。
- 独立光标：每个窗口自己画箭头，两个人不会抢那一个系统指针。
- 指针锁定：鼠标限制在前台窗口内，切到别的程序立刻释放。
- 设置按窗口分开：第一个窗口用 `config/splitplay.json`，第二个用 `config/splitplay-p2.json`，
  两个人可以各用一套设备、各自的灵敏度。
- 设备界面里有视角灵敏度和光标速度两条滑杆。
- 界面中英文，跟随游戏语言。
- 兼容整合包：第二个窗口跑在同一个实例里，配置、资源包、自定义菜单对两个窗口都生效。
- 联机：一个窗口「对局域网开放」，另一个连接 `127.0.0.1:<端口>`。

需要 Windows、两套键鼠、Minecraft 1.21.1、Fabric Loader 0.16+、Java 21。

## 构建

```powershell
.\gradlew.bat build
.\release.ps1
```

## 文档

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)：模块划分、输入管线、指针策略、mixin 清单
- [docs/DEVELOPING.md](docs/DEVELOPING.md)：构建、11 套离线自检、发布流程
- [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)：日志行、常见症状、恢复办法
- [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)：已验证的整合包、有风险的 mod、硬限制
- [CHANGELOG.md](CHANGELOG.md)

## 许可

MIT，见 [LICENSE](LICENSE)。
