package io.github.acnxo.legion.fan.wmi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 桥接实现之一：通过短生命周期的 powershell.exe 子进程调用联想 WMI 方法
 * （每次方法调用批量启动一个子进程）。
 *
 * <p>调用路径与 2026-09-27 的提权探查结果保持一致：
 * <ul>
 *   <li>风扇转速：GAMEZONE 的 GetFan1Speed / GetFan2Speed，
 *       回退到 FAN_METHOD 的 Fan_GetCurrentFanSpeed（FanID 取 0 / 1）；</li>
 *   <li>满速开关：主通路为 FAN_METHOD 的 Fan_Set_FullSpeed(bool)，
 *       回退到 GAMEZONE 的 SetFanCooling(1/0)；</li>
 *   <li>温度：GetCPUTemp / GetGPUTemp（本机 IAH7H 返回 0 无效，
 *       待 probe v2 确认后改用 Fan_GetCurrentSensorTemperature）。</li>
 * </ul>
 *
 * <p>整个程序必须以管理员权限运行，否则 WMI 会返回「拒绝访问」。
 */
public class PowerShellWmiBridge implements WmiBridge {

    private static final String NS = "root\\WMI";
    private static final String GZ = "(Get-WmiObject -Namespace " + NS
            + " -Class LENOVO_GAMEZONE_DATA | Select-Object -First 1)";
    private static final String FM = "(Get-WmiObject -Namespace " + NS
            + " -Class LENOVO_FAN_METHOD | Select-Object -First 1)";
    /** CIM(WMI v2) 取 LENOVO_GAMEZONE_DATA 的首个实例（用于智能风扇模式等 GAMEZONE 独有方法） */
    private static final String GZ_CIM = "@(Get-CimInstance -Namespace '" + NS
            + "' -ClassName LENOVO_GAMEZONE_DATA)[0]";
    /** CIM(WMI v2) 取 LENOVO_FAN_METHOD 的首个实例 */
    private static final String FM_CIM = "@(Get-CimInstance -Namespace '" + NS
            + "' -ClassName LENOVO_FAN_METHOD)[0]";
    /** CIM(WMI v2) 取 LENOVO_OTHER_METHOD 的首个实例 */
    private static final String OM_CIM = "@(Get-CimInstance -Namespace '" + NS
            + "' -ClassName LENOVO_OTHER_METHOD)[0]";
    private static final long TIMEOUT_MS = 6000;

    // ==================== 权威规格（来源：LenovoLegionToolkit 源码） ====================
    // 参考文件：
    //   LenovoLegionToolkit.Lib/System/Management/WMI.LenovoFanMethod.cs
    //   LenovoLegionToolkit.Lib/System/Management/WMI.LenovoOtherMethod.cs
    //   LenovoLegionToolkit.Lib/Controllers/Sensors/SensorsControllerV4.cs
    //   LenovoLegionToolkit.Lib/Enums.cs (CapabilityID)
    //
    // 关键纠正（早期版本全部猜错，导致读数恒为 0）：
    //   1. Fan_GetCurrentFanSpeed 返回属性名为 CurrentFanSpeed（不是 Data/Speed）；
    //   2. 参数名为 FanID（byte）；
    //   3. Fan_Get_FullSpeed 返回属性名为 Status，且类型是 bool；
    //   4. Fan_Set_FullSpeed 的参数名为 Status，类型是 int（不是 bool！）；
    //   5. 温度与风扇转速在现代机型上统一走
    //      LENOVO_OTHER_METHOD.GetFeatureValue(IDs=<CapabilityID>)，返回属性名为 Value。
    // 由于 .Data/.Speed 并不存在，[int]$null 的结果恒为 0 —— 这就是「显示 0 RPM」的根因。

    /** CapabilityID 取值（对应 LENOVO_OTHER_METHOD.GetFeatureValue 的 IDs 参数） */
    private static final long CAP_CPU_TEMP = 0x05040000L;   // CPU 温度
    private static final long CAP_GPU_TEMP = 0x05050000L;   // GPU 温度
    private static final long CAP_CPU_FAN_SPEED = 0x04030001L; // CPU 风扇转速
    private static final long CAP_GPU_FAN_SPEED = 0x04030002L; // GPU 风扇转速
    private static final long CAP_FAN_FULL_SPEED = 0x04020000L; // 满速开关

