package io.github.acnxo.legion.fan;

import javafx.animation.PauseTransition;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.scene.text.Font;
import javafx.scene.text.FontSmoothingType;
import javafx.scene.text.Text;
import javafx.scene.transform.Scale;
import javafx.stage.Screen;
import javafx.util.Duration;

import java.lang.reflect.Method;
import java.util.TreeSet;

/**
 * 原生镜像与 JVM 模式的渲染诊断（**只打日志，不改任何渲染行为**）。
 *
 * <p>背景：原生包文字比 JVM 模式发虚、笔画偏细（跟踪 issue #20）。排查时先把字体侧逐条排除
 * （字体工厂 / 次像素开关 / 字体枚举两模式逐字相同）之后，就必须换成**数字**继续定位 ——
 * 本类把这些数字一次打全，供两种模式逐行对照：
 *
 * <pre>
 * 渲染诊断：原生镜像标记 org.graalvm.nativeimage.imagecode=&lt;默认&gt;
 * 渲染诊断：原生文本灰度默认 已按原生镜像默认写入 prism.lcdtext=false（灰度抗锯齿） 开关 legion.render.nativeTextGreyscale=&lt;默认&gt;
 * 渲染诊断：属性 prism.order=&lt;默认&gt; prism.lcdtext=&lt;默认&gt; prism.subpixeltext=&lt;默认&gt; glass.win.uiScale=&lt;默认&gt;
 * 渲染诊断：运行时 JVM=Substrate VM JavaFX=27 Java=25.0.4
 * 渲染诊断：屏幕 dpi=144.0 outputScale=1.5x1.5 视觉边界=... 显示器数=1
 * 渲染诊断：默认字体 族=System 名称=System Regular 字号=13.0 可用字体族数=218
 * 渲染诊断：字体工厂=com.sun.javafx.font.directwrite.DWFactory LCD对比度=1.2 LCD文本支持=true subPixelMode=1
 * 渲染诊断：文字平滑类型（应用请求）=GRAY
 * 渲染诊断：字形度量 14px 文本宽=... 高=... 基线=...
 * 渲染诊断：字形像素 344x18 墨量合计=... 非空像素=... 次像素着色像素=...
 * 渲染诊断：屏幕字形像素 整屏2560x1600(scale=1.50) 窗口像素区=...,... 900x540 均灰度=... 深核像素=... 中间调像素=... AA比=... 色边均值=... 判定=...
 * 渲染诊断：屏幕字形像素 灰度直方图（16 档） 0-15=... 16-31=... ... 240-255=...
 * 渲染诊断：屏幕字形像素 ASCII（锚点=...,... 区域=...,... 72x14，1 字符=1 像素，越暗字符越重）
 * 渲染诊断：屏幕字形像素 ASCII |@@%###+=:.                     |
 * </pre>
 *
 * <p><b>最关键的三个数字</b>：
 * <ul>
 *   <li><b>LCD 对比度</b>：JavaFX 把它当作 LCD gamma 直接作用在文字颜色上
 *       （{@code BaseShaderGraphics} 里 {@code Math.pow(textColor, invgamma)}），值不同字形会偏细 / 发白；
 *       来自原生调用 {@code PrismFontFactory.getLCDContrastWin32()}（读系统 ClearType 设置）。</li>
 *   <li><b>LCD文本支持 / subPixelMode</b>：{@code PrismFontStrike} 用前者决定用 LCD 还是**灰度抗锯齿**
 *       （{@code aaMode = lcdEnabled ? aaMode : AA_GREYSCALE}）—— 降级到灰度就是「发虚、笔画偏细」的典型成因。</li>
 *   <li><b>字形像素墨量合计</b>：把样例文字离屏渲染后 alpha 的总和。它能直接量出
 *       「发虚 / 变细」——两个模式比大小即可，不必靠肉眼。</li>
 * </ul>
 *
 * <p>反射访问的是 {@code com.sun.javafx.font} 这类非导出包：本项目是 **classpath 启动**，
 * 应用与 JavaFX 同处未命名模块，因此**不需要 {@code --add-opens}**（这与以 {@code --module}
 * 启动的工程不同）；原生镜像里也没有模块限制。取不到时只打「不可用(异常类)」，绝不抛异常
 * 影响启动。
 */
final class RenderDiagnostics {

    /** 度量样例：挑一段含中文、数字、单位、方括号的典型文本 */
    private static final String SAMPLE = "01-左风扇转速[CPU侧] 88°C 6223RPM";

    /** 像素分类阈值：深核=笔画实心处，中间调=边缘部分覆盖，其余算背景 */
    private static final int DARK_MAX = 80;
    private static final int MID_MAX = 200;

    /** 亮度上限：用于「灰度换算越界」自检（与 {@code Script/CompareScreenshots.py} 的 0~255 口径对齐） */
    private static final int GRAY_MAX = 255;

    private RenderDiagnostics() {
    }

    /** 在 {@code start(Stage)} 里调用；此时 JavaFX 工具包已就绪，屏幕与字体信息可读。 */
    static void log() {
        logProperties();
        logRuntime();
        logScreen();
        logFonts();
        logGlyphMetrics();
        logGlyphPixels();
        logLcdComposition();
        probeStrikeMatrix();
        logLcdCompositeMatrix();
        logGlyphProfile();
        probePrismSettings();
        probeGlyphMasks();   // issue #20 根因判定：重放 LCD/灰度两条取掩码路径
    }

