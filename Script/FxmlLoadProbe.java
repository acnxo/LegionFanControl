import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

/**
 * 仅供 CI 采集使用：只做「启动 JavaFX 工具包 → 装载 FanControlView.fxml → 退出」。
 *
 * <p>用途：JavaFX 在反射调用某些成员时，会在**运行期动态生成** {@code com.sun.javafx.reflect.Trampoline}
 * 来绕过模块访问限制；native-image 默认禁止运行期定义类，于是原生包启动即崩：
 * <pre>
 * UnsupportedFeatureError: Classes cannot be defined at runtime by default …
 * Tried to define class 'com.sun.javafx.reflect.Trampoline'
 * </pre>
 *
 * <p>定点解法是「把该类预定义进镜像」，而预定义清单必须由 tracing agent 采集。
 * 采集要求进程**优雅退出**（agent 在关闭钩子里落盘），所以这里不显示窗口、装载完立即退出；
 * 另外 JVM 上 {@code defineClass} 会被模块访问挡住，采集时必须加
 * {@code --add-opens java.base/java.lang=ALL-UNNAMED}，否则该路径不会真的定义类、也就采不到。
 *
 * <p>本类不参与产品发布，只被 {@code .github/workflows/CollectPredefinedClasses.yml} 调用。
 */
public final class FxmlLoadProbe {

    private FxmlLoadProbe() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch toolkitReady = new CountDownLatch(1);
        Platform.startup(toolkitReady::countDown);
        if (!toolkitReady.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("JavaFX 工具包 30 秒内未就绪");
        }

        String resource = "/io/github/acnxo/legion/fan/FanControlView.fxml";
        FXMLLoader loader = new FXMLLoader(FxmlLoadProbe.class.getResource(resource));
        if (loader.getLocation() == null) {
            throw new IllegalStateException("找不到资源：" + resource);
        }
        Parent root = loader.load();
        System.out.println("[probe] FXML 装载完成：" + resource + " → 根节点 " + root.getClass().getName());
        System.out.flush();

        Platform.exit();
        System.exit(0); // 触发关闭钩子 ⇒ tracing agent 落盘配置文件
    }
}