    /**
     * 通过 {@code LENOVO_OTHER_METHOD.GetFeatureValue} 读取能力值。
     *
     * <p>这是现代 Legion 机型读取温度/转速的<b>首选通路</b>：
     * 参数 {@code IDs} 为 CapabilityID，返回内嵌对象的 {@code Value} 属性。
     */
    private static String featureValueScript(long capabilityId) {
        return "$ErrorActionPreference='Stop'; $o=" + OM_CIM + "; "
                + "$r=Invoke-CimMethod -InputObject $o -MethodName GetFeatureValue "
                + "-Arguments @{IDs=[int]" + capabilityId + "}; "
                + "Write-Output ('V=' + [int]$r.Value)";
    }

    /** 通过 FAN_METHOD.Fan_GetCurrentFanSpeed(FanID) 读转速；返回属性名 CurrentFanSpeed */
    private static String fanSpeedByFanMethodScript(int fanId) {
        return "$ErrorActionPreference='Stop'; $i=" + FM_CIM + "; "
                + "$r=Invoke-CimMethod -InputObject $i -MethodName Fan_GetCurrentFanSpeed "
                + "-Arguments @{FanID=[byte]" + fanId + "}; "
                + "Write-Output ('V=' + [int]$r.CurrentFanSpeed)";
    }

    /** 通过 FAN_METHOD.Fan_GetCurrentSensorTemperature(SensorID) 读温度；属性名 CurrentSensorTemperature */
    private static String sensorTempScript(int sensorId) {
        return "$ErrorActionPreference='Stop'; $i=" + FM_CIM + "; "
                + "$r=Invoke-CimMethod -InputObject $i -MethodName Fan_GetCurrentSensorTemperature "
                + "-Arguments @{SensorID=[int]" + sensorId + "}; "
                + "Write-Output ('V=' + [int]$r.CurrentSensorTemperature)";
    }

    private static final String SCRIPT_SPEED_WMI_LEGACY =
            "$ErrorActionPreference='Stop'; $g=" + GZ + "; "
                    + "Write-Output ('V=' + $g.GetFan1Speed().Data.ToString() + ','"
                    + " + $g.GetFan2Speed().Data.ToString())";

    @Override
    public int getFanCount() throws WmiException {
        // 优先 CIM；旧版 Get-WmiObject 对 Lenovo 内嵌对象取不到值。
        try {
            String cim = "$ErrorActionPreference='Stop'; $i=" + GZ_CIM + "; "
                    + "$r=Invoke-CimMethod -InputObject $i -MethodName GetFanCount; "
                    + "Write-Output ('V=' + [int]$r.Data)";
            return parseInt(extractValue(run(cim)), "FanCount (CIM)");
        } catch (WmiException cimFailure) {
            String out = extractValue(run(GZ + ".GetFanCount() | Select-Object -ExpandProperty Data"));
            return parseInt(out, "FanCount (WMI v1)");
        }
    }

