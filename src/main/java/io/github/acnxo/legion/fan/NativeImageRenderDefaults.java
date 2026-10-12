package io.github.acnxo.legion.fan;

import java.util.Locale;

/**
 * 原生镜像下的渲染默认值 —— <b>临时缓解措施（workaround），不是最终方案</b>（issue #48）。
 *
 * <p><b>它在绕开什么</b>：GraalVM {@code native-image} 产物在 JavaFX 的 **LCD 次像素文本合成**
 * 路径上退化为二值输出 —— 同一参数下 DWrite 掩码逐字节相同，但合成把三个颜色通道压成同一个
 * 0/255 标量（原生 {@code 126480/126480/126480}，JVM {@code 90666/91021/90713}），界面上表现为
 * 「文字更粗、更糊、没有次像素色边」。根因与完整证据链见 issue #20。
 *
 * <p><b>重要：代码级根因尚未定位</b>。本类**没有把问题修好**，只是在原生镜像内改走灰度抗锯齿
 * 以绕开该退化路径（灰度路径在两个构建下逐项一致）。因此它<b>必须被视为临时措施</b>：
 * 凡是上游（GraalVM 或 OpenJFX）修复了该退化，就应当**删除本类与它的两处调用**
 * （{@link Launcher#main}、{@link FanControlApp#main}），回到 JavaFX 默认行为。
 *
 * <h2>开关与优先级（命令行即可指定，不必改代码）</h2>
 *
 * <pre>
 * 1. -Dprism.lcdtext=true|false              用户直接决定，程序一律不覆盖（最高优先）
 * 2. -Dlegion.render.nativeTextGreyscale=false  关闭本缓解措施，回到 JavaFX 默认（Windows 即 LCD）
 * 3. 未指定以上任一属性且在原生镜像内         程序写入 prism.lcdtext=false（灰度）← 本类默认行为
 * 4. JVM 版（LegionFanControl.jar）           程序不做任何处理，保持 LCD 次像素合成
 * </pre>
 *
 * <h2>回退操作（两种粒度）</h2>
 *
 * <pre>
 * 只这一次运行不缓解（推荐，不改代码、不改发布物）：
 *   LegionFanControl.exe -Dprism.lcdtext=true                 直接恢复 LCD 次像素合成
 *   LegionFanControl.exe -Dlegion.render.nativeTextGreyscale=false  关掉本默认值本身（等同未打本改动）
 *
 * 永久回退到改动前：
 *   ① 删掉 Launcher.main 与 FanControlApp.main 里的 NativeImageRenderDefaults.applyIfNativeImage(); 两行；
 *   ② 或对合入本改动的提交执行 git revert（本类与两处调用会一并撤销）。
 * </pre>
 *
 * <p><b>时机是硬约束</b>：{@code lcdEnabled} 在 {@code PrismFontFactory} 的静态初始化里读取该属性
 * **一次**，晚于工具包启动再设置就没有效果。因此本方法必须最早执行 —— 当前在
 * {@link Launcher#main} 与 {@link FanControlApp#main} 的第一条语句处调用。
 * 原生镜像构建里 {@code com.sun.javafx.font} 与 {@code com.sun.prism} 都是
 * {@code --initialize-at-run-time}（见 pom.xml），故运行期写入的值会被真正读取；
 * 若这两包日后改为构建期初始化，本缓解措施会**静默失效**（这正是它属于临时措施的另一面）。
 */
public final class NativeImageRenderDefaults {

    /** GraalVM 在原生镜像内注入的标记属性；非 null 即表示当前运行于原生镜像。 */
    private static final String NATIVE_IMAGE_MARKER = "org.graalvm.nativeimage.imagecode";

    /** JavaFX 属性：是否启用 LCD 次像素文本合成。 */
    private static final String LCD_TEXT = "prism.lcdtext";

    /**
     * 本缓解措施的**总开关**：显式设为 false / off / no / 0 即关闭本默认值，
     * 回到 JavaFX 默认（Windows 上即 LCD 次像素合成）。未设置时视为开启。
     */
    static final String GREYSCALE_DEFAULT_SWITCH = "legion.render.nativeTextGreyscale";

    /** 最近一次判定的结果，供渲染诊断如实呈现「这个值到底是谁设的」。 */
    private static volatile String decision = "未判定";

    private NativeImageRenderDefaults() {
    }

    /**
     * 原生镜像内、用户未显式指定 {@code prism.lcdtext}、且总开关未关闭时，关闭 LCD 次像素合成。
     *
     * <p>幂等：重复调用只在第一次生效。任何异常都不向外抛 —— 渲染默认值绝不能阻断启动。
     *
     * @return 本次调用是否真的写入了该属性
     */
    public static boolean applyIfNativeImage() {
        try {
            if (System.getProperty(NATIVE_IMAGE_MARKER) == null) {
                decision = "跳过：非原生镜像（JVM 版不受影响）";
                return false;
            }
            String lcd = System.getProperty(LCD_TEXT);
            if (lcd != null) {
                decision = "跳过：用户已显式设置 prism.lcdtext=" + lcd;
                return false;
            }
            if (isSwitchOff()) {
                decision = "跳过：总开关已关闭（" + GREYSCALE_DEFAULT_SWITCH + "="
                        + System.getProperty(GREYSCALE_DEFAULT_SWITCH) + "）";
                return false;
            }
            System.setProperty(LCD_TEXT, "false");
            decision = "已按原生镜像默认写入 prism.lcdtext=false（灰度抗锯齿）";
            return true;
        } catch (Throwable t) {
            decision = "不可用(" + t.getClass().getSimpleName() + ")";
            return false;
        }
    }

    /**
     * 最近一次判定结果，用于渲染诊断。
     *
     * @return 形如「已按原生镜像默认写入 prism.lcdtext=false（灰度抗锯齿）」或「跳过：…」的说明
     */
    static String decision() {
        return decision;
    }

    /** @return 总开关是否被显式关闭（false / off / no / 0，忽略大小写与空白） */
    private static boolean isSwitchOff() {
        String value = System.getProperty(GREYSCALE_DEFAULT_SWITCH);
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("false") || normalized.equals("off")
                || normalized.equals("no") || normalized.equals("0");
    }
}
