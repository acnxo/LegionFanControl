package io.github.acnxo.legion.fan.wmi;

/**
 * 桥接实现之二（后续迭代，尚未实现）：通过 JNA 直接以 COM 调用 WMI 方法。
 *
 * <p>调用链为 com.sun.jna.platform.win32.COM + WbemProx：
 * IWbemLocator -> IWbemServices -> GetObject（LENOVO_GAMEZONE_DATA /
 * LENOVO_FAN_METHOD）-> InvokeMethod。
 *
 * <p>引入动机：去掉每次调用都启动 powershell.exe 子进程的开销
 * （每次约 150~400 毫秒），从而支持流畅的转速轮询。
 *
 * <p>该实现与 GraalVM 原生镜像方向一同后置（2026-09-27 决策），
 * 当前基线由 {@link PowerShellWmiBridge} 承担。
 */
public class JnaComWmiBridge implements WmiBridge {

    private static final String NOT_IMPLEMENTED =
            "JnaComWmiBridge 已随原生镜像方向后置（基线阶段不实现）";

    @Override
    public int getFanCount() throws WmiException {
        throw new WmiException(NOT_IMPLEMENTED);
    }

    @Override
    public int setFullSpeed(boolean on) throws WmiException {
        throw new WmiException(NOT_IMPLEMENTED);
    }

    @Override
    public int resetToAuto() throws WmiException {
        throw new WmiException(NOT_IMPLEMENTED);
    }

    @Override
    public int getFullSpeed() throws WmiException {
        throw new WmiException(NOT_IMPLEMENTED);
    }

    @Override
    public int[] readFanSpeeds() throws WmiException {
        throw new WmiException(NOT_IMPLEMENTED);
    }

    @Override
    public int[] readTemps() throws WmiException {
        throw new WmiException(NOT_IMPLEMENTED);
    }

    @Override
    public int getSmartFanMode() throws WmiException {
        throw new WmiException(NOT_IMPLEMENTED);
    }

    @Override
    public Snapshot readSnapshot() throws WmiException {
        throw new WmiException(NOT_IMPLEMENTED);
    }

    @Override
    public String name() {
        return "jna-com-bridge";
    }
}
