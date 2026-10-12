# LegionFanControl

联想拯救者（Legion）风扇控制工具，用于**平替「联想电脑管家」里的「降温除尘」功能** —— 从而可以卸载电脑管家，同时保留一键除尘的能力。

---

## 环境要求

| 项目 | 要求 | 备注 |
|---|---|---|
| 笔记本电脑 | 联想拯救者 Legion 机型 |  |
| 操作系统 | Windows 10 / 11 |  |
| JDK | **25** | 运行JVM JAR 版本程序时需要 |
| 权限 | **管理员权限** | 运行期必需，程序会自动申请 |

程序通过调用 `powershell.exe` 访问 WMI，无需安装任何第三方驱动或运行库。

---

## 界面说明

![界面截图](https://github.com/user-attachments/assets/7c5af861-52b2-4f61-a9af-f21bdde86bd8)

> 各显示项、按钮状态与读数约定的完整说明见 [`Docs/UserInterface.md`](Docs/UserInterface.md)。

---

## 功能特性

| 功能 | 状态 | 说明 |
|---|---|---|
| 一键「降温除尘」 | 可用 | 固件级风扇满速；约 **2 分钟**后自动交还 EC 自动控制 |
| 120 秒倒计时 | 可用 | 到期自动复位，无需人工干预 |
| 双风扇转速实时显示 | 可用 | 带方位标注（左 / CPU 侧、右 / GPU 侧；方位为推断） |
| CPU / GPU 温度实时显示 | 可用 | 现代机型走 CapabilityID 通路 |
| 智能风扇模式显示 | 可用 | 安静 / 均衡 / 性能 / 自定义 |
| 硬件状态校正 | 可用 | 固件自动复位或外部改动后，界面状态自动同步 |
| 管理员权限自动获取 | 可用 | 启动时若未提权，自动请求 UAC 以管理员身份重启自身 |
| 单风扇定向控制 | **不支持** | 固件接口层面不支持（见[已知限制](Docs/LimitationsAndRoadmap.md#1-满速除尘是全局开关不支持单风扇定向控制)） |
| 自定义转速曲线（v2） | 未实现 | 需逆向 `Fan_Set_Table` 二进制布局 |

---

## 快速开始

### 直接使用

**双击 `Dist\Bootstrap.cmd` 即可。** 脚本已把运行所需的全部内容封装好：

| 脚本代劳的事项 | 说明 |
|---|---|
| JVM 参数 | 自动附加 `--enable-native-access=ALL-UNNAMED` |
| jar 路径 | 取与本脚本同目录的 `LegionFanControl.jar`，**不做版本号匹配** |
| 管理员权限 | 自动检测，不足时自动弹出 UAC 请求提权 |
| 环境检查 | jar 缺失、Java 不可用时给出明确提示 |

> `Dist/` 是**自包含文件夹**：整个目录拷贝到任意位置都能直接运行，不依赖源码工程。

**原生镜像版（`Dist\LegionFanControl.exe`）**：以 **GUI 子系统**链接，所以**根本不会出现控制台窗口** ——
双击、右键「以管理员身份运行」都只会看到界面。需要看 `渲染诊断：…` 日志时，从命令行运行它（日志会打在那个窗口里），
或加 `--console` / 把 `-Dlegion.console=true` 写进快捷方式「目标」（详见 [`Dist/README.md`](Dist/README.md)）。

**手动命令方式**（在管理员终端中执行）：

```bash
java --enable-native-access=ALL-UNNAMED -jar Dist/LegionFanControl.jar
```

### 关于管理员权限（重要）

> [!IMPORTANT]
> **必须以管理员身份运行**（程序会自动申请 UAC 提权，无需手动处理）。联想 WMI 提供程序在非管理员上下文下会直接返回「拒绝访问」，转速与温度全部无法读取。
> 详细说明见 [`Dist/README.md`](Dist/README.md) 的「为什么必须管理员权限」。

---

## 已知限制

完整清单与展开说明见 [`Docs/LimitationsAndRoadmap.md`](Docs/LimitationsAndRoadmap.md)。
