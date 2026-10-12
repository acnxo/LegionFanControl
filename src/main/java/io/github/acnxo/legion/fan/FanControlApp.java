package io.github.acnxo.legion.fan;

import io.github.acnxo.legion.fan.wmi.PowerShellWmiBridge;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;

/**
 * 组装层：加载 FXML 视图、装配业务层与控制器、设置窗口属性与生命周期。
 *
 * <p><b>分层</b>：界面结构在 {@code FanControlView.fxml}，样式在 {@code FanControlView.css}，
 * 事件与显示格式化在 {@link FanControlController}，业务规则在 {@link FanControlService}。
 * 本类不写布局、不写业务，只做装配。
 *
 * <p>v1 功能范围（平替联想电脑管家的「降温除尘」）：
 * <ul>
 *   <li>一键满速（主通路 Fan_Set_FullSpeed，回退 SetFanCooling）；</li>
 *   <li>120 秒倒计时，到期自动交还 EC 自动控制；</li>
 *   <li>实时显示双风扇转速（标注左 / 右及 CPU / GPU 归属）、CPU / GPU 温度、智能风扇模式；</li>
 *   <li>启动时检测管理员权限并给出可见警告。</li>
 * </ul>
 *
 * <p>风扇方位标注的依据与置信度（含「并非联想官方逐一标注、未经拆机核实」的口径）见
 * {@code Docs/TechnicalPrinciples.md} 与 {@code Docs/UserInterface.md}。
 *
 * <p>必须以管理员身份运行。开发期请在管理员终端执行：mvn javafx:run
 */
public class FanControlApp extends Application {

    /** 视图资源与控制器、样式同目录（见 src/main/resources/io/github/acnxo/legion/fan/） */
    private static final String VIEW_FXML = "FanControlView.fxml";

    private FanControlService service;

    public static void main(String[] args) {
        // 控制台：原生包以 GUI 子系统链接，默认根本没有控制台窗口（不是“隐藏”）；需要日志时用 --console。
        // 放在最前面：--console 分配控制台要早于任何输出与 JavaFX 初始化，否则早期日志会丢。
        ConsoleWindow.applyStartupVisibility(args);

        // 临时缓解措施（issue #48，非最终方案）：原生镜像内默认关闭 LCD 次像素合成；lcdEnabled
        // 只在 PrismFontFactory 静态初始化时读取一次，必须早于工具包启动。开关与回退见该类注释。
        NativeImageRenderDefaults.applyIfNativeImage();
        launch(args);
    }

    /**
     * 诊断（只打日志）：JavaFX 的反射工具 {@code com.sun.javafx.property.MethodHelper} →
     * {@code com.sun.javafx.reflect.MethodUtil}（一个私有 {@code SecureClassLoader}）会读取
     * {@code com/sun/javafx/reflect/Trampoline.class} 这份**资源**，再在**运行期** {@code defineClass}；
     * 而 native-image 默认禁止运行期定义类，于是原生包启动即崩。
     *
     * <p>因此这份资源必须被排除（见 {@code resource-config.json} 的 {@code excludes}）：资源不可见时，
     * {@code MethodUtil.findClass} 会抛 {@code ClassNotFoundException}，它自己的 {@code loadClass}
     * 会吞掉该异常并回落到父加载器，从而直接从镜像里取到该类（反射表已登记）—— 完全不触发运行期定义。
     *
     * <p>这一行是**纯 ASCII 标记**，让 CI 与真机都能一眼判定排除是否生效（{@code null} 才是对的），
     * 详见 {@code Docs/NativeImageCi.md} 第 5 节第 21 条。
     */
    private static void logTrampolineDiagnostics() {
        String visibility;
        try (InputStream in = FanControlApp.class.getModule()
                .getResourceAsStream("com/sun/javafx/reflect/Trampoline.class")) {
            visibility = (in == null) ? "null" : "visible";
        } catch (IOException | RuntimeException e) {
            visibility = "error(" + e.getClass().getSimpleName() + ")";
        }
        System.out.println("[diag-trampoline-resource] " + visibility
                + "（期望 null：资源被排除后 JavaFX 会回落到父加载器，不再运行期定义类）");
        System.out.flush();
    }

    @Override
    public void start(Stage stage) {
        // 渲染诊断：只打日志，不改渲染行为。用于对照原生镜像与 JVM 模式的字体渲染差异（issue #20）
        RenderDiagnostics.log();
        logTrampolineDiagnostics();

        FanControlController controller;
        Parent root;
        try {
            FXMLLoader loader = new FXMLLoader(FanControlApp.class.getResource(VIEW_FXML));
            root = loader.load();
            controller = loader.getController();
        } catch (IOException | RuntimeException | Error e) {
            // 视图装不上就没有界面可给：把原因写清楚再退出。
            // 捕获范围包含 Error：原生镜像里 FXMLLoader 被裁掉时抛的是
            // AssertionError（内部 forceInit 按名字加载失败），只接 Exception 会漏过去、
            // 连带把下面的 ASCII 标记也漏掉（2026-10-12 冒烟正面断言就是这么抓到的）。
            // [ui-fxml-load-failed] 是给 CI 冒烟用的**纯 ASCII 标记**（冒烟以 GBK 跑，中文在日志里可能是乱码，
            // 而 ASCII 不受影响）；对应的成功标记 [ui-fxml-loaded] 在 FanControlController.initialize()。
            // 原生镜像下这类失败通常是资源（fxml/css）或反射元数据（控制器/FXMLLoader）没登记，
            // 见 Docs/NativeImageCi.md 第 5 节第 20 条。
            System.err.println("[ui-fxml-load-failed] 界面加载失败（" + VIEW_FXML + "）：" + e);
            e.printStackTrace();
            System.err.flush();
            Platform.exit();
            return;
        }

        service = new FanControlService(new PowerShellWmiBridge(), controller);
        controller.init(service);

        // 窗口初始 600×360：容纳「01-左风扇转速[CPU侧]」这类较长标注而不被截断，
        // 同时给状态栏留出足够的横向余量；状态栏的宽度上限随窗口自适应（见控制器 initialize()）。
        stage.setScene(new Scene(root, 600, 360));
        stage.setTitle("Legion风扇控制 V0.5.0");
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
        stage.setOnCloseRequest(e -> service.shutdown());
        stage.show();

        // 权限判定与轮询由业务层负责：未提权时它给出红色警示并禁用按钮，且不启动轮询。
        service.start();
    }

    @Override
    public void stop() {
        if (service != null) {
            service.shutdown();
        }
    }
}
