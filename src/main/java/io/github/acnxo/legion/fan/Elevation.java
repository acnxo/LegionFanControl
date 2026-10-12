package io.github.acnxo.legion.fan;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 管理员权限自提升。
 *
 * <p><b>为什么需要</b>：本程序访问的联想 WMI（{@code root\WMI} 下的 {@code LENOVO_*}
 * 类）在非管理员上下文中会直接返回「拒绝访问」，表现为<b>转速与温度读不到、
 * 「降温除尘」按钮不可用</b>。因此启动时若检测到未提权，本类会尝试通过 UAC
 * 以管理员身份重启自身 —— 这样无论用户是双击 jar、双击启动脚本，还是命令行运行，
 * 都能拿到权限。
 *
 * <p><b>安全设计（避免死循环与误判）</b>：
 * <ul>
 *   <li>重启时向新进程传入 {@link #FLAG_NO_ELEVATE} 标记，带此标记的实例
 *       <b>一律不再尝试提权</b>，从根本上杜绝「提权 → 判定失败 → 再提权」的无限循环；</li>
 *   <li>任何一步失败（非 jar 方式启动、用户取消 UAC、等待超时）都返回 {@code false}，
 *       程序照常以当前权限启动 —— 行为与未引入本功能时完全一致，
 *       因此本类<b>不会导致程序无法启动</b>。</li>
 * </ul>
 */
final class Elevation {

    /** 传给「已提权实例」的标记参数：带此参数时不再尝试提权，用于防止循环 */
    static final String FLAG_NO_ELEVATE = "--no-elevate";

    /** 等待提权进程拉起的秒数；超时即视为放弃提权（例如用户在 UAC 弹窗上迟迟不响应） */
    private static final int TIMEOUT_SECONDS = 30;

    /** 探测权限时等待 powershell 返回的秒数 */
    private static final int PROBE_TIMEOUT_SECONDS = 6;

    private Elevation() {
    }

    /**
     * 判断当前进程是否具备管理员权限。
     *
     * @return true 表示已提权
     */
    static boolean isElevated() {
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent())"
                            + ".IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS) && out.equalsIgnoreCase("True");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 若未提权，则以管理员身份重启自身。
     *
     * <p>用 {@code javaw.exe} 启动（而非 {@code java.exe}），以保持双击 jar 时
     * 「只有界面窗口、没有多余控制台」的观感。
     *
     * @return true 表示已成功交棒给提权后的新进程，调用方应<b>立即退出</b>；
     *         false 表示无需提权或提权未成功，调用方应继续正常启动
     */
    static boolean relaunchElevatedIfNeeded() {
        if (isElevated()) {
            return false;
        }
        String jar = currentJarPath();
        if (jar == null) {
            // 非 jar 方式启动（例如 IDE 里直接跑 classes 目录），无从重建启动命令，放弃提权
            return false;
        }
        try {
            String javaExe = new File(new File(System.getProperty("java.home"), "bin"), "javaw.exe")
                    .getAbsolutePath();
            String arguments = "-jar \"" + jar + "\" --enable-native-access=ALL-UNNAMED "
                    + FLAG_NO_ELEVATE;
            String command = "Start-Process -FilePath '" + psQuote(javaExe)
                    + "' -Verb RunAs -ArgumentList '" + psQuote(arguments) + "'";
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", command);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            // 用户取消 UAC 时 Start-Process 会以非 0 退出，此处即视为放弃提权
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 取当前运行体的 jar 绝对路径。
     *
     * @return jar 路径；若以 classes 目录方式运行（非 jar）则返回 null
     */
    private static String currentJarPath() {
        try {
            File f = new File(Elevation.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return f.isFile() && f.getName().toLowerCase().endsWith(".jar")
                    ? f.getAbsolutePath() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** PowerShell 单引号字符串转义：内容里的单引号需写成两个 */
    private static String psQuote(String s) {
        return s.replace("'", "''");
    }
}
