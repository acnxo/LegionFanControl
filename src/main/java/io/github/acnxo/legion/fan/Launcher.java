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
 * <p><b>4. 控制台窗口（默认不创建）</b>：原生包以 <b>GUI 子系统</b> 链接（pom 的
 * {@code /SUBSYSTEM:WINDOWS}），Windows <b>不会</b>为它创建控制台窗口 —— 双击、右键「以管理员身份运行」
 * 都只有界面，也不存在「先弹黑框再消失」的一闪（这是链接期定下的，与运行时调用无关）。
 * 需要日志时用 {@code --console} / {@code -Dlegion.console=true} 显式取得控制台；
 * 从 cmd / PowerShell 启动时日志打在<b>启动者</b>的窗口里，那个窗口不属于本进程，绝不隐藏、不乱动。
 * 详见 {@link ConsoleWindow}。
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        // 控制台：原生包以 GUI 子系统链接，默认根本没有控制台窗口（不是“隐藏”）；需要日志时用 --console。
        // 放在最前面：--console 分配控制台要早于任何输出与 JavaFX 初始化，否则早期日志会丢。
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