    /**
     * 字形掩码探针：把 JavaFX 取字形掩码的**两条分支重放一遗**，用于判定原生镜像里 LCD 掩码为何退化。
     *
     * <p>背景（issue #20 阶段性结论）：Windows 上 {@code prism.lcdtext} 默认 true，因此界面文字走
     * LCD 分支 —— {@code DWGlyph.getPixelData → getLCDMask()} → DWrite 的
     * {@code GetAlphaTextureBounds(DWRITE_TEXTURE_CLEARTYPE_3x1)}；当它拿不到掩码时，JavaFX 会回退到
     * {@code getD2DMask(..., lcd=true)}，而那条回退里**唯一设置抗锯齿的语句被 {@code if (!lcd)} 跳过**，
     * 于是掩码二值化，且写出 R=G=B（连色边都没有）—— 与真机实测完全吻合。
     *
     * <p><b>本探针必须在两个构建里都跑</b>，并对照四项：
     * <ol>
     *   <li>{@code GetAlphaTextureBounds}：<b>null（调用失败）</b> vs <b>空矩形（调用成功但无该纹理）</b>
     *       —— 这两者根因完全不同，所以分开记；</li>
     *   <li>{@code CreateAlphaTexture(CLEARTYPE_3x1)} 是否 null / 长度；</li>
     *   <li><b>调用输入</b>（fontFace 指针 / fontEmSize / glyphIndices / renderingMode / pixelsPerDip / x,y）
     *       —— 用于判定「输入是否相同」；</li>
     *   <li>掩码字节性质（R=G=B 占比、部分覆盖占比、取值档数）。</li>
     * </ol>
     *
     * <p><b>判定规则</b>：输入相同而结果不同 ⇒ 进程环境 / 运行期配置；输入不同 ⇒ 代码路径或编译期固化；
     * 两者都相同却仍失败 ⇒ 需做最小复现。任何一步失败都只打「不可用(...)」，不影响启动。
     */
    private static void probeGlyphMasks() {
        try {
            Class<?> factoryCls = Class.forName("com.sun.javafx.font.PrismFontFactory");
            Object factory = factoryCls.getMethod("getFontFactory").invoke(null);
            Object resource = null;
            String usedFamily = null;
            // 注意：“System”是 JavaFX 的**映射名**，getFontResource 认的是**真实字族名** ——
            // 2026-10-11 就因为传 “System” 导致两个构建都打印「取不到字体资源」，白跑一轮。
            // 逐个试到成功为止；探测用的字族对结论无影响，只要两个构建用**同一个**即可。
            for (String candidate : new String[]{"Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei",
                    "Arial", "Tahoma", "Consolas"}) {
                resource = factoryCls
                        .getMethod("getFontResource", String.class, boolean.class, boolean.class, boolean.class)
                        .invoke(factory, candidate, false, false, true);
                if (resource != null) {
                    usedFamily = candidate;
                    break;
                }
            }
            if (resource == null) {
                line("字形掩码探针 不可用(测过的字族全部取不到：Segoe UI / Microsoft YaHei UI / Arial / Tahoma / Consolas)");
                return;
            }
            line("字形掩码探针 使用字族=" + usedFamily + "（两个构建必须一致，否则不可比）");
            Class<?> frCls = Class.forName("com.sun.javafx.font.FontResource");
            int grayMode = frCls.getField("AA_GREYSCALE").getInt(null);
            int lcdMode = frCls.getField("AA_LCD").getInt(null);
            Class<?> btCls = Class.forName("com.sun.javafx.geom.transform.BaseTransform");
            Object identity = btCls.getField("IDENTITY_TRANSFORM").get(null);
            var getStrike = frCls.getMethod("getStrike", float.class, btCls, int.class);

            probeMaskOfStrike(getStrike.invoke(resource, 12f, identity, grayMode), "灰度");
            Object lcdStrike = getStrike.invoke(resource, 12f, identity, lcdMode);
            probeMaskOfStrike(lcdStrike, "LCD");
            probeLcdAnalysis(lcdStrike, "LCD");
        } catch (Throwable t) {
            line("字形掩码探针 不可用(" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
        }
    }

    /** 取一整段样例文字的掩码，统计「部分覆盖 / 取值档数 / R=G=B 占比」等性质。 */
    private static void probeMaskOfStrike(Object strike, String label) {
        try {
            Class<?> strikeCls = Class.forName("com.sun.javafx.font.FontStrike");
            Class<?> glyphCls = Class.forName("com.sun.javafx.font.Glyph");
            Object aaMode = strikeCls.getMethod("getAAMode").invoke(strike);
            var getGlyph = strikeCls.getMethod("getGlyph", char.class);
            var getPixelData = glyphCls.getMethod("getPixelData", int.class);
            var isLcdGlyph = glyphCls.getMethod("isLCDGlyph");
            var getWidth = glyphCls.getMethod("getWidth");
            var getHeight = glyphCls.getMethod("getHeight");

            long zero = 0;
            long full = 0;
            long partial = 0;
            long rgbSame = 0;
            long pixels = 0;
            var values = new TreeSet<Integer>();
            var sizes = new StringBuilder();
            byte[] firstMask = null;
            int firstByteWidth = 0;
            int firstHeight = 0;
            boolean firstLcd = false;
            for (char ch : "MW#8".toCharArray()) {
                Object glyph = getGlyph.invoke(strike, ch);
                if (glyph == null) {
                    continue;
                }
                boolean lcd = (boolean) isLcdGlyph.invoke(glyph);
                sizes.append(ch).append('=').append(getWidth.invoke(glyph)).append('x')
                        .append(getHeight.invoke(glyph)).append(' ');
                byte[] mask = (byte[]) getPixelData.invoke(glyph, 0);
                if (mask == null) {
                    sizes.append("(掩码 null) ");
                    continue;
                }
                if (firstMask == null) {
                    firstMask = mask;
                    firstByteWidth = (int) getWidth.invoke(glyph);
                    firstHeight = (int) getHeight.invoke(glyph);
                    firstLcd = lcd;
                }
                int step = lcd ? 3 : 1;
                int count = mask.length / step;
                pixels += count;
                for (int i = 0; i < count; i++) {
                    int v0 = mask[i * step] & 0xFF;
                    int v1 = lcd ? mask[i * step + 1] & 0xFF : v0;
                    int v2 = lcd ? mask[i * step + 2] & 0xFF : v0;
                    values.add(v0);
                    if (lcd) {
                        values.add(v1);
                        values.add(v2);
                        if (v0 == v1 && v1 == v2) {
                            rgbSame++;
                        }
                    }
                    if (v0 == 0) {
                        zero++;
                    } else if (v0 == 255) {
                        full++;
                    } else {
                        partial++;
                    }
                }
            }
            line(String.format("字形掩码探针[%s] aaMode=%s 尺寸=%s 像素=%d 零=%d 满=%d 部分覆盖=%d(%.2f%%) 取值档数=%d%s",
                    label, aaMode, sizes.toString().trim(), pixels, zero, full, partial,
                    pixels == 0 ? 0.0 : 100.0 * partial / pixels, values.size(),
                    label.equals("LCD")
                            ? String.format(" R=G=B占比=%.1f%%", pixels == 0 ? 0.0 : 100.0 * rgbSame / pixels)
                            : ""));
            dumpMaskAscii(firstMask, firstByteWidth, firstHeight, firstLcd, label);
        } catch (Throwable t) {
            line("字形掩码探针[" + label + "] 不可用(" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
        }
    }

    /**
     * 把一个字形掩码逐像素打成 ASCII（越暗字符越重）—— 日志里直接看出掩码是**平滑灰阶**还是**二值**。
     *
     * <p>LCD 掩码是 3 字节/像素（R,G,B 三份），这里只取 R 通道；灰度掩码是 1 字节/像素。
     */
    private static void dumpMaskAscii(byte[] mask, int byteWidth, int height, boolean lcd, String label) {
        if (mask == null || byteWidth <= 0 || height <= 0) {
            return;
        }
        int pixelWidth = lcd ? byteWidth / 3 : byteWidth;
        if (pixelWidth <= 0 || (long) byteWidth * height > mask.length) {
            return;
        }
        int rows = Math.min(height, 14);
        int cols = Math.min(pixelWidth, 44);
        line(String.format("字形掩码 ASCII[%s%s] byteWidth=%d 高=%d（1 字符=1 像素，@ 最暗、空格最亮）%s",
                label, lcd ? " R通道" : "", byteWidth, height,
                lcd ? "；若三通道完全相同则说明走了 D2D 回退" : ""));
        for (int y = 0; y < rows; y++) {
            StringBuilder sb = new StringBuilder("字形掩码 ASCII |");
            for (int x = 0; x < cols; x++) {
                int index = lcd ? y * byteWidth + x * 3 : y * byteWidth + x;
                int value = mask[index] & 0xFF;
                sb.append(RAMP.charAt(Math.min(RAMP.length() - 1, value * RAMP.length() / 256)));
            }
            sb.append('|');
            line(sb.toString());
        }
    }

    /** ASCII 灰度分级：越靠前越暗 */
    private static final String RAMP = "@%#*+=-:. ";

    /**
     * 重放 JavaFX 在 {@code getLCDMask()} 里的两步 DWrite 查询 —— 这是判定「哪一步失败」的关键。
     *
     * <p>区分重点：返回 <b>null</b> = 调用失败（HRESULT 错误，分析对象或环境有问题）；
     * 返回 <b>空矩形 (0,0,0,0)</b> = 调用成功但 DWrite 认为这次不需要 ClearType 纹理（参数/模式问题）。
     */
    private static void probeLcdAnalysis(Object strike, String label) {
        try {
            Class<?> strikeCls = Class.forName("com.sun.javafx.font.FontStrike");
            Object glyph = strikeCls.getMethod("getGlyph", char.class).invoke(strike, 'M');
            Class<?> glyphCls = Class.forName("com.sun.javafx.font.directwrite.DWGlyph");
            if (glyph == null || !glyphCls.isInstance(glyph)) {
                line("LCD 分析探针[" + label + "] 不可用(字形不是 DWGlyph：命中回退字体)");
                return;
            }
            var runField = glyphCls.getDeclaredField("run");
            runField.setAccessible(true);
            Object run = runField.get(glyph);
            Class<?> runCls = run.getClass();
            StringBuilder input = new StringBuilder();
            for (String f : new String[]{"fontFace", "fontEmSize", "glyphIndices", "bidiLevel", "isSideways"}) {
                var field = runCls.getDeclaredField(f);
                field.setAccessible(true);
                Object value = field.get(run);
                input.append(f).append('=').append(value instanceof Number ? longValue(value) : value).append(' ');
            }
            int renderingMode = 4;   // OS.DWRITE_RENDERING_MODE_NATURAL
            try {
                var dmCls = Class.forName("com.sun.javafx.font.directwrite.DWFontStrike");
                var subPixelY = dmCls.getDeclaredField("SUBPIXEL_Y");
                subPixelY.setAccessible(true);
                if (subPixelY.getBoolean(null)) {
                    renderingMode = 5;   // NATURAL_SYMMETRIC
                }
            } catch (Throwable ignored) {
                // 取不到就用默认值
            }
            line(String.format("LCD 分析探针[%s] 输入: %srenderingMode=%d pixelsPerDip=1.0 x=0 y=0",
                    label, input, renderingMode));

            var createAnalysis = glyphCls.getDeclaredMethod("createAnalysis", float.class, float.class);
            createAnalysis.setAccessible(true);
            Object analysis = createAnalysis.invoke(glyph, 0f, 0f);
            if (analysis == null) {
                line("LCD 分析探针[" + label + "] createAnalysis 返回 null（分析对象创建失败）");
                return;
            }
            Class<?> analysisCls = analysis.getClass();
            var boundsMethod = analysisCls.getDeclaredMethod("GetAlphaTextureBounds", int.class);
            boundsMethod.setAccessible(true);
            var textureMethod = analysisCls.getDeclaredMethod("CreateAlphaTexture", int.class,
                    Class.forName("com.sun.javafx.font.directwrite.RECT"));
            textureMethod.setAccessible(true);

            int clearType = 1;   // OS.DWRITE_TEXTURE_CLEARTYPE_3x1
            int aliased = 0;     // OS.DWRITE_TEXTURE_ALIASED_1x1
            Object clearBounds = boundsMethod.invoke(analysis, clearType);
            Object aliasBounds = boundsMethod.invoke(analysis, aliased);
            line(String.format("LCD 分析探针[%s] GetAlphaTextureBounds: CLEARTYPE_3x1 → %s ；ALIASED_1x1 → %s",
                    label, describeRect(clearBounds), describeRect(aliasBounds)));
            if (clearBounds != null) {
                Object texture = textureMethod.invoke(analysis, clearType, clearBounds);
                line(String.format("LCD 分析探针[%s] CreateAlphaTexture(CLEARTYPE_3x1) → %s",
                        label, texture == null ? "null（失败）" : ("长度=" + ((byte[]) texture).length)));
            }
        } catch (Throwable t) {
            line("LCD 分析探针[" + label + "] 不可用(" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
        }
    }

    /** 把 RECT 描述成人读的形式，并明确区分 null（调用失败）与空矩形。 */
    private static String describeRect(Object rect) {
        if (rect == null) {
            return "null（调用失败/HRESULT 错误）";
        }
        try {
            Class<?> rectCls = rect.getClass();
            int left = 0;
            int top = 0;
            int right = 0;
            int bottom = 0;
            for (var f : rectCls.getDeclaredFields()) {
                f.setAccessible(true);
                int v = f.getInt(rect);
                switch (f.getName()) {
                    case "left" -> left = v;
                    case "top" -> top = v;
                    case "right" -> right = v;
                    case "bottom" -> bottom = v;
                    default -> { }
                }
            }
            String empty = (right - left == 0 || bottom - top == 0) ? "（空矩形：无该类型纹理）" : "";
            return "(" + left + "," + top + "," + right + "," + bottom + ")" + empty;
        } catch (Throwable t) {
            return "所在类=" + rect.getClass().getSimpleName() + "（读字段失败：" + t.getMessage() + "）";
        }
    }

    private static long longValue(Object number) {
        // 注意：fontEmSize 等字段是 Float，直接 (Long) 装会抛 ClassCastException
        // （V0.2.0.00036 真机就是这个：Float cannot be cast to Long）。统一走 Number.doubleValue()。
        return Math.round(((Number) number).doubleValue());
    }

    /**
     * 屏幕字形像素：抓**窗口在屏幕上的真实像素**，就地量抗锯齿。
     *
     * <p>为什么非得抓屏幕：离屏 {@link Text#snapshot} 走的是另一条路径 —— 实测两个构建的离屏
     * 结果**逐像素一致且都带灰度抗锯齿**（1.0 倍与 1.5 倍皆然），而屏幕上的差异
     * （原生包**完全没有抗锯齿**、JVM 是 ClearType 次像素）在离屏数据里**根本看不到**（issue #20）。
     *
     * <p><b>抓法</b>：先抓**整屏**。整屏原点 (0,0) 在「逻辑坐标」与「物理像素」两种解释下都指向
     * 同一个点，因此可以用返回图的尺寸**反推坐标系比例**（本机 1.5），再把窗口矩形映射成像素矩形。
     * 这样就不必猜 {@code Robot} 按哪种坐标解释入参 —— 2026-10-10 的第一版直接按逻辑坐标抓
     * **窗口内一小块**，两种模式都抓到一片空白（同一句「没抓到文字」），即栽在这一点上。
     *
     * <p><b>指标同口径</b>（与 {@code Script/CompareScreenshots.py} 一致）：只在**文字边缘像素**
     * （灰度 &lt; 200 且指定邻域内存在深核）上统计：
     * <ul>
     *   <li>中间调像素（80~200）：**有抗锯齿才会有部分覆盖的像素**，纯二值栅格化时为 0；</li>
     *   <li>AA 比 = 中间调 / 深核；色边均值 = {@code max(|R-G|, |G-B|)}，次像素渲染才显著大于 0。</li>
     * </ul>
     *
     * <p>窗口显示后延迟 400 ms 再抓（太早会抓到未绘制的窗口）。任何一步失败只打「不可用(...)」。
     *
     * <p><b>当前状态（2026-10-11）：已停用，不再由 {@code FanControlApp} 调用。</b>
     * 真机两次验证都证明它抓到的**不是本窗口**：直方图显示 **99.7% 的像素挤在 208-223 一个桶、
     * 224-255 一个都没有**（而本窗口背景是白的，不可能没有亮像素），ASCII 也只显出右边框、无字形。
     * 坐标映射仍有一处没查清，而它已经耗掉三轮 —— 验收改用可靠的那条线：
     * **真实截图 + {@code Script/CompareScreenshots.py}**。代码保留，供日后需要时继续排查。
     */
    static void logScreenGlyphPixels(Scene scene) {
        PauseTransition delay = new PauseTransition(Duration.millis(400));
        delay.setOnFinished(e -> {
            try {
                captureOnScreen(scene);
            } catch (Throwable t) {
                line("屏幕字形像素 不可用(" + t.getClass().getSimpleName() + ")");
            }
        });
        delay.play();
    }

    private static void captureOnScreen(Scene scene) {
        var visual = Screen.getPrimary().getVisualBounds();
        WritableImage shot = new Robot().getScreenCapture(null, 0, 0,
                visual.getWidth(), visual.getHeight(), false);
        int shotW = (int) shot.getWidth();
        int shotH = (int) shot.getHeight();
        // 入参是逻辑坐标、返回图是物理像素 → 比例即 outputScale（本机 1.5）
        double scale = visual.getWidth() > 0 ? shotW / visual.getWidth() : 1.0;

        Bounds win = scene.getRoot().localToScreen(scene.getRoot().getBoundsInLocal());
        if (win == null) {
            line("屏幕字形像素 不可用(窗口不在屏幕上)");
            return;
        }
        int x0 = clamp((int) Math.floor((win.getMinX() - visual.getMinX()) * scale), 0, shotW);
        int y0 = clamp((int) Math.floor((win.getMinY() - visual.getMinY()) * scale), 0, shotH);
        int x1 = clamp((int) Math.ceil((win.getMaxX() - visual.getMinX()) * scale), 0, shotW);
        int y1 = clamp((int) Math.ceil((win.getMaxY() - visual.getMinY()) * scale), 0, shotH);
        int w = x1 - x0;
        int h = y1 - y0;
        if (w < 2 || h < 2) {
            line("屏幕字形像素 不可用(窗口像素区过小)");
            return;
        }

        PixelReader reader = shot.getPixelReader();
        int[] gray = new int[w * h];
        boolean[] darkMask = new boolean[w * h];
        long graySum = 0;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int g = luminance(reader.getColor(x0 + i, y0 + j));
                gray[j * w + i] = g;
                darkMask[j * w + i] = g < DARK_MAX;
                graySum += g;
            }
        }
        double meanGray = (double) graySum / (w * h);
        if (meanGray > GRAY_MAX) {
            // 诊断自身的错误必须**显式失败**，不能安静地给出看似合理的假数字（2026-10-10 踩过）
            line(String.format("屏幕字形像素 不可用(灰度换算越界：均灰度=%.1f > %d，这是诊断自身的 bug)",
                    meanGray, GRAY_MAX));
            return;
        }
        dumpHistogram(gray);
        dumpAscii(gray, w, h);
        long dark = 0;
        long mid = 0;
        long textPixels = 0;
        double fringeSum = 0;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int g = gray[j * w + i];
                if (g >= MID_MAX || !hasDarkNeighbor(darkMask, w, h, i, j)) {
                    continue;  // 只看文字边缘像素：排除按钮渐变 / 窗口圆角这类柔和过渡
                }
                if (g < DARK_MAX) {
                    dark++;
                } else {
                    mid++;
                }
                Color c = reader.getColor(x0 + i, y0 + j);
                textPixels++;
                fringeSum += 255 * Math.max(Math.abs(c.getRed() - c.getGreen()),
                        Math.abs(c.getGreen() - c.getBlue()));
            }
        }
        double aaRatio = dark == 0 ? -1 : (double) mid / dark;
        double fringe = textPixels == 0 ? 0 : fringeSum / textPixels;
        String verdict;
        if (dark == 0) {
            verdict = "没抓到文字(窗口未绘制或坐标不对？)";
        } else if (mid == 0 || aaRatio < 0.05) {
            verdict = "无抗锯齿(纯二值栅格化)";
        } else if (fringe >= 2.0) {
            verdict = "次像素抗锯齿(ClearType 类)";
        } else {
            verdict = "灰度抗锯齿";
        }
        line(String.format("屏幕字形像素 整屏%dx%d(scale=%.2f) 窗口像素区=%d,%d %dx%d 均灰度=%.1f"
                        + " 深核像素=%d 中间调像素=%d AA比=%.3f 色边均值=%.2f 判定=%s",
                shotW, shotH, scale, x0, y0, w, h, meanGray,
                dark, mid, aaRatio, fringe, verdict));
    }

    /**
     * 亮度（0~255）。
     *
     * <p><b>换算口径的坑</b>：JavaFX 的 {@code Color#getRed()} 等返回的是 **0~1 的 double**，
     * 而 {@code Script/CompareScreenshots.py} 里读的是 **0~255 的字节** —— 两边写法不能照搬。
     * 2026-10-10 就因为把 {@code 299*r + 587*g + 114*b} 直接当灰度用（值域 0~1000），
     * 算出「均灰度=864.4」这种一眼假的数字，**却照样给出了一个判定**（“次像素抗锯齿”），
     * 极其容易被当成结论采信。故这里统一走本方法，并在上方加 {@link #GRAY_MAX} 越界自检。
     */
    private static int luminance(Color c) {
        return (int) Math.round(255 * (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()));
    }

    /**
     * 灰度直方图（16 档，每档 16 级）。
     *
     * <p>用途：一眼看出抓到的画面是**二值**（只有最暗与最亮两座峰、中间空）还是**有抗锯齿**
     * （中间分布开）。用于自证抓屏本身是否忠实 —— 2026-10-10 真机上应用内抓屏对「原生（纯二值）」
     * 与「JVM（ClearType）」给出了几乎相同的数字，与**真实截图**量出的结论相反，
     * 故怀疑抓屏被重采样过；直方图与 ASCII 就是为查清这件事加的。
     */
    private static void dumpHistogram(int[] gray) {
        int[] buckets = new int[16];
        for (int g : gray) {
            buckets[Math.min(15, g / 16)]++;
        }
        StringBuilder sb = new StringBuilder("屏幕字形像素 灰度直方图（16 档）");
        for (int i = 0; i < buckets.length; i++) {
            sb.append(' ').append(i * 16).append('-').append(i * 16 + 15).append('=').append(buckets[i]);
        }
        line(sb.toString());
    }

    /**
     * 把捕获到的画面里的一小段**逐像素打印成 ASCII**（越暗的像素字符越重）。
     *
     * <p>用途：把「到底抓到了什么」变成日志里能直接看的证据 —— 是我们自己的界面文字，
     * 还是桌面 / 窗口阴影 / 空白；字形是硬边还是被抹糊。每字符 = 1 像素，锚点取扫到的第一个深核像素。
     */
    private static void dumpAscii(int[] gray, int w, int h) {
        int anchorX = -1;
        int anchorY = -1;
        for (int j = 0; j < h && anchorX < 0; j++) {
            for (int i = 0; i < w; i++) {
                if (gray[j * w + i] < DARK_MAX) {
                    anchorX = i;
                    anchorY = j;
                    break;
                }
            }
        }
        if (anchorX < 0) {
            line("屏幕字形像素 ASCII：画面里没有深核像素（没抓到任何文字）");
            return;
        }
        int cw = Math.min(72, w);
        int chh = Math.min(14, h);
        int sx = clamp(anchorX - 4, 0, w - cw);
        int sy = clamp(anchorY - 2, 0, h - chh);
        line(String.format("屏幕字形像素 ASCII（锚点=%d,%d，区域=%d,%d %dx%d，1 字符=1 像素，越暗字符越重）",
                anchorX, anchorY, sx, sy, cw, chh));
        String ramp = "@%#*+=-:. ";
        for (int j = 0; j < chh; j++) {
            StringBuilder sb = new StringBuilder("屏幕字形像素 ASCII |");
            for (int i = 0; i < cw; i++) {
                int g = gray[(sy + j) * w + (sx + i)];
                sb.append(ramp.charAt(Math.min(ramp.length() - 1, g * ramp.length() / 256)));
            }
            sb.append('|');
            line(sb.toString());
        }
    }

    private static boolean hasDarkNeighbor(boolean[] darkMask, int w, int h, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx;
                int ny = y + dy;
                if (nx >= 0 && nx < w && ny >= 0 && ny < h && darkMask[ny * w + nx]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int clamp(int value, int lo, int hi) {
        return value < lo ? lo : Math.min(value, hi);
    }

    private static void logProperties() {
        line("原生镜像标记 org.graalvm.nativeimage.imagecode=" + prop("org.graalvm.nativeimage.imagecode"));
        line("属性 prism.order=" + prop("prism.order")
                + " prism.lcdtext=" + prop("prism.lcdtext")
                + " prism.subpixeltext=" + prop("prism.subpixeltext")
                + " glass.win.uiScale=" + prop("glass.win.uiScale")
                + " glass.win.renderScale=" + prop("glass.win.renderScale")
                + "（<默认> = 未显式设置）");
        // 临时缓解措施的开关与判定结果（issue #48）：用来区分「程序设的 / 用户设的 / 开关关掉了」
        line("原生文本灰度默认 " + NativeImageRenderDefaults.decision()
                + " 开关 " + NativeImageRenderDefaults.GREYSCALE_DEFAULT_SWITCH + "="
                + prop(NativeImageRenderDefaults.GREYSCALE_DEFAULT_SWITCH));
    }

    private static void logRuntime() {
        line("运行时 JVM=" + System.getProperty("java.vm.name")
                + " JavaFX=" + System.getProperty("javafx.runtime.version")
                + " Java=" + System.getProperty("java.version"));
    }

    private static void logScreen() {
        Screen primary = Screen.getPrimary();
        line("屏幕 dpi=" + primary.getDpi()
                + " outputScale=" + primary.getOutputScaleX() + "x" + primary.getOutputScaleY()
                + " 显示器数=" + Screen.getScreens().size()
                + " 视觉边界=" + primary.getVisualBounds());
    }

    private static void logFonts() {
        Font def = Font.getDefault();
        int familyCount = new TreeSet<>(Font.getFamilies()).size();
        line("默认字体 族=" + def.getFamily()
                + " 名称=" + def.getName()
                + " 字号=" + def.getSize()
                + " 可用字体族数=" + familyCount);
        // 字体工厂与 LCD 对比度走反射（com.sun.javafx.font 是非导出包；classpath 启动无需 add-opens）
        Object factory = invoke("getFontFactory");
        line("字体工厂=" + factory
                + " LCD对比度=" + invoke("getLCDContrast")
                + " LCD文本支持=" + invokeOnFactory("isLCDTextSupported")
                + " subPixelMode=" + invokeOnFactory("getSubPixelMode"));
        // 应用层请求的平滑类型（Text/Labeled 的默认值是 gray，与上行的「硬件是否支持」是两件事）
        Text probe = new Text("probe");
        line("文字平滑类型（应用请求）=" + probe.getFontSmoothingType()
                + "（GRAY=" + FontSmoothingType.GRAY + ", LCD=" + FontSmoothingType.LCD + "）");
    }

    private static void logGlyphMetrics() {
        Text text = new Text(SAMPLE);
        text.setFont(Font.font(14));
        var bounds = text.getLayoutBounds();
        line(String.format("字形度量 14px 文本宽=%.2f 高=%.2f 基线=%.2f（样例：%s）",
                bounds.getWidth(), bounds.getHeight(), text.getBaselineOffset(), SAMPLE));
    }

    /**
     * 字形像素的量化指纹：把样例文字离屏渲染，统计「墨量合计」（alpha 总和）与
     * 「次像素着色像素数」（R/G/B 不相等的像素）。
     *
     * <p>为什么需要它：上面那些开关全相同也不能证明**栅格化结果相同** ——
     * 而「发虚 / 笔画偏细」的本质就是**墨量变少**。有了这个数字，两种模式可直接比大小，
     * 不再靠肉眼。若本例打印「墨量=0」，则是快照失败（不是字体问题），见下方注释。
     */
    private static void logGlyphPixels() {
        // 先测 1.0 倍（纯栅格化基线）
        logGlyphPixelsAt(1.0, 1.0);
        // 再测屏幕的实际缩放：**屏幕上的字是按这个倍率栅格化的**，
        // 而快照默认只按 1.0 渲染 —— 差异完全可能只在小数倍率下出现（本机 outputScale=1.5）。
        double sx = Screen.getPrimary().getOutputScaleX();
        double sy = Screen.getPrimary().getOutputScaleY();
        if (Math.abs(sx - 1.0) > 0.001 || Math.abs(sy - 1.0) > 0.001) {
            logGlyphPixelsAt(sx, sy);
        }
    }

    /**
     * <b>LCD 合成探针</b>：把**强制 LCD 平滑**的文字离屏渲染出来，量化**合成结果**。
     *
     * <p>为什么非加不可（issue #20 的关键教训）：上面的 {@link #logGlyphPixelsAt} 造的是
     * {@code new Text(SAMPLE)}，**从未设过平滑类型** —— 用的是 JavaFX 默认的
     * {@link FontSmoothingType#GRAY}。也就是说此前「两个构建逐像素一致」的结论
     * **只覆盖了灰度路径**，而真机截图上出问题的 LCD 合成一次都没测到：
     * 原生包笔画更粗、更糊、几乎没有次像素色边，正是这条路上的症状。
     *
     * <p>测法：{GRAY, LCD} × {白底, 透明底} 各渲染一次，外加一个走 modena CSS 的
     * {@link Label}（CSS 里 {@code -fx-font-smoothing-type: lcd}），并直接读出它的
     * {@code .text} 子节点实际生效的平滑类型。判据：
     *
     * <ul>
     *   <li>LCD 组合应当出现**次像素色边**（最大通道差 &gt; 0），墨量与 GRAY 组合相近；</li>
     *   <li>若 LCD 组合「色边=0 且墨量明显偏离」⇒ 合成有问题，**且离屏即可复现**
     *       （不必再依赖屏幕截图）；</li>
     *   <li>若两个构建的 LCD 组合数字不同 ⇒ 已有可复现的最小场景，可继续二分。</li>
     * </ul>
     */
    private static void logLcdComposition() {
        double scale = Math.max(1.0, Screen.getPrimary().getOutputScaleX());
        // ① 先确认 Label 的 .text 子节点到底是不是 LCD（modena CSS 是否生效）
        try {
            Label label = new Label(SAMPLE);
            new Scene(new Group(label));
            label.applyCss();
            label.layout();
            Node textNode = label.lookup(".text");
            String actual = textNode instanceof Text t
                    ? String.valueOf(t.getFontSmoothingType()) : "取不到 .text 子节点";
            line("LCD 合成探针 Label 的平滑类型（modena CSS 实际生效值）=" + actual);
        } catch (Throwable e) {
            line("LCD 合成探针 Label 平滑类型 不可用(" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
        // ② Text：强制 GRAY / LCD，各配白底与透明底
        for (FontSmoothingType type : FontSmoothingType.values()) {
            for (Color fill : new Color[]{Color.WHITE, Color.TRANSPARENT}) {
                String tag = "LCD 合成探针[Text " + type + " "
                        + (fill == Color.WHITE ? "白底" : "透明底") + "]";
                try {
                    Text text = new Text(SAMPLE);
                    text.setFont(Font.font(14));
                    text.setFontSmoothingType(type);
                    text.setFill(Color.BLACK);
                    new Scene(new Group(text));
                    line(tag + " " + fingerprint(snapshot(text, fill, scale)));
                } catch (Throwable e) {
                    line(tag + " 不可用(" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
                }
            }
        }
        // ③ Label（CSS 路径）的合成结果
        try {
            Label label = new Label(SAMPLE);
            new Scene(new Group(label));
            label.applyCss();
            label.layout();
            line("LCD 合成探针[Label CSS 路径] " + fingerprint(snapshot(label, Color.WHITE, scale)));
        } catch (Throwable e) {
            line("LCD 合成探针[Label CSS 路径] 不可用(" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
        // ④ 分通道算术：判定「每通道覆盖」到底还在不在（黑字/红字 × GRAY/LCD）
        logLcdChannelSplit(scale);
    }

    /**
     * <b>分通道算术判据</b>：分别统计渲染结果 R/G/B 三个通道各自的「墨量」。
     *
     * <p>为什么要它：真机 `V0.2.0.00037` 上原生包的 LCD 合成三个数很反常
     * ——`色边像素=0 最大通道差=0 中间调=0` 且墨量放大 1.37~1.79 倍，指向
     * 「**每通道覆盖被压成了单值**」。这条判据把它变成可判定的算术：
     *
     * <ul>
     *   <li><b>黑字白底</b>：正确合成时 {@code out_c = 1 - m_c}，因为掩码的 R/G/B 不同，
     *       三通道墨量就**必须不等**（JVM 实测正是如此）。三个数**完全相等** ⇒ 覆盖已被压成单值。</li>
     *   <li><b>红字白底</b>（fg 只在上 R 通道）进一步区分：若三通道仍相等，
     *       说明连**前景颜色**都没有按通道作用（错误更深一层）；若不等，
     *       则颜色作用正常、只有掩码的每通道覆盖丢了。</li>
     * </ul>
     *
     * <p>两个构建同一行对比即可得出结论，不靠肉眼、也不靠屏幕。
     */
    private static void logLcdChannelSplit(double scale) {
        for (FontSmoothingType type : FontSmoothingType.values()) {
            for (Color fg : new Color[]{Color.BLACK, Color.RED}) {
                String tag = "LCD 分通道[" + type + " 白底 " + (fg == Color.BLACK ? "黑字" : "红字") + "]";
                try {
                    Text text = new Text("MW#8");
                    text.setFont(Font.font(14));
                    text.setFontSmoothingType(type);
                    text.setFill(fg);
                    new Scene(new Group(text));
                    long[] ch = channelSums(snapshot(text, Color.WHITE, scale));
                    line(String.format("%s 墨量(r,g,b)=%d/%d/%d 三通道相等=%s",
                            tag, ch[0], ch[1], ch[2],
                            (ch[0] == ch[1] && ch[1] == ch[2])
                                    ? "是（每通道覆盖已被压成单值）" : "否（每通道覆盖仍在）"));
                } catch (Throwable e) {
                    line(tag + " 不可用(" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
                }
            }
        }
    }

    /**
     * <b>单字形逐列墨量剖面</b>：同一参数（Segoe UI / 12px / 1×）下把「掩码」与「合成结果」的
     * 形状并排打出来，用来区分剩下的两种可能。
     *
     * <p>背景：`V0.2.0.00039` 已证明——**同一参数下掩码字节逐字节相同，合成却不同**
     * （原生 `45900/45900/45900`、JVM `25870/25821/25843`）⇒ 缺陷在「掩码安放与取样」这段，
     * 与 DWrite 无关。而「合成 ÷ 掩码」的比值随字号**下降**（12px 1.65 → 21px 1.25），
     * 不是线性缩放，更像**每像素二次叠加**（`2m-m²`）。
     *
     * <p>本剖面区分：
     * <ul>
     *   <li>**形状相同、幅度更大** ⇒ 数据没被换，是合成算术 / 重复上墨的问题；</li>
     *   <li>**形状拉伸、错位或变浅** ⇒ 图集纹理的格式或行步长把数据换了。</li>
     * </ul>
     *
     * <p>注：掩码取 `subPixel=0`，与上屏时按位置量化出的次像素档位可能不同，
     * 故两侧**允许整体平移**，看的是形状。
     */
    private static void logGlyphProfile() {
        try {
            Object factory = invoke("getFontFactory");
            Object resource = Class.forName("com.sun.javafx.font.PrismFontFactory")
                    .getMethod("getFontResource", String.class, boolean.class, boolean.class, boolean.class)
                    .invoke(factory, "Segoe UI", false, false, true);
            Class<?> btCls = Class.forName("com.sun.javafx.geom.transform.BaseTransform");
            Class<?> frCls = Class.forName("com.sun.javafx.font.FontResource");
            int lcdMode = frCls.getField("AA_LCD").getInt(null);
            Object identity = btCls.getField("IDENTITY_TRANSFORM").get(null);
            Object strike = frCls.getMethod("getStrike", float.class, btCls, int.class)
                    .invoke(resource, 12f, identity, lcdMode);
            Class<?> strikeCls = Class.forName("com.sun.javafx.font.FontStrike");
            Class<?> glyphCls = Class.forName("com.sun.javafx.font.Glyph");
            Object glyph = strikeCls.getMethod("getGlyph", char.class).invoke(strike, 'M');
            byte[] mask = (byte[]) glyphCls.getMethod("getPixelData", int.class).invoke(glyph, 0);
            boolean lcd = (boolean) glyphCls.getMethod("isLCDGlyph").invoke(glyph);
            int byteWidth = (int) glyphCls.getMethod("getWidth").invoke(glyph);
            int height = (int) glyphCls.getMethod("getHeight").invoke(glyph);
            int bpp = lcd ? 3 : 1;
            int cols = byteWidth / bpp;
            long[] maskCols = new long[cols];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < cols; x++) {
                    int base = y * byteWidth + x * bpp;
                    for (int c = 0; c < bpp && base + c < mask.length; c++) {
                        maskCols[x] += mask[base + c] & 0xFF;
                    }
                }
            }
            line("字形剖面[掩码 M 12px 1x] 列数=" + cols + " 逐列墨量=" + profile(maskCols));

            Text text = new Text("M");
            text.setFont(Font.font("Segoe UI", 12));
            text.setFontSmoothingType(FontSmoothingType.LCD);
            text.setFill(Color.BLACK);
            new Scene(new Group(text));
            Image image = snapshot(text, Color.WHITE, 1.0);
            line("字形剖面[合成 M 12px 1x] 宽=" + (int) image.getWidth()
                    + " 逐列墨量=" + profile(columnSums(image)));
        } catch (Throwable t) {
            line("字形剖面 不可用(" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
        }
    }

    /**
     * <b>PrismSettings 静态字段全量转储</b>：这是「构建期固化」的最后一个可能落点。
     *
     * <p>为何要它：`PrismSettings` 的字段是 `static final`、**在静态初始化时读系统属性**得到。
     * 若这些字段在 native-image 构建期被初始化（本项目的 `--initialize-at-run-time` 只列到包级，
     * 一旦没盖住或类被提前触达就会被固化），运行时拿到的就是**构建机上的默认值**。
     * 两个构建对比：**只要有任一字段不同，根因就是它**；全部相同则此路排除。
     *
     * <p>只打印基本类型与字符串，避免触发类初始化副作用。
     */
    private static void probePrismSettings() {
        try {
            Class<?> cls = Class.forName("com.sun.prism.impl.PrismSettings");
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (var field : cls.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof Number || value instanceof Boolean || value instanceof CharSequence) {
                    sb.append(field.getName()).append('=').append(value).append(' ');
                    shown++;
                }
            }
            line("PrismSettings（静态字段 " + shown + " 个）= " + sb.toString().trim());
        } catch (Throwable t) {
            line("PrismSettings 不可用(" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
        }
    }

    /** 逐列 R+G+B 墨量（白底黑字：每通道 0..255，三通道相加）。 */
    private static long[] columnSums(Image image) {
        PixelReader reader = image.getPixelReader();
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        long[] cols = new long[w];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                Color c = reader.getColor(x, y);
                double alpha = c.getOpacity();
                cols[x] += Math.round((3.0 - c.getRed() - c.getGreen() - c.getBlue()) * alpha * 255);
            }
        }
        return cols;
    }

    private static String profile(long[] values) {
        StringBuilder sb = new StringBuilder();
        for (long v : values) {
            sb.append(v).append(' ');
        }
        return sb.toString().trim();
    }

    /** 单张图的 R/G/B 三通道各自墨量（白底黑/红字时：墨量 = (1 - 通道值) * 不透明度）。 */
    private static long[] channelSums(Image image) {
        PixelReader reader = image.getPixelReader();
        long r = 0;
        long g = 0;
        long b = 0;
        for (int y = 0; y < (int) image.getHeight(); y++) {
            for (int x = 0; x < (int) image.getWidth(); x++) {
                Color c = reader.getColor(x, y);
                double alpha = c.getOpacity();
                r += Math.round((1.0 - c.getRed()) * alpha * 255);
                g += Math.round((1.0 - c.getGreen()) * alpha * 255);
                b += Math.round((1.0 - c.getBlue()) * alpha * 255);
            }
        }
        return new long[]{r, g, b};
    }

    private static Image snapshot(Node node, Color fill, double scale) {
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(fill);
        params.setTransform(new Scale(scale, scale));
        return node.snapshot(params, null);
    }

    /**
     * 一张渲染结果的字形指纹。用「覆盖率」而非单纯 alpha：LCD 掩码把三个次像素的覆盖
     * 分别放在 R/G/B 里，所以 {@code 覆盖率 = alpha * (1 - 亮度)} 更能反映真实上墨量。
     */
    private static String fingerprint(Image image) {
        PixelReader reader = image.getPixelReader();
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        long dark = 0;
        long mid = 0;
        long ink = 0;
        long colored = 0;
        int maxSpread = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                Color c = reader.getColor(x, y);
                double gray = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
                int level = (int) Math.round(c.getOpacity() * (1.0 - gray) * 255);
                ink += level;
                if (level > 179) {
                    dark++;
                } else if (level > 25) {
                    mid++;
                }
                int spread = (int) Math.round(255 * Math.max(Math.abs(c.getRed() - c.getGreen()),
                        Math.abs(c.getGreen() - c.getBlue())));
                if (spread > maxSpread) {
                    maxSpread = spread;
                }
                if (spread > 8) {
                    colored++;
                }
            }
        }
        return String.format("宽=%d 高=%d 深核=%d 中间调=%d 墨量=%d 色边像素=%d 最大通道差=%d",
                w, h, dark, mid, ink, colored, maxSpread);
    }

    /**
     * <b>掩码参数矩阵</b>：把同一套分通道统计跑在多个「字号 × 缩放」上。
     *
     * <p>为何必须补：先前那个「两个构建掩码逐字节相同」的结论用的是
     * <b>size=12 + 单位变换</b>，而<b>界面上真正显示的文字是 size=14、并且带屏幕缩放
     * （本机 1.5×）</b>。而 `V0.2.0.00038` 已证明：LCD 合成在原生包里把三通道压成了一个值
     * （黑字白底 `126480/126480/126480`，JVM 为 `90666/91021/90713`），
     * 因此 **“DWrite 在具体字号/缩放下就返回了退化掩码”** 与
     * **“管线把三通道压平”** 这两种可能必须分开 —— 这个矩阵就是分界点：
     * 看 `R=G=B 占比` 与掩码尺寸随参数的走向。
     */
    private static void probeStrikeMatrix() {
        try {
            Object factory = invoke("getFontFactory");
            Class<?> btCls = Class.forName("com.sun.javafx.geom.transform.BaseTransform");
            Object identity = btCls.getField("IDENTITY_TRANSFORM").get(null);
            Class<?> frCls = Class.forName("com.sun.javafx.font.FontResource");
            int lcdMode = frCls.getField("AA_LCD").getInt(null);
            Object resource = Class.forName("com.sun.javafx.font.PrismFontFactory")
                    .getMethod("getFontResource", String.class, boolean.class, boolean.class, boolean.class)
                    .invoke(factory, "Segoe UI", false, false, true);
            if (resource == null) {
                line("掩码矩阵 不可用(取不到字体资源)");
                return;
            }
            var getStrike = frCls.getMethod("getStrike", float.class, btCls, int.class);
            for (float size : new float[]{12f, 14f, 21f}) {
                for (double scale : new double[]{1.0, 1.5}) {
                    Object transform = identity;
                    if (scale != 1.0) {
                        transform = scaleTransform(btCls, scale);
                    }
                    Object strike = getStrike.invoke(resource, size, transform, lcdMode);
                    line(String.format("掩码矩阵[size=%.0f scale=%.2f] 变换=%s %s",
                            size, scale, transform, maskChannelStats(strike)));
                }
            }
        } catch (Throwable t) {
            line("掩码矩阵 不可用(" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
        }
    }

    /** 造一个 geom 层（com.sun.javafx.geom.transform）的缩放变换，供 getStrike 使用。 */
    private static Object scaleTransform(Class<?> btCls, double scale) {
        try {
            return btCls.getMethod("getScaleInstance", double.class, double.class)
                    .invoke(null, scale, scale);
        } catch (Throwable ignored) {
            // 退一步：用 Affine
            try {
                Class<?> affCls = Class.forName("com.sun.javafx.geom.transform.Affine");
                Object aff = affCls.getConstructor().newInstance();
                affCls.getMethod("setToScale", float.class, float.class)
                        .invoke(aff, (float) scale, (float) scale);
                return aff;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /**
     * 与掩码矩阵 <b>同参数</b>的合成矩阵：两侧用完全相同的字族/字号/缩放，逐行对照。
     *
     * <p>为何这条能定根因：先前「掩码正常」的结论是在 **Segoe UI / 12px / 1×** 下得到的，
     * 而合成异常是在 **System / 14px / 1.5×** 下看到的。把两侧参数对齐后：
     *
     * <ul>
     *   <li>若某一参数下 **掩码 r/g/b 不等、合成三通道却相等** ⇒ 压平发生在**掩码之后**
     *       （图集上传 / 纹理格式 / 着色器取样），**与 DWrite 无关**；</li>
     *   <li>若某参数下 **掩码本身就已经 R=G=B=100%** ⇒ 退化在**取掩码**，指向传给
     *       DWrite 的实际输入（变换矩阵 / 像素密度 / 渲染模式）。</li>
     * </ul>
     *
     * <p>两侧同一行分开对比即可，不需要再靠截图或肉眼。
     */
    private static void logLcdCompositeMatrix() {
        for (float size : new float[]{12f, 14f, 21f}) {
            for (double scale : new double[]{1.0, 1.5}) {
                String tag = String.format("合成矩阵[Segoe UI size=%.0f scale=%.2f]", size, scale);
                try {
                    Text text = new Text("MW#8");
                    text.setFont(Font.font("Segoe UI", size));
                    text.setFontSmoothingType(FontSmoothingType.LCD);
                    text.setFill(Color.BLACK);
                    new Scene(new Group(text));
                    long[] ch = channelSums(snapshot(text, Color.WHITE, scale));
                    line(String.format("%s 墨量(r,g,b)=%d/%d/%d 三通道相等=%s",
                            tag, ch[0], ch[1], ch[2],
                            (ch[0] == ch[1] && ch[1] == ch[2]) ? "是（已压平）" : "否（每通道覆盖仍在）"));
                } catch (Throwable e) {
                    line(tag + " 不可用(" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
                }
            }
        }
    }

    /**
     * 一段样例字形的分通道统计：r/g/b 各自合计、`R=G=B` 像素占比、像素数、掩码最大尺寸。
     *
     * <p>判据：若在某个「字号 × 缩放」下 `R=G=B 占比` 突升到 100%，则说明
     * **DWrite 在该参数下给出的就是单值（灰度）掩码**，缺陷在“取掩码”而非“合成”；
     * 若各参数下都与 JVM 相同，则缺陷确实在合成环节。
     *
     * <p>另外把 `max合计`、`均值合计` 也打出来：它们与 `r/g/b合计` 一起构成**候选标量集合**。
     * 黑字白底时 gamma 会相互抵消 ⇒ 正确合成的每通道墨量应当**等于掩码对应通道的和**；
     * 因此把「合成矩阵」里那个相等的单值去跟这 5 个候选对号，就能直接**说出到底是哪种算术**
     * （取了某一通道 / 取 max / 取均值），而不只是“哪一段”。
     */
    private static String maskChannelStats(Object strike) {
        try {
            Class<?> strikeCls = Class.forName("com.sun.javafx.font.FontStrike");
            Class<?> glyphCls = Class.forName("com.sun.javafx.font.Glyph");
            var getGlyph = strikeCls.getMethod("getGlyph", char.class);
            var getPixelData = glyphCls.getMethod("getPixelData", int.class);
            var isLcdGlyph = glyphCls.getMethod("isLCDGlyph");
            var getWidth = glyphCls.getMethod("getWidth");
            var getHeight = glyphCls.getMethod("getHeight");
            long sr = 0;
            long sg = 0;
            long sb = 0;
            long smax = 0;
            double smean = 0;
            long same = 0;
            long pixels = 0;
            int maxWidth = 0;
            int maxHeight = 0;
            boolean lcd = false;
            for (char ch : "MW#8".toCharArray()) {
                Object glyph = getGlyph.invoke(strike, ch);
                if (glyph == null) {
                    continue;
                }
                lcd = (boolean) isLcdGlyph.invoke(glyph);
                byte[] mask = (byte[]) getPixelData.invoke(glyph, 0);
                if (mask == null) {
                    continue;
                }
                int bpp = lcd ? 3 : 1;
                int count = mask.length / bpp;
                for (int i = 0; i < count; i++) {
                    int r = mask[i * bpp] & 0xFF;
                    int g = bpp == 3 ? mask[i * bpp + 1] & 0xFF : r;
                    int b = bpp == 3 ? mask[i * bpp + 2] & 0xFF : r;
                    sr += r;
                    sg += g;
                    sb += b;
                    smax += Math.max(r, Math.max(g, b));
                    smean += (r + g + b) / 3.0;
                    if (r == g && g == b) {
                        same++;
                    }
                    pixels++;
                }
                maxWidth = Math.max(maxWidth, (int) getWidth.invoke(glyph));
                maxHeight = Math.max(maxHeight, (int) getHeight.invoke(glyph));
            }
            return String.format("isLCD=%s 像素=%d r/g/b合计=%d/%d/%d max合计=%d 均值合计=%.0f "
                            + "R=G=B占比=%.1f%% 掩码最大=%dx%d",
                    lcd, pixels, sr, sg, sb, smax, smean,
                    pixels == 0 ? 0.0 : 100.0 * same / pixels, maxWidth, maxHeight);
        } catch (Throwable t) {
            return "不可用(" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
        }
    }

    /**
     * 在给定缩放倍率下渲染样例文字，统计「墨量合计」（alpha 总和）与「次像素着色像素数」。
     *
     * <p>为什么需要它：上面那些开关全相同也不能证明**栅格化结果相同** ——
     * 而「发虚 / 笔画偏细」的本质就是**墨量变少**。有了这个数字，两种模式可直接比大小，
     * 不再靠肉眼。若打印「墨量=0」，则是快照失败（不是字体问题），见下方注释。
     */
    private static void logGlyphPixelsAt(double scaleX, double scaleY) {
        try {
            Text text = new Text(SAMPLE);
            text.setFont(Font.font(14));
            text.setFill(Color.BLACK);
            // 快照要求节点在场景图内：挂在临时 Scene 上，但不入 Stage（不上屏）
            new Scene(new Group(text));
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            params.setTransform(new Scale(scaleX, scaleY));
            Image image = text.snapshot(params, null);
            PixelReader reader = image.getPixelReader();
            int w = (int) image.getWidth();
            int h = (int) image.getHeight();
            long ink = 0;
            long nonEmpty = 0;
            long colored = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    Color c = reader.getColor(x, y);
                    int alpha = (int) Math.round(c.getOpacity() * 255);
                    ink += alpha;
                    if (alpha > 0) {
                        nonEmpty++;
                    }
                    if (Math.abs(c.getRed() - c.getGreen()) > 0.01
                            || Math.abs(c.getGreen() - c.getBlue()) > 0.01) {
                        colored++;
                    }
                }
            }
            line(String.format("字形像素[scale=%.2f] %dx%d 墨量合计=%d 非空像素=%d 次像素着色像素=%d",
                    scaleX, w, h, ink, nonEmpty, colored));
        } catch (Throwable e) {
            line(String.format("字形像素[scale=%.2f] 不可用(%s)", scaleX, e.getClass().getSimpleName()));
        }
    }

    /**
     * 调 {@code PrismFontFactory} 的**实例**方法。
     *
     * <p>注意：{@code isLCDTextSupported()} / {@code getSubPixelMode()} 是实例方法，
     * 不能用 {@code Method.invoke(null)} 调 —— 2026-10-10 就因此两种模式都只打出
     * 「不可用(NullPointerException)」，白跑一轮。这里统一经**基类**（已登记的
     * {@code PrismFontFactory}）取方法，再以实例为接收者调用。
     */
    private static Object invokeOnFactory(String method) {
        try {
            Class<?> base = Class.forName("com.sun.javafx.font.PrismFontFactory");
            Object factory = base.getMethod("getFontFactory").invoke(null);
            return base.getMethod(method).invoke(factory);
        } catch (Throwable e) {
            return "不可用(" + e.getClass().getSimpleName() + ")";
        }
    }

    private static String prop(String key) {
        String value = System.getProperty(key);
        return value == null ? "<默认>" : value;
    }

    private static Object invoke(String method) {
        try {
            Class<?> factory = Class.forName("com.sun.javafx.font.PrismFontFactory");
            Method m = factory.getMethod(method);
            return m.invoke(null);
        } catch (Throwable e) {
            return "不可用(" + e.getClass().getSimpleName() + ")";
        }
    }

    private static void line(String message) {
        System.out.println("渲染诊断：" + message);
    }
}
