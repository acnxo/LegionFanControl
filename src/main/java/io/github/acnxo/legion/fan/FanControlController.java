package io.github.acnxo.legion.fan;

import io.github.acnxo.legion.fan.wmi.WmiBridge;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;

/**
 * 视图控制器（界面与业务之间的唯一桥梁）。
 *
 * <p>职责边界：
 * <ul>
 *   <li><b>事件</b>：FXML 里的 {@code onAction="#onToggle"} 绑到本类方法，方法体只做一次委托；</li>
 *   <li><b>数据</b>：把业务层发来的原始数据（如 {@link WmiBridge.Snapshot}）格式化成控件文本；</li>
 *   <li><b>状态样式</b>：样式类（{@code status-error}）与禁用态由本类切换，配色写在 CSS 里。</li>
 * </ul>
 *
 * <p>本类**不含任何业务规则**（何时复位、多久倒计时、失败后怎么恢复，全在 {@link FanControlService}）。
 * 业务回调可能来自轮询线程或短命工作线程，因此所有控件改动都先切回 UI 线程。
 */
public final class FanControlController implements FanControlService.Listener {

    /** 状态栏最小宽度 = 初始布局宽度 × 1.2（要求：比原始布局宽 20%） */
    private static final double STATUS_MIN_WIDTH_FACTOR = 1.2;

    /** FanControlView.css 中 .fan-grid 的 -fx-padding: 20 ⇒ 左右各 20px */
    private static final double GRID_HORIZONTAL_PADDING = 40;

    @FXML
    private GridPane grid;
    @FXML
    private Label fan1Value;
    @FXML
    private Label fan2Value;
    @FXML
    private Label cpuTempValue;
    @FXML
    private Label gpuTempValue;
    @FXML
    private Label modeValue;
    @FXML
    private Label statusValue;
    @FXML
    private Button toggleButton;

    private FanControlService service;
    private boolean cooling;

    /** 由 {@link FanControlApp} 在 FXML 装载完成后注入业务层（{@code initialize()} 阶段它还不存在）。 */
    void init(FanControlService service) {
        this.service = service;
    }

    /** FXMLLoader 在装载后自动调用：只做「视图自身的度量与初始化」。 */
    @FXML
    private void initialize() {
        // 状态栏宽度：上限随窗口自适应（FXML 里 maxWidth="Infinity" + 第 2 列 hgrow=ALWAYS），
        // 这里只补最小宽度 —— 取当前布局实际分配给状态栏的宽度 × 1.2。
        double statusWidthBefore = Math.max(grid.prefWidth(-1) - GRID_HORIZONTAL_PADDING, 0);
        statusValue.setMinWidth(Math.round(statusWidthBefore * STATUS_MIN_WIDTH_FACTOR));
        System.out.println("窗口布局：状态栏最小宽度 = " + Math.round(statusWidthBefore * STATUS_MIN_WIDTH_FACTOR)
                + " px（原 " + Math.round(statusWidthBefore) + " px 的 1.2 倍），宽度上限随窗口自适应");
    }

    /** FXML 的 onAction 绑定点：只委托给业务层。 */
    @FXML
    private void onToggle() {
        service.toggle();
    }

    // ------------------------------------------------------------------
    // FanControlService.Listener：业务 → 界面。控件改动一律切回 UI 线程。
    // ------------------------------------------------------------------

    @Override
    public void onStatus(String text) {
        onFx(() -> {
            clearErrorStyle();
            statusValue.setText(text);
        });
    }

    @Override
    public void onSensors(WmiBridge.Snapshot snap) {
        onFx(() -> applySnapshot(snap));
    }

    @Override
    public void onSensorError(String message) {
        onFx(() -> {
            fan1Value.setText("读取失败");
            fan2Value.setText("读取失败");
            statusValue.setText("传感器读取异常: " + message);
        });
    }

    @Override
    public void onCountdown(int remainingSeconds) {
        onFx(() -> statusValue.setText("除尘波形运行中 · 剩余 " + remainingSeconds + " s"));
    }

    @Override
    public void onCoolingState(boolean cooling) {
        onFx(() -> {
            this.cooling = cooling;
            toggleButton.setText(cooling ? "停止" : "开始降温除尘");
        });
    }

    @Override
    public void onToggleEnabled(boolean enabled) {
        onFx(() -> toggleButton.setDisable(!enabled));
    }

    @Override
    public void onNotElevated(String text) {
        onFx(() -> {
            statusValue.getStyleClass().add("status-error");
            statusValue.setText(text);
            toggleButton.setText("需管理员权限");
            toggleButton.setDisable(true);
        });
    }

    // ------------------------------------------------------------------
    // 展示层内部的格式化与线程切换
    // ------------------------------------------------------------------

    /** 把原始快照格式化成各控件文本（区分「读取失败」与合法的 0 值）。 */
    private void applySnapshot(WmiBridge.Snapshot snap) {
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
    }

    private void clearErrorStyle() {
        statusValue.getStyleClass().remove("status-error");
    }

    /** 当前是否处于降温除尘状态（供测试或后续扩展使用）。 */
    boolean isCooling() {
        return cooling;
    }

    /** 无论回调来自哪个线程，控件改动都排到 UI 线程执行。 */
    private static void onFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
