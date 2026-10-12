package io.github.acnxo.legion.fan;

import java.util.ArrayList;
import java.util.List;

/**
 * 非模块化 fat jar 的启动入口。
 *
 * <p>本类承担两件事：
 *
 * <p><b>1. 绕开模块化 JavaFX 检查（必须）</b>：Java 启动器在检测到「主类直接或间接继承
 * javafx.application.Application」时会强制走模块化 JavaFX 运行时检查路径，对非模块化
 * jar 直接抛出「缺少 JavaFX 运行时组件」。把一个 <b>不继承 Application</b> 的普通类作为
 * Main-Class，由它在运行时调用 {@code Application.launch()}，即可绕开该检查，
 * 让单 jar 内的 JavaFX 类与 native 库正常加载。
 *
 * <p><b>2. 管理员权限自提升（可选，失败不影响启动）</b>：本程序依赖联想 WMI，
 * 非管理员上下文下读不到转速与温度、功能按钮不可用。因此入口处先尝试提权，
 * 详见 {@link Elevation}。提权失败时不会阻断启动，仍按当前权限进入界面。
 *
 * <p><b>3. 原生镜像渲染默认值（issue #48）</b>：原生镜像内默认关闭 LCD 次像素文本合成 ——
 * 这是<b>临时缓解措施，非最终方案</b>（代码级根因尚未定位），开关与回退方式见
 * {@link NativeImageRenderDefaults}。该调用必须早于任何 JavaFX / Prism 类初始化，
 * 故置于本方法的第一条语句。
 *
 * <p><b>4. 隐藏双击自带的控制台窗口</b>：原生包是控制台子系统程序，双击时 Windows 会先开一个黑框。
 * 详见 {@link ConsoleWindow} —— 只隐藏「本进程独占的那一个」，由 cmd / `Dist\*.cmd` 启动时一律保持可见。
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        // 双击原生包时隐藏自带控制台窗口；从 cmd / Dist 脚本启动时保持可见（见 ConsoleWindow）。
        // 放在最前面：越早隐藏，双击时的一闪越短。
        ConsoleWindow.applyStartupVisibility(args);

        // 临时缓解措施（issue #48，非最终方案）：原生镜像内默认关闭 LCD 次像素合成。
        // 必须早于任何 JavaFX / Prism 类初始化；开关与回退方式见 NativeImageRenderDefaults 注释。
        NativeImageRenderDefaults.applyIfNativeImage();

        // 过滤掉内部标记参数，避免其被转发给 JavaFX
        List<String> forwarded = new ArrayList<>();
        boolean noElevate = false;
        for (String arg : args) {
            if (Elevation.FLAG_NO_ELEVATE.equals(arg)) {
                noElevate = true;
            } else {
                forwarded.add(arg);
            }
        }

        // 尝试以管理员身份重启自身；成功则本进程直接退出，由提权后的实例继续
        if (!noElevate && Elevation.relaunchElevatedIfNeeded()) {
            return;
        }

        FanControlApp.main(forwarded.toArray(new String[0]));
    }
}