    /**
     * 开关满速模式。
     *
     * <p>权威签名（LLTK 实证）：
     * <pre>
     *   Fan_Set_FullSpeed(Status: int)     // 参数为 int，不是 bool！
     *   Fan_Get_FullSpeed() -> Status: bool
     * </pre>
     * 另有现代能力位 {@code CapabilityID.FanFullSpeed = 0x04020000}，
     * 可通过 {@code LENOVO_OTHER_METHOD.SetFeatureValue} 设置，作为备选通路。
     */
    @Override
    public int setFullSpeed(boolean on) throws WmiException {
        int status = on ? 1 : 0;
        StringBuilder errors = new StringBuilder();

        // 通路 1（权威首选）：FAN_METHOD.Fan_Set_FullSpeed(Status: int)
        String p1 = "$ErrorActionPreference='Stop'; $i=" + FM_CIM + "; "
                + "Invoke-CimMethod -InputObject $i -MethodName Fan_Set_FullSpeed "
                + "-Arguments @{Status=[int]" + status + "} | Out-Null; "
                + "Write-Output 'V=0'";
        try {
            return parseInt(extractValue(run(p1)), "Fan_Set_FullSpeed(Status)");
        } catch (WmiException e) {
            errors.append("[Fan_Set_FullSpeed: ").append(e.getMessage()).append("] ");
        }

        // 通路 2（现代能力位）：LENOVO_OTHER_METHOD.SetFeatureValue(CAP_FAN_FULL_SPEED, status)
        String p2 = "$ErrorActionPreference='Stop'; $o=" + OM_CIM + "; "
                + "Invoke-CimMethod -InputObject $o -MethodName SetFeatureValue "
                + "-Arguments @{IDs=[int]" + CAP_FAN_FULL_SPEED + "; value=[int]" + status + "} | Out-Null; "
                + "Write-Output 'V=0'";
        try {
            return parseInt(extractValue(run(p2)), "SetFeatureValue(FanFullSpeed)");
        } catch (WmiException e) {
            errors.append("[SetFeatureValue: ").append(e.getMessage()).append("] ");
        }

        // 通路 3（旧机型兜底）：GAMEZONE.SetFanCooling
        try {
            String legacy = extractValue(run(GZ + ".SetFanCooling(" + status
                    + ") | Select-Object -ExpandProperty ReturnValue"));
            return parseInt(legacy, "SetFanCooling ReturnValue");
        } catch (WmiException e) {
            errors.append("[SetFanCooling: ").append(e.getMessage()).append("]");
        }
        throw new WmiException("三条满速通路均失败: " + errors);
    }

    @Override
    public int resetToAuto() throws WmiException {
        // 关闭满速并交还 EC 自动控制。
        int rc = setFullSpeed(false);

        // 二次确认：读回满速标志，若仍处于满速则明确告警（避免假成功）
        try {
            if (getFullSpeed() == 1) {
                throw new WmiException("满速标志未清除，硬件可能仍处于满速状态");
            }
        } catch (WmiException probeFailure) {
            // 读回能力不可用时（部分机型不支持 Fan_Get_FullSpeed）不阻断流程
            if (probeFailure.getMessage() != null
                    && probeFailure.getMessage().contains("满速标志未清除")) {
                throw probeFailure;
            }
        }
        return rc;
    }

    @Override
    public int getFullSpeed() throws WmiException {
        // 权威签名：Fan_Get_FullSpeed() 返回属性 Status，类型为 bool。
        // 本方法统一归一化为 1（满速）/ 0（常规）。
        String cim = "$ErrorActionPreference='Stop'; $i=" + FM_CIM + "; "
                + "$r=Invoke-CimMethod -InputObject $i -MethodName Fan_Get_FullSpeed; "
                + "$v=$r.Status; if ($null -eq $v) { $v=$r.Data }; "
                + "if ($v -is [bool]) { if ($v) { $v=1 } else { $v=0 } }; "
                + "Write-Output ('V=' + [int]$v)";
        try {
            return parseInt(extractValue(run(cim)), "Fan_Get_FullSpeed (CIM)");
        } catch (WmiException cimFailure) {
            // 备选：现代能力位 FanFullSpeed
            String cap = "$ErrorActionPreference='Stop'; $o=" + OM_CIM + "; "
                    + "$r=Invoke-CimMethod -InputObject $o -MethodName GetFeatureValue "
                    + "-Arguments @{IDs=[int]" + CAP_FAN_FULL_SPEED + "}; "
                    + "Write-Output ('V=' + [int]$r.Value)";
            return parseInt(extractValue(run(cap)), "GetFeatureValue(FanFullSpeed)");
        }
    }

