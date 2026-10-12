package io.github.acnxo.legion.fan;

import io.github.acnxo.legion.fan.wmi.PowerShellWmiBridge;
import io.github.acnxo.legion.fan.wmi.WmiBridge;
import io.github.acnxo.legion.fan.wmi.WmiException;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.InputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 联想拯救者风扇工具 —— Java 25 + OpenJFX 基线版（JVM 运行）。
 *
 * <p>v1 功能范围（平替联想电脑管家的「降温除尘」）：
 * <ul>
 *   <li>一键满速（主通路 Fan_Set_FullSpeed，回退 SetFanCooling）；</li>
 *   <li>120 秒倒计时，到期自动交还 EC 自动控制；</li>
 *   <li>实时显示双风扇转速（标注左 / 右及 CPU / GPU 归属）、CPU / GPU 温度、智能风扇模式；</li>
 *   <li>启动时检测管理员权限并给出可见警告。</li>
 * </ul>
 *
 * <p>必须以管理员身份运行。开发期请在管理员终端执行：mvn javafx:run
 */
public class FanControlApp extends Application {

    /** 固件满速波形约 2 分钟后自动复位 */
    private static final int DUST_REMOVAL_SECONDS = 120;

    /**
     * 两轮采集之间的额外间隔（毫秒）。
     *
     * <p>批量采集本身已耗时（含一次 powershell 子进程创建），因此间隔只作
     * 喘息之用，不宜设大，否则会无谓拉长刷新周期。设 250ms 用于让出 CPU
     * 并避免极端情况下的高频自旋。
     */
    private static final long POLL_GAP_MS = 250;

    /**
     * 风扇 1 / 风扇 2 的物理方位与归属标注。
     *
     * <p><b>证据来源与置信度（务必如实呈现，不做确定性断言）：</b>
     * <ol>
     *   <li>本机实测风扇表（{@code Script/Evidence/probe-result-elevated.txt}）：
     *       Fan_Id=0 温点 58→92℃、最低 1700 RPM；Fan_Id=1 温点 65→88℃、最低 1600 RPM。
     *       Fan 0 起始温度更低、上限更高，符合 CPU 温区特性 —— <b>推断 Fan_Id=0 对应 CPU 侧</b>。</li>
     *   <li>本机型（Legion 5 Pro / Y9000P 16IAH7H，Type 82RF）风扇配件商定位说明：
     *       「正常使用笔记本时，<b>左侧为 CPU 散热风扇，右侧为 GPU 散热风扇</b>」
     *       （对应备件号 CPU 风机 DFSCL12E06486Q / GPU 风机 DFSCL12E16486Q）。</li>
     * </ol>
     *
     * <p><b>注意</b>：上述「左=CPU、右=GPU」来自配件市场的通行布局约定与本机热特性推断，
     * <b>并非联想官方逐一标注，也未经拆机实机核对</b>。因此界面以「推测」措辞呈现，
     * 避免把推断当成定论。若后续用户拆机核实与标注不符，仅需改此常量。
     */
    private static final String FAN1_LABEL = "01-左风扇转速[CPU侧]";
    private static final String FAN2_LABEL = "02-右风扇转速[GPU侧]";

