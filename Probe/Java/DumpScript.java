import io.github.acnxo.legion.fan.wmi.PowerShellWmiBridge;
import java.lang.reflect.*;
import java.nio.file.*;

/** 导出 buildSnapshotScript() 的完整脚本文本，用于人工核对与 PowerShell 语法校验。 */
public class DumpScript {
    public static void main(String[] a) throws Exception {
        Method m = PowerShellWmiBridge.class.getDeclaredMethod("buildSnapshotScript");
        m.setAccessible(true);
        String script = (String) m.invoke(null);
        Path out = Paths.get("snapshot-script.ps1");
        Files.writeString(out, script);
        System.out.println("written lines=" + script.split("\n").length + " chars=" + script.length());
    }
}