    @Override
    public int[] readFanSpeeds() throws WmiException {
        // 转速读取通路（按权威度排序）：
        //   1. LENOVO_OTHER_METHOD.GetFeatureValue(CapabilityID.Cpu/GpuCurrentFanSpeed)
        //      —— 现代机型首选，属性名 Value；
        //   2. FAN_METHOD.Fan_GetCurrentFanSpeed(FanID)，属性名 CurrentFanSpeed；
        //   3. GAMEZONE.GetFan1Speed/GetFan2Speed（WMI v1，老机型兜底）。
        StringBuilder errors = new StringBuilder();

        // 通路 1：CapabilityID（CpuCurrentFanSpeed=0x04030001 / GpuCurrentFanSpeed=0x04030002）
        try {
            int cpu = parseInt(extractValue(run(featureValueScript(CAP_CPU_FAN_SPEED))),
                    "CpuCurrentFanSpeed");
            int gpu = parseInt(extractValue(run(featureValueScript(CAP_GPU_FAN_SPEED))),
                    "GpuCurrentFanSpeed");
            return new int[]{cpu, gpu};
        } catch (WmiException e) {
            errors.append("[CapabilityID: ").append(e.getMessage()).append("] ");
        }

        // 通路 2：FAN_METHOD.Fan_GetCurrentFanSpeed(FanID 0/1)
        try {
            int f0 = parseInt(extractValue(run(fanSpeedByFanMethodScript(0))),
                    "Fan_GetCurrentFanSpeed(0)");
            int f1 = parseInt(extractValue(run(fanSpeedByFanMethodScript(1))),
                    "Fan_GetCurrentFanSpeed(1)");
            return new int[]{f0, f1};
        } catch (WmiException e) {
            errors.append("[Fan_Method: ").append(e.getMessage()).append("] ");
        }

        // 通路 3：WMI v1 旧路径（兼容老机型）
        try {
            return parsePair(extractValue(run(SCRIPT_SPEED_WMI_LEGACY)), "fan speeds (WMI v1 legacy)");
        } catch (WmiException e) {
            errors.append("[WMI-v1: ").append(e.getMessage()).append("]");
        }
        throw new WmiException("三条转速通路均失败: " + errors);
    }

    @Override
    public int[] readTemps() throws WmiException {
        // 温度读取通路（按权威度排序）：
        //   1. LENOVO_OTHER_METHOD.GetFeatureValue(CapabilityID.Cpu/GpuCurrentTemperature)
        //      —— 现代机型首选（CpuCurrentTemperature=0x05040000 / GpuCurrentTemperature=0x05050000）；
        //   2. FAN_METHOD.Fan_GetCurrentSensorTemperature(SensorID) —— 传感器维度；
        //   3. GAMEZONE.GetCPUTemp/GetGPUTemp（旧机型，本机返回 0 无效）。
        StringBuilder errors = new StringBuilder();

        // 通路 1：CapabilityID
        try {
            int cpu = parseInt(extractValue(run(featureValueScript(CAP_CPU_TEMP))),
                    "CpuCurrentTemperature");
            int gpu = parseInt(extractValue(run(featureValueScript(CAP_GPU_TEMP))),
                    "GpuCurrentTemperature");
            return new int[]{cpu, gpu};
        } catch (WmiException e) {
            errors.append("[CapabilityID: ").append(e.getMessage()).append("] ");
        }

        // 通路 2：传感器温度（probe v1 显示 Sensor_ID=3/4 对应 CPU/GPU 维度）
        try {
            int cpu = parseInt(extractValue(run(sensorTempScript(3))), "SensorTemperature(3)");
            int gpu = parseInt(extractValue(run(sensorTempScript(4))), "SensorTemperature(4)");
            return new int[]{cpu, gpu};
        } catch (WmiException e) {
            errors.append("[SensorTemp: ").append(e.getMessage()).append("] ");
        }

        // 通路 3：旧机型 GAMEZONE
        try {
            String script = "$g=" + GZ + "; "
                    + "Write-Output ('V=' + $g.GetCPUTemp().Data.ToString() + ','"
                    + " + $g.GetGPUTemp().Data.ToString())";
            return parsePair(extractValue(run(script)), "temperatures (WMI v1)");
        } catch (WmiException e) {
            errors.append("[GAMEZONE: ").append(e.getMessage()).append("]");
        }
        throw new WmiException("三条温度通路均失败: " + errors);
    }

