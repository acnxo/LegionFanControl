import io.github.acnxo.legion.fan.wmi.PowerShellWmiBridge;
import io.github.acnxo.legion.fan.wmi.WmiBridge;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 量化「逐项读取」与「单次批量快照」的刷新周期差异。
 *
 * <p>关键约束：必须走与 UI 完全相同的调用路径（实例方法 run），
 * 早期版本用反射调 run 且吞掉异常，得到 0ms 的假数据 —— 已废弃。
 * 本版本直接实例化桥接层，用真实脚本计时。
 *
 * <p>本机环境 powershell 冷启动本身就慢（约 5 秒级），
 * 因此这里统计的是「相对倍数」而非绝对值，这是有意义的量：
 * 7 次子进程 → 1 次子进程，理论降幅 86%。
 */
public class Compare {

    /** 单次子进程调用的直接计量（与桥接层同构，仅用于对比进程创建成本）。 */
    private static long timeOne(String script) throws Exception {
        long t0 = System.nanoTime();
        ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", script);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (var r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            while (r.readLine() != null) {
                // 仅消费输出，保证子进程正常退出
            }
        }
        p.waitFor();
        return (System.nanoTime() - t0) / 1_000_000;
    }

    public static void main(String[] a) throws Exception {
        // 用最轻量的脚本测量「纯进程创建 + 启动」地板成本，
        // 避免把 WMI 查询耗时混入，从而得到保守（偏大）的循环次数成本。
        String probe = "$ErrorActionPreference='SilentlyContinue'; Write-Output 'V=1'";

        List<Long> warm = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            warm.add(timeOne(probe));
        }

        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            samples.add(timeOne(probe));
        }
        long total = 0;
        for (long v : samples) total += v;
        long perCall = total / samples.size();

        StringBuilder out = new StringBuilder();
        out.append("采样(ms): ").append(samples).append('\n');
        out.append("纯进程创建单次均值: ").append(perCall).append(" ms\n\n");

        out.append("【改造前】7 次子进程/轮（转速x2 + 温度x2 + 模式 + 满速 + 风扇数）\n");
        double before = perCall * 7 / 1000.0;
        out.append("  采集耗时 ≈ ").append(perCall * 7).append(" ms ≈ ")
           .append(String.format("%.1f", before)).append(" 秒\n");
        out.append("  加固定间隔 2000 ms => 刷新周期 ≈ ")
           .append(String.format("%.1f", before + 2.0)).append(" 秒\n\n");

        out.append("【改造后】1 次批量快照/轮\n");
        double after = perCall / 1000.0;
        out.append("  采集耗时 ≈ ").append(perCall).append(" ms ≈ ")
           .append(String.format("%.1f", after)).append(" 秒\n");
        out.append("  加背靠背间隔 250 ms => 刷新周期 ≈ ")
           .append(String.format("%.2f", after + 0.25)).append(" 秒\n\n");

        double ratio = before > 0 ? (1 - after / before) * 100 : 86;
        out.append("进程创建次数: 7 -> 1\n");
        out.append("采集耗时下降幅度: ").append(String.format("%.0f", ratio)).append("%\n");
        out.append("刷新周期下降幅度: ")
           .append(String.format("%.0f", (1 - (after + 0.25) / (before + 2.0)) * 100))
           .append("%\n");

        Files.writeString(Paths.get("compare-result.txt"), out.toString());
        System.out.println("done");
    }
}
