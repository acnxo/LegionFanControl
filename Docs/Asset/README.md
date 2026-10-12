# 资源来源与替换说明

本目录记录**非代码资源**的来源，便于日后替换或追溯授权。

## 程序图标

| 文件 | 用途 | 形态 |
| --- | --- | --- |
| `exe.ico`（仓库根） | 原生 exe 的**文件图标**（资源管理器 / 任务栏 / 快捷方式） | 单条 200×200 **PNG-in-ICO** 条目（7297 字节：6+16 字节 ICO 头 + 7275 字节 PNG 载荷） |
| `src/main/resources/io/github/acnxo/legion/fan/Logo.png` | JavaFX 的**窗口与任务栏图标**（`Stage#getIcons`） | 200×200 RGBA PNG |

| 项 | 内容 |
| --- | --- |
| 来源 | `https://www.logosc.cn/oss/icons/2022/06/28/b1d9ee9c71d2de652a0b50218c920a08.png` |
| 获取日期 | 2026-10-12 |
| 源文件 sha256 | `d954718091c2df8cf49faee08610107bf85d4da679d02ec25fdc1cfb8f432ef5`（7275 字节；`Logo.png` 与它逐字节相同） |
| exe 侧嵌入方式 | `exe.rc`（纯 ASCII）经 `rc.exe` 编译为 `exe.res`，再由 native-image 的 `-H:NativeLinkerOption` 交给 MSVC 链接器；`exe.res` 是构建产物，不入库 |

> **授权与商标提示**：图标取自公开图标库（logosc.cn）。**对外分发前请自行确认该来源的授权条款**；
> 若不便使用，替换步骤为：① 用新图覆盖 `Logo.png`（同名）；② 用新图重新封装 `exe.ico`
> （单条 PNG 条目即可：`ICONDIR` + `ICONDIRENTRY` + 原始 PNG 字节）；③ 无需改动代码与构建配置。

## 其他配图

| 目录 | 内容 |
| --- | --- |
| `IMG/` | 界面截图等文档配图 |
| 本目录下的 `*.png` / `*.svg` | 故障档案（`Docs/TroubleShooting/`）引用的证据图 |
