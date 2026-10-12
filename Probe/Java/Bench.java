import java.io.*;
import java.nio.charset.StandardCharsets;

/** 从 Java 侧实测单次 powershell 子进程调用开销（与本程序桥接层同路径）。 */
public class Bench {
    static long timeOne(String... cmd) throws Exception {
        long t0 = System.nanoTime();
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            while (r.readLine() != null) {
                // 仅消费输出
            }
        }
        p.waitFor();
        return (System.nanoTime() - t0) / 1_000_000;
    }

    public static void main(String[] a) throws Exception {
        String script = "$ErrorActionPreference='Stop'; $i=@(Get-CimInstance -Namespace 'root\\WMI'"
                + " -ClassName LENOVO_FAN_METHOD)[0]; $r=Invoke-CimMethod -InputObject $i"
                + " -MethodName Fan_GetCurrentFanSpeed -Arguments @{FanID=[byte]0};"
                + " Write-Output ('V=' + [int]$r.CurrentFanSpeed)";
        for (int i = 0; i < 2; i++) {
            timeOne("powershell", "-NoProfile", "-NonInteractive", "-Command", script);
        }
        StringBuilder sb = new StringBuilder();
        long total = 0;
        int n = 5;
        for (int i = 0; i < n; i++) {
            long ms = timeOne("powershell", "-NoProfile", "-NonInteractive", "-Command", script);
            sb.append(ms).append(' ');
            total += ms;
        }
        long avg = total / n;
        System.out.println("powershell single-call ms: " + sb);
        System.out.println("avg: " + avg + " ms");
        System.out.println("7 calls per round => " + (avg * 7 / 1000.0) + " s/round");
        System.out.println("plus 2000ms interval => " + (avg * 7 / 1000.0 + 2) + " s refresh");
    }
}