    @Override
    public int getSmartFanMode() throws WmiException {
        try {
            String cim = "$ErrorActionPreference='Stop'; $i=" + GZ_CIM + "; "
                    + "$o=Invoke-CimMethod -InputObject $i -MethodName GetSmartFanMode; "
                    + "$v=$o.Data; if ($null -eq $v) { $v=$o.ReturnValue }; "
                    + "Write-Output ('V=' + [int]$v)";
            return parseInt(extractValue(run(cim)), "SmartFanMode (CIM)");
        } catch (WmiException cimFailure) {
            String out = extractValue(run(GZ + ".GetSmartFanMode() | Select-Object -ExpandProperty Data"));
            return parseInt(out, "SmartFanMode (WMI v1)");
        }
    }

    /**
     * 单次子进程批量采集全部指标。
     *
     * <p>性能关键：桥接层每次 {@code run()} 都要新建一个 powershell.exe，
     * 而进程创建是主要开销（实测空启动即数秒）。逐项读取会让每轮轮询付出
     * 6~7 次进程创建成本，刷新周期被拖到数十秒。本方法把一轮所需全部读取
     * 合并进<b>同一个脚本</b>，只付一次进程创建成本。
     *
     * <p>脚本对每个指标单独 try/catch，任一失败只把该项记为 -1，
     * 不会因为单项不支持而丢掉整批数据。
     */
    @Override
    public Snapshot readSnapshot() throws WmiException {
        long t0 = System.nanoTime();
        String line = extractValue(run(buildSnapshotScript()));
        long costMs = (System.nanoTime() - t0) / 1_000_000;

        String[] p = line.split(",");
        if (p.length != 6) {
            throw new WmiException("批量快照解析失败，期望 6 个字段，实际 " + p.length + "：'" + line + "'");
        }
        return new Snapshot(
                parseLoose(p[0]), parseLoose(p[1]), parseLoose(p[2]),
                parseLoose(p[3]), parseLoose(p[4]), parseLoose(p[5]), costMs);
    }

    /**
     * 拼装批量采集脚本（独立成方法，便于单元校验其语法与内容）。
     *
     * <p>设计要点：
     * <ul>
     *   <li>所有实例只枚举一次，供各指标复用，避免重复连接 WMI；</li>
     *   <li>每个指标包在 {@code Get-V} 的 try/catch 中，单项失败只记 -1；</li>
     *   <li>转速/温度均先试 CapabilityID，取不到再退到 FAN_METHOD 的对应方法；</li>
     *   <li>输出严格为 {@code V=m1,m2,m3,m4,m5,m6} 单行，便于 Java 侧解析。</li>
     * </ul>
     */
    static String buildSnapshotScript() {
        StringBuilder s = new StringBuilder();
        s.append("$ErrorActionPreference='SilentlyContinue';");
        // 每个指标独立 try/catch，失败记 -1
        s.append("function Get-V($fn){ try { $r=&$fn; if($null -eq $r){-1}else{[int]$r} } catch { -1 } };");
        // 实例（各取第一个，复用）
        s.append("$fm=@(Get-CimInstance -Namespace '").append(NS).append("' -ClassName LENOVO_FAN_METHOD)[0];");
        s.append("$om=@(Get-CimInstance -Namespace '").append(NS).append("' -ClassName LENOVO_OTHER_METHOD)[0];");
        s.append("$gz=@(Get-CimInstance -Namespace '").append(NS).append("' -ClassName LENOVO_GAMEZONE_DATA)[0];");
        // 风扇转速：优先 CapabilityID，回退 Fan_GetCurrentFanSpeed
        s.append("$f1=Get-V { $x=$null; if($om){$x=(Invoke-CimMethod -InputObject $om -MethodName GetFeatureValue -Arguments @{IDs=[int]")
                .append(CAP_CPU_FAN_SPEED).append("}).Value}; if($null -eq $x -or $x -lt 1){")
                .append(" if($fm){$x=(Invoke-CimMethod -InputObject $fm -MethodName Fan_GetCurrentFanSpeed -Arguments @{FanID=[byte]0}).CurrentFanSpeed}}; $x };");
        s.append("$f2=Get-V { $x=$null; if($om){$x=(Invoke-CimMethod -InputObject $om -MethodName GetFeatureValue -Arguments @{IDs=[int]")
                .append(CAP_GPU_FAN_SPEED).append("}).Value}; if($null -eq $x -or $x -lt 1){")
                .append(" if($fm){$x=(Invoke-CimMethod -InputObject $fm -MethodName Fan_GetCurrentFanSpeed -Arguments @{FanID=[byte]1}).CurrentFanSpeed}}; $x };");
        // 温度：优先 CapabilityID，回退传感器温度
        s.append("$ct=Get-V { $x=$null; if($om){$x=(Invoke-CimMethod -InputObject $om -MethodName GetFeatureValue -Arguments @{IDs=[int]")
                .append(CAP_CPU_TEMP).append("}).Value}; if($null -eq $x -or $x -lt 1){")
                .append(" if($fm){$x=(Invoke-CimMethod -InputObject $fm -MethodName Fan_GetCurrentSensorTemperature -Arguments @{SensorID=[int]3}).CurrentSensorTemperature}}; $x };");
        s.append("$gt=Get-V { $x=$null; if($om){$x=(Invoke-CimMethod -InputObject $om -MethodName GetFeatureValue -Arguments @{IDs=[int]")
                .append(CAP_GPU_TEMP).append("}).Value}; if($null -eq $x -or $x -lt 1){")
                .append(" if($fm){$x=(Invoke-CimMethod -InputObject $fm -MethodName Fan_GetCurrentSensorTemperature -Arguments @{SensorID=[int]4}).CurrentSensorTemperature}}; $x };");
        // 智能风扇模式
        s.append("$sm=Get-V { if($gz){(Invoke-CimMethod -InputObject $gz -MethodName GetSmartFanMode).Data} else {-1} };");
        // 满速状态：Fan_Get_FullSpeed 的 Status 是 bool，归一化为 1/0
        s.append("$fs=Get-V { if($fm){ $v=(Invoke-CimMethod -InputObject $fm -MethodName Fan_Get_FullSpeed).Status;")
                .append(" if($v -is [bool]){ if($v){1}else{0} } else { [int]$v } } else { -1 } };");
        s.append("Write-Output ('V=' + $f1 + ',' + $f2 + ',' + $ct + ',' + $gt + ',' + $sm + ',' + $fs)");
        return s.toString();
    }