    private final WmiBridge bridge = new PowerShellWmiBridge();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fan-poller");
        t.setDaemon(true);
        return t;
    });

    private Label fan1Value;
    private Label fan2Value;
    private Label cpuTempValue;
    private Label gpuTempValue;
    private Label modeValue;
    private Label statusValue;
    private Button toggleButton;

    private Timeline countdown;
    private int remainingSeconds;
    private boolean cooling;
    /** 正在执行开/关切换，期间冻结硬件状态校正，避免与用户操作抢占 */
    private volatile boolean switching;
    /** 是否以管理员身份运行；未提权时 WMI 必然拒绝，不轮询 */
    private boolean elevated;

    public static void main(String[] args) {
        // 控制台：原生包以 GUI 子系统链接，默认根本没有控制台窗口（不是“隐藏”）；需要日志时用 --console。
        // 放在最前面：--console 分配控制台要早于任何输出与 JavaFX 初始化，否则早期日志会丢。
        ConsoleWindow.applyStartupVisibility(args);

        // 临时缓解措施（issue #48，非最终方案）：原生镜像内默认关闭 LCD 次像素合成；lcdEnabled
        // 只在 PrismFontFactory 静态初始化时读取一次，必须早于工具包启动。开关与回退见该类注释。
        NativeImageRenderDefaults.applyIfNativeImage();
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        // 渲染诊断：只打日志，不改渲染行为。用于对照原生镜像与 JVM 模式的字体渲染差异（issue #20）
        RenderDiagnostics.log();

        GridPane grid = new GridPane();
        grid.setHgap(20);
        grid.setVgap(15);
        grid.setPadding(new Insets(20));

        fan1Value = addRow(grid, 0, FAN1_LABEL);
        fan2Value = addRow(grid, 1, FAN2_LABEL);
        cpuTempValue = addRow(grid, 2, "CPU 温度");
        gpuTempValue = addRow(grid, 3, "GPU 温度");
        modeValue = addRow(grid, 4, "智能风扇模式");

        toggleButton = new Button("开始降温除尘");
        toggleButton.setMaxWidth(Double.MAX_VALUE);
        toggleButton.setOnAction(e -> onToggle());
        GridPane.setColumnSpan(toggleButton, 2);
        grid.add(toggleButton, 0, 5);

        statusValue = new Label();
        statusValue.setWrapText(true);
        GridPane.setColumnSpan(statusValue, 2);
        grid.add(statusValue, 0, 6);

        HBox root = new HBox(grid);
        root.setAlignment(Pos.CENTER);
        // 宽度 470：容纳「风扇 1 转速（左 / CPU 侧，推测）」这类较长标注而不被截断
        stage.setScene(new Scene(root, 600, 360));
        stage.setTitle("Legion风扇控制 V0.3.1");
        // 窗口与任务栏图标：与 exe 的文件图标同一张图（见 Docs/Asset/README.md）。
        // 图标是外观项：缺失或加载失败都不应影响启动，因此只记一行、不向上抛。
        try (InputStream logo = FanControlApp.class.getResourceAsStream("/Asset/Logo.png")) {
            if (logo == null) {
                System.out.println("窗口图标：未找到 Logo.png，已跳过（不影响功能）");
            } else {
                stage.getIcons().add(new Image(logo));
            }
        } catch (Exception e) {
            System.out.println("窗口图标：加载失败，已跳过（" + e.getClass().getSimpleName() + "）");
        }
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();
        // 注：应用内「屏幕字形像素」（Robot 抓屏）已停用 —— 两次真机验证都证明它抓到的**不是本窗口**
        // （直方图 99.7% 像素挤在 208-223 一个桶、224-255 为空，而本窗口背景是白的），
        // 保留代码仅供必要时排查；验收一律以「真实截图 + Script/CompareScreenshots.py」为准。
        // RenderDiagnostics.logScreenGlyphPixels(stage.getScene());

        boolean elevated = Elevation.isElevated();
        this.elevated = elevated;
        if (!elevated) {
            statusValue.setStyle("-fx-text-fill: #b00020;");
            statusValue.setText("未以管理员运行：Lenovo WMI 会拒绝访问，转速与温度无法读取。"
                    + "自动提权未成功（通常是 UAC 被取消或拒绝），请右键选择「以管理员身份运行」"
                    + "后重新启动本程序（桥接: " + bridge.name() + "）");
            // 未提权时不启动轮询：WMI 必然拒绝访问，继续轮询只会每 2 秒刷一次失败提示
            toggleButton.setDisable(true);
            toggleButton.setText("需管理员权限");
            return;
        }
        statusValue.setText("桥接: " + bridge.name() + " · 已提权 · 固件波形约 2 分钟自动复位");

        // 采用「背靠背轮询」而非固定间隔：单次批量采集本身已有可观耗时，
        // 若再叠加固定间隔会白白拉长刷新周期。此处每轮结束即开始下一轮，
        // 实际刷新周期 ≈ 单次采集耗时，做到尽可能快。
        poller.scheduleWithFixedDelay(this::pollSensors, 0, POLL_GAP_MS, TimeUnit.MILLISECONDS);
    }

    private Label addRow(GridPane grid, int row, String title) {
        Label k = new Label(title);
        Label v = new Label("--");
        grid.add(k, 0, row);
        grid.add(v, 1, row);
        return v;
    }

    private void onToggle() {
        if (cooling) {
            stopCooling("已手动停止，交还 EC 自动控制");
            return;
        }
        toggleButton.setDisable(true);
        switching = true;
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return bridge.setFullSpeed(true);
            }
        };
        task.setOnSucceeded(e -> {
            switching = false;
            cooling = true;
            toggleButton.setText("停止");
            toggleButton.setDisable(false);
            startCountdown();
        });
        task.setOnFailed(e -> {
            switching = false;
            statusValue.setText("开启失败: " + task.getException().getMessage());
            toggleButton.setDisable(false);
        });
        new Thread(task, "fan-start").start();
    }

    private void startCountdown() {
        remainingSeconds = DUST_REMOVAL_SECONDS;
        statusValue.setText("除尘波形运行中 · 剩余 " + remainingSeconds + " s");
        countdown = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            remainingSeconds--;
            if (remainingSeconds <= 0) {
                stopCooling("已达 2 分钟，自动复位");
            } else {
                statusValue.setText("除尘波形运行中 · 剩余 " + remainingSeconds + " s");
            }
        }));
        countdown.setCycleCount(Timeline.INDEFINITE);
        countdown.play();
    }

    private void stopCooling(String reason) {
        if (countdown != null) {
            countdown.stop();
            countdown = null;
        }
        toggleButton.setDisable(true);
        switching = true;
        statusValue.setText(reason + " · 正在复位…");
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                // 优先走带读回校验的复位通路，避免「界面说停了、硬件还在满速」
                return bridge.resetToAuto();
            }
        };
        task.setOnSucceeded(e -> {
            switching = false;
            cooling = false;
            toggleButton.setText("开始降温除尘");
            toggleButton.setDisable(false);
            statusValue.setText(reason + " · rc=" + task.getValue());
        });
        // 关键修正：无论复位成功或失败，都必须回到可交互状态。
        // 早期版本失败时不翻转 cooling，导致按钮永久卡在「停止」且无法再次启动。
        task.setOnFailed(e -> {
            switching = false;
            cooling = false;
            toggleButton.setText("开始降温除尘");
            toggleButton.setDisable(false);
            statusValue.setText("复位失败: " + task.getException().getMessage()
                    + " —— 若风扇仍在满速，请点击按钮重试或重启本程序");
        });
        new Thread(task, "fan-stop").start();
    }

    /**
     * 轮询传感器。
     *
     * <p><b>性能约束</b>：桥接层每次读取都会新建 powershell.exe 子进程，
     * 而进程创建是主要开销。因此这里必须使用<b>单次批量快照</b>
     * （{@link WmiBridge#readSnapshot()}），绝不能逐项调用 —— 逐项会把每轮
     * 轮询的进程创建次数从 1 次放大到 6~7 次，刷新周期从秒级恶化到数十秒。
     */
    private void pollSensors() {
        try {
            WmiBridge.Snapshot snap = bridge.readSnapshot();
            Platform.runLater(() -> {
                // 区分「读取失败」(-1) 与「真实读数为 0」：0 是合法值，
                // 只有 <0 才代表读取异常，避免把正常低速误报为失败。
                fan1Value.setText(snap.fanSpeed1() < 0 ? "读取失败" : snap.fanSpeed1() + " RPM");
                fan2Value.setText(snap.fanSpeed2() < 0 ? "读取失败" : snap.fanSpeed2() + " RPM");
                cpuTempValue.setText(snap.cpuTemp() > 0 ? snap.cpuTemp() + " °C" : "--");
                gpuTempValue.setText(snap.gpuTemp() > 0 ? snap.gpuTemp() + " °C" : "--");
                modeValue.setText(switch (snap.smartFanMode()) {
                    case 1 -> "[001]安静";
                    case 2 -> "[002]均衡";
                    case 3 -> "[003]性能";
                    case 255 -> "[255]自定义";
                    case -1 -> "未知";
                    default -> "未知 (" + snap.smartFanMode() + ")";
                });
                applyHardwareFullSpeed(snap.fullSpeed());
                // 状态栏显示采集耗时，便于直观判断刷新性能
                if (!switching && !cooling) {
                    statusValue.setText("桥接: " + bridge.name() + " · 已提权 · 采集耗时 "
                            + snap.costMs() + " ms");
                }
            });
        } catch (WmiException e) {
            String reason = e.getMessage();
            Platform.runLater(() -> {
                fan1Value.setText("读取失败");
                fan2Value.setText("读取失败");
                statusValue.setText("传感器读取异常: " + reason);
            });
        }
    }

    /**
     * 用硬件真实满速状态校正界面状态（在 FX 线程内调用）。
     *
     * <p>解决两类状态漂移：① 固件满速约 2 分钟后自动复位，界面倒计时可能仍在跑；
     * ② 程序启动时硬件若已处于满速，界面会错误显示为「未开启」。
     */
    private void applyHardwareFullSpeed(int actual) {
        if (actual < 0 || switching) {
            return; // 读不到或正在切换，不干预界面
        }
        boolean hardwareOn = actual == 1;
        if (hardwareOn == cooling) {
            return; // 一致，无需校正
        }
        cooling = hardwareOn;
        if (hardwareOn) {
            toggleButton.setText("停止");
            if (countdown == null) {
                startCountdown();
            }
        } else {
            if (countdown != null) {
                countdown.stop();
                countdown = null;
            }
            toggleButton.setText("开始降温除尘");
            statusValue.setText("固件已自动复位，风扇交还 EC 自动控制");
        }
    }

    private void shutdown() {
        poller.shutdownNow();
        if (countdown != null) {
            countdown.stop();
        }
        if (cooling) {
            // best effort: never leave the machine in forced full-speed state
            try {
                bridge.setFullSpeed(false);
            } catch (WmiException ignored) {
            }
        }
    }

    @Override
    public void stop() {
        shutdown();
    }
}
