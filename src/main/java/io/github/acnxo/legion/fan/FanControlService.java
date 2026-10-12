package io.github.acnxo.legion.fan;

import io.github.acnxo.legion.fan.wmi.WmiBridge;
import io.github.acnxo.legion.fan.wmi.WmiException;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 风扇控制业务逻辑（**不依赖任何 JavaFX 类型**，可脱离界面单独测试）。
 *
 * <p>职责：轮询传感器、维护「降温除尘」状态机（120 秒倒计时 + 固件自动复位校正）、
 * 复位通路、退出收尾。状态变化一律通过 {@link Listener} 向外发布 ——
 * 本类不认识任何控件，因此界面换实现（FXML / 手写 / 别的 UI 框架）都不影响这里。
 *
 * <p><b>线程</b>：本类自身不切线程；实现方（控制器）负责把回调切回 UI 线程。
 * 轮询使用单线程调度器，切换/复位使用一次性短命线程（与原实现一致）。
 */
public final class FanControlService {

    /** 界面订阅者：由控制器实现。 */
    public interface Listener {

        /** 普通状态文案（默认样式）。 */
        void onStatus(String text);

        /** 传感器读数（原始快照，格式化留给展示层）。 */
        void onSensors(WmiBridge.Snapshot snapshot);

        /** 传感器读取失败。 */
        void onSensorError(String message);

        /** 倒计时秒数变化。 */
        void onCountdown(int remainingSeconds);

        /** 降温除尘开关状态变化（展示层据此决定按钮文案：开启 / 停止）。 */
        void onCoolingState(boolean cooling);

        /** 按钮是否可点（切换过程中禁用，避免抢占）。 */
        void onToggleEnabled(boolean enabled);

        /** 未以管理员身份运行：给出红色警示文案并禁用按钮。 */
        void onNotElevated(String text);
    }

    /** 固件满速波形约 2 分钟后自动复位 */
    private static final int DUST_REMOVAL_SECONDS = 120;

    /**
     * 两轮采集之间的额外间隔（毫秒）。
     *
     * <p>批量采集本身已耗时（含一次 powershell 子进程创建），因此间隔只作喘息之用：
     * 设 250ms 用于让出 CPU 并避免极端情况下的高频自旋。
     */
    private static final long POLL_GAP_MS = 250;

    private final WmiBridge bridge;
    private final Listener listener;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fan-poller");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean cooling;
    /** 正在执行开/关切换，期间冻结硬件状态校正，避免与用户操作抢占 */
    private volatile boolean switching;
    private volatile ScheduledFuture<?> countdown;
    private volatile int remainingSeconds;

    public FanControlService(WmiBridge bridge, Listener listener) {
        this.bridge = bridge;
        this.listener = listener;
    }

    /** 桥接实现名（用于状态文案）。 */
    public String bridgeName() {
        return bridge.name();
    }

    /**
     * 启动：判定管理员权限并决定是否开始轮询。
     *
     * <p>未提权时 WMI 必然拒绝访问，因此**不启动轮询**（继续轮询只会反复刷失败提示）。
     */
    public void start() {
        if (!Elevation.isElevated()) {
            listener.onNotElevated("未以管理员运行：Lenovo WMI 会拒绝访问，转速与温度无法读取。"
                    + "自动提权未成功（通常是 UAC 被取消或拒绝），请右键选择「以管理员身份运行」"
                    + "后重新启动本程序（桥接: " + bridge.name() + "）");
            return;
        }
        listener.onStatus("桥接: " + bridge.name() + " · 已提权 · 固件波形约 2 分钟自动复位");

        // 「背靠背轮询」：单次批量采集本身已有可观耗时，每轮结束即开始下一轮，
        // 实际刷新周期 ≈ 单次采集耗时 + POLL_GAP_MS。
        poller.scheduleWithFixedDelay(this::pollSensors, 0, POLL_GAP_MS, TimeUnit.MILLISECONDS);
    }

    /** 用户点击按钮：开启降温除尘，或手动停止。 */
    public void toggle() {
        if (cooling) {
            stopCooling("已手动停止，交还 EC 自动控制");
            return;
        }
        listener.onToggleEnabled(false);
        switching = true;
        runAsync("fan-start", () -> bridge.setFullSpeed(true),
                rc -> {
                    switching = false;
                    cooling = true;
                    listener.onCoolingState(true);
                    listener.onToggleEnabled(true);
                    startCountdown();
                },
                e -> {
                    switching = false;
                    listener.onStatus("开启失败: " + e.getMessage());
                    listener.onToggleEnabled(true);
                });
    }