    /** 宽松解析：非数字（含空串）一律视为 -1，不抛异常，保证批量快照不因单项脏值整体失败。 */
    private static int parseLoose(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public String name() {
        return "powershell-bridge";
    }

    private String run(String psScript) throws WmiException {
        ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", psScript);
        pb.redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new WmiException("failed to start powershell", e);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line.trim()).append('\n');
            }
            if (!p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new WmiException("powershell call timed out: " + psScript);
            }
        } catch (IOException e) {
            throw new WmiException("failed reading powershell output", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WmiException("interrupted while waiting for powershell", e);
        }
        if (p.exitValue() != 0) {
            throw new WmiException("powershell exit=" + p.exitValue() + " out=" + sb);
        }
        // 关键修正：早期版本在此处只取第一个换行前的内容，
        // 一旦 PowerShell 输出任何前置行（警告/进度/空行），真实结果就被丢弃，
        // 表现为「转速读取失败」。此处保留完整输出，由 extractValue 定位有效行。
        return sb.toString().trim();
    }

    /**
     * 从 PowerShell 输出中提取有效值。
     *
     * <p>优先取带 {@code V=} 前缀的行（脚本显式约定），
     * 否则取最后一行非空输出 —— 避免被前置警告行干扰。
     */
    private String extractValue(String raw) {
        String[] lines = raw.split("\\R");
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("V=")) {
                return t.substring(2).trim();
            }
        }
        for (int i = lines.length - 1; i >= 0; i--) {
            String t = lines[i].trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        return "";
    }

    private int parseInt(String s, String what) throws WmiException {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new WmiException("cannot parse " + what + " from '" + s + "'", e);
        }
    }

    private int[] parsePair(String s, String what) throws WmiException {
        String[] parts = s.split(",");
        if (parts.length != 2) {
            throw new WmiException("cannot parse " + what + " from '" + s + "'");
        }
        return new int[]{parseInt(parts[0], what), parseInt(parts[1], what)};
    }
}
