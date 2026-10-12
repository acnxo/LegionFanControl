package io.github.acnxo.legion.fan.wmi;

/**
 * 联想 WMI 风扇接口的桥接层（命名空间 root\WMI）。
 *
 * <p>探查证据（2026-09-27，Y9000P IAH7H，BIOS J2CN57WW）：
 * <ul>
 *   <li>LENOVO_GAMEZONE_DATA：GetFanCount / GetFan1Speed / GetFan2Speed /
 *       GetFanMaxSpeed / IsSupportFanCooling（本机返回 0，即不支持）/
 *       SetFanCooling（旧接口，本机调用无效）/ GetFanCoolingStatus /
 *       GetCPUTemp / GetGPUTemp / GetSmartFanMode 等；</li>
 *   <li>LENOVO_FAN_METHOD：Fan_Set_FullSpeed / Fan_Get_FullSpeed /
 *       Fan_Get_MaxSpeed / Fan_Get_Table / Fan_Set_Table /
 *       Fan_GetCurrentFanSpeed（FanID 取 0 或 1）/
 *       Fan_GetCurrentSensorTemperature / Fan_SetCurrentFanSpeed
 *       —— 后两者是 v2 自由转速能力的关键；</li>
 *   <li>风扇曲线：风扇 0 为 1700~4800 RPM，风扇 1 为 1600~4800 RPM，各 10 个档位。</li>
 * </ul>
 *
 * <p>实现类必须以管理员权限运行，否则联想 WMI 提供程序会返回「拒绝访问」。
 */
public interface WmiBridge {

    /**
     * 读取风扇数量。
     *
     * @return LENOVO_GAMEZONE_DATA.GetFanCount 返回的风扇数量；读取失败返回 -1
     */
    int getFanCount() throws WmiException;

    /**
     * 切换满速模式（即「降温除尘」功能的固件实现）。
     *
     * <p>主通路为 {@code LENOVO_FAN_METHOD.Fan_Set_FullSpeed}；
     * 对仍支持旧接口的机型回退到 {@code LENOVO_GAMEZONE_DATA.SetFanCooling}。
     *
     * <p>注意：固件会在满速运行约 2 分钟后自动交还 EC 自动控制。
     *
     * @param on true 表示开启满速，false 表示交还 EC 自动控制
     * @return WMI 的 ReturnValue，0 表示成功
     */
    int setFullSpeed(boolean on) throws WmiException;

    /**
     * 关闭满速并强制交还 EC 自动风扇控制。
     *
     * <p>与 {@link #setFullSpeed(boolean) setFullSpeed(false)} 的区别：
     * 本方法在清除满速标志后，会<b>读回校验</b>标志位是否真的归零，
     * 若仍为 1 则抛出异常，避免界面显示「已停止」而硬件实际仍在满速（假成功）。
     *
     * <p>部分 BIOS 在退出满速后不会自动回落风扇曲线，因此界面上的
     * 「停止」按钮应优先调用本方法，而非直接调用 setFullSpeed(false)。
     *
     * @return WMI 的 ReturnValue，0 表示成功
     * @throws WmiException 复位失败，或复位后标志位仍为 1
     */
    int resetToAuto() throws WmiException;

    /**
     * 读取当前是否处于满速模式。
     *
     * @return 1 表示满速中，0 表示常规模式；读取失败返回 -1
     */
    int getFullSpeed() throws WmiException;

    /**
     * 读取双风扇当前转速。
     *
     * @return 长度为 2 的数组，依次为 [风扇1转速, 风扇2转速]，单位 RPM；不可读时为 -1
     */
    int[] readFanSpeeds() throws WmiException;

    /**
     * 读取当前温度。
     *
     * @return 长度为 2 的数组，依次为 [CPU温度, GPU温度]，单位摄氏度；不可读时为 -1
     */
    int[] readTemps() throws WmiException;

    /**
     * 读取智能风扇模式。
     *
     * @return 1=安静、2=均衡、3=性能、255=自定义；读取失败返回 -1
     */
    int getSmartFanMode() throws WmiException;

    /**
     * 一次性读取全部传感器指标（批量快照）。
     *
     * <p><b>为什么需要这个批量接口</b>：桥接层每次调用都会启动一个新的
     * powershell.exe 子进程，而进程创建本身开销很大（实测空启动即达数秒）。
     * 若逐项读取（转速 2 次 + 温度 2 次 + 风扇模式 + 满速状态），
     * 每轮轮询要付 6~7 次进程创建成本，导致界面刷新周期长达数十秒。
     *
     * <p>本方法把一轮所需的全部读取<b>合并为单次子进程调用</b>，
     * 显著缩短刷新周期。取不到的项以 -1 表示（不会因单项失败而丢弃整批数据）。
     *
     * @return 快照对象，字段取不到时为 -1
     */
    Snapshot readSnapshot() throws WmiException;

    /**
     * 一轮轮询所需的全部指标快照。
     *
     * @param fanSpeed1    风扇 1 转速（RPM），不可读为 -1
     * @param fanSpeed2    风扇 2 转速（RPM），不可读为 -1
     * @param cpuTemp      CPU 温度（摄氏度），不可读为 -1
     * @param gpuTemp      GPU 温度（摄氏度），不可读为 -1
     * @param smartFanMode 智能风扇模式，不可读为 -1
     * @param fullSpeed    满速状态：1=满速，0=常规，不可读为 -1
     * @param costMs       本次采集实际耗时（毫秒），用于界面显示诊断
     */
    record Snapshot(int fanSpeed1, int fanSpeed2, int cpuTemp, int gpuTemp,
                    int smartFanMode, int fullSpeed, long costMs) {
    }

    /**
     * 返回实现名称，用于界面上的诊断显示。
     *
     * @return 实现名，例如 powershell-bridge
     */
    String name();
}
