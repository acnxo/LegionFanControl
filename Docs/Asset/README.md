# 资源说明

本目录记录**非代码资源**的用途与替换方式。

## 程序图标

| 文件 | 用途 | 形态 |
| --- | --- | --- |
| `exe.ico`（仓库根） | 原生 exe 的**文件图标**（资源管理器 / 任务栏 / 快捷方式） | 单条 200×200 **PNG-in-ICO** 条目（6 + 16 字节 ICO 头 + 原始 PNG 载荷） |
| `src/main/resources/Asset/Logo.png` | JavaFX 的**窗口与任务栏图标**（`Stage#getIcons`） | 200×200 RGBA PNG |

## 替换步骤

| 步骤 | 做法 |
| --- | --- |
| 1 | 用新图覆盖 `src/main/resources/Asset/Logo.png`（同名，PNG 格式） |
| 2 | 用新图重新封装 `exe.ico`：单条 PNG 条目即可 —— `ICONDIR`（6 字节：`00 00 01 00 01 00`）+ `ICONDIRENTRY`（16 字节）+ 原始 PNG 字节（载荷偏移 = 22） |
| 3 | 无需改动代码与构建配置：资源路径（`/Asset/Logo.png`）与 native-image 资源规则（`Asset/.*\.png$`）都已就位 |

> `exe.ico` 由工作流的 `CompileAppIconResource` 经 `rc.exe` 编译为 `exe.res` 后嵌入 exe，
> 并由 `CheckExeIcon` 在 exe 字节里复检是否真的嵌进去了；详见 `Docs/NativeImageCi.md` 第 5 节第 19 条。

## 其他配图

| 目录 / 文件 | 内容 |
| --- | --- |
| `IMG/` | 界面截图等文档配图 |
| 本目录下的 `*.png` / `*.svg` | 故障档案（`Docs/TroubleShooting/`）引用的证据图 |