    /** 退出前的收尾：停轮询、停倒计时，并尽力不在机器上留下强制满速状态。 */
    public void shutdown() {
        poller.shutdownNow();
        cancelCountdown();
        if (cooling) {
            try {
                bridge.setFullSpeed(false);
            } catch (WmiException ignored) {
                // best effort：退出路径不抛
            }
        }
    }

    private void startCountdown() {
        remainingSeconds = DUST_REMOVAL_SECONDS;
        listener.onCountdown(remainingSeconds);
        cancelCountdown();
        countdown = poller.scheduleAtFixedRate(() -> {
            remainingSeconds--;
            if (remainingSeconds <= 0) {
                stopCooling("已达 2 分钟，自动复位");
            } else {
                listener.onCountdown(remainingSeconds);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void stopCooling(String reason) {
        cancelCountdown();
        listener.onToggleEnabled(false);
        switching = true;
        listener.onStatus(reason + " · 正在复位…");
        runAsync("fan-stop", () -> bridge.resetToAuto(),
                rc -> {
                    switching = false;
                    cooling = false;
                    listener.onCoolingState(false);
                    listener.onToggleEnabled(true);
                    listener.onStatus(reason + " · rc=" + rc);
                },
                // 关键修正（沿用原实现）：无论复位成功或失败，都必须回到可交互状态。
                // 早期版本失败时不翻转 cooling，导致按钮永久卡在「停止」且无法再次启动。
                e -> {
                    switching = false;
                    cooling = false;
                    listener.onCoolingState(false);
                    listener.onToggleEnabled(true);
                    listener.onStatus("复位失败: " + e.getMessage()
                            + " —— 若风扇仍在满速，请点击按钮重试或重启本程序");
                });
    }

    /**
     * 轮询传感器。
     *
     * <p><b>性能约束</b>：桥接层每次读取都会新建 powershell.exe 子进程，而进程创建是主要开销。
     * 因此必须使用<b>单次批量快照</b>（{@link WmiBridge#readSnapshot()}），绝不能逐项调用 ——
     * 逐项会把每轮的进程创建次数从 1 次放大到 6~7 次，刷新周期从秒级恶化到数十秒。
     */
    private void pollSensors() {
        try {
            WmiBridge.Snapshot snap = bridge.readSnapshot();
            listener.onSensors(snap);
            applyHardwareFullSpeed(snap.fullSpeed());
            // 状态栏显示采集耗时，便于直观判断刷新性能；切换/倒计时期间不覆盖状态文案
            if (!switching && !cooling) {
                listener.onStatus("桥接: " + bridge.name() + " · 已提权 · 采集耗时 " + snap.costMs() + " ms");
            }
        } catch (WmiException e) {
            listener.onSensorError(e.getMessage());
        }
    }

    /**
     * 用硬件真实满速状态校正状态机与界面。
     *
     * <p>解决两类状态漂移：① 固件满速约 2 分钟后自动复位，界面倒计时可能仍在跑；
     * ② 程序启动时硬件若已处于满速，界面会错误显示为「未开启」。
     */
    private void applyHardwareFullSpeed(int actual) {
        if (actual < 0 || switching) {
            return; // 读不到或正在切换，不干预
        }
        boolean hardwareOn = actual == 1;
        if (hardwareOn == cooling) {
            return; // 一致，无需校正
        }
        cooling = hardwareOn;
        if (hardwareOn) {
            listener.onCoolingState(true);
            if (countdown == null) {
                startCountdown();
            }
        } else {
            cancelCountdown();
            listener.onCoolingState(false);
            listener.onStatus("固件已自动复位，风扇交还 EC 自动控制");
        }
    }

    private void cancelCountdown() {
        ScheduledFuture<?> f = countdown;
        if (f != null) {
            f.cancel(false);
            countdown = null;
        }
    }

    /** 在短命线程上跑一次桥接调用，把结果或异常分别交给两个回调。 */
    private void runAsync(String threadName, BridgeCall call, IntConsumer onSuccess, Consumer<String> onFailure) {
        Thread t = new Thread(() -> {
            try {
                int rc = call.get();
                onSuccess.accept(rc);
            } catch (Exception e) {
                onFailure.accept(e);
            }
        }, threadName);
        t.setDaemon(true);
        t.start();
    }

    @FunctionalInterface
    private interface BridgeCall {
        int get() throws WmiException;
    }
}
