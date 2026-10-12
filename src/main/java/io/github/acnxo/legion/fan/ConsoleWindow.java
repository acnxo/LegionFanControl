package io.github.acnxo.legion.fan;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/**
 * 控制台窗口管理：**默认为空**（不创建、不显示），需要看日志时用开关显式分配一个控制台。
 *
 * <p><b>为什么默认不需要“隐藏”动作</b>：原生包现在是按 **GUI 子系统** 链接的
 * （pom 的 `-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS`），Windows **根本不会**为它创建控制台窗口。
 * 因此双击、右键「以管理员身份运行」、快捷方式启动 —— 一律只会出现界面，不存在「先弹黑框」这一步，
 * 也没有「窗口已创建、再赶去隐藏」的时序问题与一闪。
 *
 * <p><b>日志去哪了</b>：
 * <ul>
 *   <li>从 cmd / PowerShell 运行 exe（或 CI 重定向输出）时，stdout 由启动者提供，`渲染诊断：…` 照旧打印；
 *       区别只是命令行不会“等”这个 GUI 程序结束（需要等待就用 `start /wait`）；</li>
 *   <li>双击等没有控制台的情形，日志无处可去 —— 要看日志就加开关（见下），或从命令行运行。</li>
 * </ul>
 *
 * <p><b>开关</b>：
 * <pre>
 * 直接启动（双击 / 右键管理员运行 / 快捷方式）   不创建控制台，只有界面
 * --console                                     分配一个控制台窗口并把日志重定向进去
 * -Dlegion.console=true                         同上（可写进快捷方式的「目标」，双击也能带）
 * 已有控制台时（cmd / 脚本 / JVM 版）            不动作 —— 那本来就是启动者的窗口，绝不乱动
 * </pre>
 *
 * <p><b>实现</b>：用 JDK 自带的 FFM（{@code java.lang.foreign}）调 Win32 的
 * {@code GetConsoleWindow} / {@code AttachConsole} / {@code AllocConsole}，
 * 再用 `CONOUT$` 把 `System.out` / `System.err` 重新绑到控制台。
 * 这些调用**只服务于 `--console` 这条可选路径**：失败只记状态、不抛异常，且**不影响默认行为** ——
 * 默认“没有控制台”是链接期就定下的，不依赖任何运行时调用。
 */
final class ConsoleWindow {

    /** 命令行开关：分配控制台窗口 */
    private static final String ARG_CONSOLE = "--console";

    /** 系统属性开关：适合快捷方式的「目标」栏 */
    private static final String PROP_CONSOLE = "legion.console";

    /** AttachConsole 的取值：接管父进程的控制台。**必须是 int**，它会被当作 32 位 DWORD 传给原生函数（invokeExact 要求参数类型精确匹配，用 long 会抛 WrongMethodTypeException） */
    private static final int ATTACH_PARENT_PROCESS = -1;

    private static MethodHandle getConsoleWindow;
    private static MethodHandle attachConsole;
    private static MethodHandle allocConsole;

    private static volatile String state = "未判定";

    private ConsoleWindow() {
    }

    /**
     * 在 {@code main} 的第一条语句调用：默认什么都不做；只有显式要求时才分配控制台。
     *
     * @param args {@code main} 收到的参数，用于识别 {@code --console}
     */
    static void applyStartupVisibility(String[] args) {
        try {
            boolean wanted = consoleRequested(args);

            if (!isWindows()) {
                state = "不介入（非 Windows）";
                report();
                return;
            }

            if (consoleWindowHandle() != 0L) {
                // 已经有控制台：自己就是控制台程序（JVM 版），或由 cmd / CI 启动 —— 那是别人的窗口，不动它
                state = "已有控制台（沿用启动者的窗口）";
                report();
                return;
            }

            if (!wanted) {
                state = "默认：不创建控制台（exe 以 GUI 子系统构建）";
                report();
                return;
            }

            // 显式要求控制台：优先接管父控制台（从命令行启动时），否则新开一个
            boolean attached = attachParentConsole();
            boolean allocated = false;
            if (!attached) {
                allocated = allocConsole();
            }
            boolean rebound = rebindConsoleStreams();
            state = (attached ? "已接管父控制台" : allocated ? "已新建控制台" : "分配控制台失败")
                    + (rebound ? "，日志已重定向到控制台" : "，但日志重定向失败");
            report();
        } catch (Throwable t) {
            // 只记状态，绝不抛：`--console` 是可选便利，不能阻断启动
            state = "不介入(" + t.getClass().getSimpleName() + ")";
            report();
        }
    }

    /** @return 本次判定结果，供自检与文档核对 */
    static String state() {
        return state;
    }

    private static void report() {
        System.out.println("控制台窗口：" + state);
    }

    private static boolean consoleRequested(String[] args) {
        for (String arg : args) {
            if (ARG_CONSOLE.equalsIgnoreCase(arg)) {
                return true;
            }
        }
        return Boolean.parseBoolean(System.getProperty(PROP_CONSOLE, "false"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** @return 已附加的控制台窗口句柄；0 表示当前没有控制台 */
    private static long consoleWindowHandle() throws Throwable {
        initApi();
        MemorySegment hwnd = (MemorySegment) getConsoleWindow.invokeExact();
        return hwnd.address();
    }

    /** @return true 表示接管了父进程的控制台（即“从命令行启动”这一情形） */
    private static boolean attachParentConsole() throws Throwable {
        initApi();
        return ((int) attachConsole.invokeExact(ATTACH_PARENT_PROCESS)) != 0;
    }

    /** @return true 表示新建控制台成功 */
    private static boolean allocConsole() throws Throwable {
        initApi();
        return ((int) allocConsole.invokeExact()) != 0;
    }

    /**
     * 把 Java 的标准输出 / 错误重新绑到控制台设备 `CONOUT$`。
     *
     * <p>只有显式要求控制台时才会走到这里：启动时没有控制台的进程，其 stdout 句柄是无效的，
     * 不重绑的话 `渲染诊断：…` 依旧无处可去。
     *
     * @return true 表示重定向成功
     */
    private static boolean rebindConsoleStreams() {
        for (String device : new String[] {"CONOUT$", "\\\\.\\CONOUT$"}) {
            try {
                FileOutputStream out = new FileOutputStream(device);
                System.setOut(new PrintStream(out, true));
                System.setErr(new PrintStream(out, true));
                return true;
            } catch (Throwable ignored) {
                // 换下一个写法再试；全失败则返回 false（不影响程序运行）
            }
        }
        return false;
    }

    /** 惰性解析 Win32 符号（只在 Windows 上会成功）。 */
    private static synchronized void initApi() {
        if (allocConsole != null) {
            return;
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());

            getConsoleWindow = linker.downcallHandle(
                    kernel32.find("GetConsoleWindow").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
            attachConsole = linker.downcallHandle(
                    kernel32.find("AttachConsole").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            allocConsole = linker.downcallHandle(
                    kernel32.find("AllocConsole").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
        } catch (Throwable t) {
            throw new IllegalStateException("Win32 符号解析失败", t);
        }
    }
}
