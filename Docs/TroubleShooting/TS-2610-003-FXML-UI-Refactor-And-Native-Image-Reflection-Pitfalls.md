# TS-2610-003 · FXML 界面重构后原生包启动即崩：反射登记与资源排除的两层踩坑（复盘与预防）

| 项目 | 内容 |
| --- | --- |
| 编号 | TS-2610-003 |
| 标题 | FXML 界面重构后**原生包启动即崩**：FXML 反射登记（按声明类）与**资源排除**（Trampoline.class）的两层踩坑 |
| 类型 | 原生镜像元数据缺陷（反射登记不完整 + 资源规则过宽）+ **自证链路缺口**（负面断言看不见缺陷）+ 界面重构引入 |
| 首次发现 | 2026-10-12（冒烟改为**正面断言**后，第一次运行 CI 即命中；此前多轮构建全绿却完全没报） |
| 影响范围 | 原生 exe 的启动路径（`FXMLLoader` 装载 `FanControlView.fxml`）；**JVM 模式不受影响**（同一份代码在 JVM 下始终正常） |
| 当前状态 | **已修复 + 已机器强制**（冒烟正面断言、exe 字节自证、资源可见性诊断标记）；真机手工双击验收待现场记录 |
| 修复提交 | PR #15「冒烟正面断言 FXML 已加载」、PR #16「补 FXML 继承成员的反射登记 + 排除 Trampoline 资源」；界面重构本身见 PR #14 |
| 相关文档 | `Docs/NativeImageCi.md` 第 5 节第 20、21 条；`Script/GenerateNativeImageMetadata.py`；`src/main/resources/META-INF/native-image/{reflect,resource}-config.json`；`.github/workflows/main.BuildReleaseWindowsX64.yml` |

---

## 1. 现象

三个 PR 是一条因果链，缺一处都看不到全貌：

| PR | 角色 | 结果 |
| --- | --- | --- |
| #14 | **引入形态**：界面从 Java 代码迁到 `FanControlView.fxml` + `.css`，新增控制器 `FanControlController` 与业务层 `FanControlService` | JVM 模式完全正常；**原生包从此启动即崩**，但构建全绿 ⇒ 无人察觉 |
| #15 | **把缺陷变可见**：冒烟从「不得出现某标记」（负面）改成「必须命中某标记」（正面），并把标记改成纯 ASCII | **第一轮 CI 就抓到**原生包启动即崩 —— 在此之前它已经连续多轮"全绿"通过 |
| #16 | **修到底**：补按声明类的反射登记 + **排除被当作资源编进镜像的 `Trampoline.class`** | 原生包恢复可启动；CI 全绿且冒烟正面命中 |

### 1.1 直接现象

| 项 | 内容 |
| --- | --- |
| 触发方式 | 双击原生 `LegionFanControl.exe`（或 CI 冒烟运行它） |
| 表现 | **窗口不出现**，进程很快退出；控制台（`--console`）里打出「界面加载失败」一行 |
| 对照 | 同一份代码 `java -jar LegionFanControl.jar` 一切正常 ⇒ 差异只在 native-image 一侧 |
| 关键性质 | 构建阶段**没有任何报错**：`native-image` 成功、exe 成功产出、自证项全绿 |

### 1.2 依次暴露的报错（原文）

按修复推进的顺序，共 5 条。它们都出现在同一条路径（FXML 装载）上，但**分属两类完全不同的原因**：

```text
① AssertionError: ClassNotFoundException: javafx.fxml.FXMLLoader
   （FXMLLoader.<clinit> 内部 Utils.forceInit("javafx.fxml.FXMLLoader") —— 按名字加载，被封闭世界分析裁掉）

② MissingReflectionRegistrationError: Cannot reflectively invoke method '… javafx.scene.Node.getId()'
   （FXML 注入 / 属性设置反射调用的成员声明在**祖先类**上，只登记控件类不够）

③ UnsupportedFeatureError: Classes cannot be defined at runtime by default when using ahead-of-time
   Native Image compilation. Tried to define class: com.sun.javafx.reflect.Trampoline
   （JavaFX 运行期要"自己造"一个类；native-image 默认禁止运行期定义类）

④ MissingReflectionRegistrationError: Cannot reflectively invoke method
     'public javafx.collections.ObservableList javafx.scene.layout.Pane.getChildren()'
   MissingReflectionRegistrationError: Cannot reflectively invoke method
     'public final void javafx.scene.control.ButtonBase.setOnAction(javafx.event.EventHandler)'
   （同 ②，属"按声明类登记"这条规律的另两处表现）

⑤ javax.xml.stream.FactoryConfigurationError: Provider com.sun.xml.internal.stream.XMLInputFactoryImpl not found
   （FXMLLoader 解析 fxml 走 StAX，其 JDK 内部实现同样要登记才进镜像）
```

> ③ 的栈是判断"谁在运行期定义类"的关键证据（摘录）：
> `MethodUtil.getTrampolineClass(217)` → `Class.forName` → `ClassRegistries.forName(233)` → `resolve` →
> `resolveInstanceType` → `AOTClassRegistry.loadClass(51)` → `ClassLoader.loadClass(490)` →
> `MethodUtil.loadClass(157)` → `findClass(185)` → `defineClass(204)` → `SecureClassLoader.defineClass` →
> `RuntimeClassLoading.defineClass(178)` → `throwNoBytecodeClasses(197)`。

### 1.3 为什么"以前看不见"

| 项 | 说明 |
| --- | --- |
| 旧冒烟判据 | 「输出里**不得**含『界面加载失败』」——这是**负面断言** |
| 逻辑漏洞 | 程序若在加载视图**之前**就崩（如 ① 的 `AssertionError`），同样不会打印「界面加载失败」⇒ 断言**通过** |
| 另一层漏洞 | 判据是中文字符串，而冒烟以 **GBK** 运行（模拟中文 Windows），日志里的中文可能是乱码 ⇒ 匹配本身也不可靠 |
| 结论 | 负面断言只能证明"没看到坏现象"，**不能证明"程序真的走到了好状态"** ⇒ 必须改成正面断言 |

---

## 2. 影响范围

### 2.1 受影响文件

| 文件 | 定位 | 是否会随发布包分发 |
| --- | --- | --- |
| `src/main/resources/META-INF/native-image/reflect-config.json` | 反射登记表（489 条；FXML 相关的 21 类为 `allDeclared*`） | **会** —— 打进 jar 后由 native-image 自动读取，直接决定镜像内容 |
| `src/main/resources/META-INF/native-image/resource-config.json` | 资源收录规则（含 `excludes`） | **会** —— 同上；**本档案的结构性根因就在这里** |
| `Script/GenerateNativeImageMetadata.py` | 元数据生成器（`FXML_UI_CLASSES` / `CONTROLLER_CLASS` / 类族完整性校验） | 否（构建期脚本） |
| `.github/workflows/main.BuildReleaseWindowsX64.yml` | 冒烟断言、`CheckExeFxmlView` 等自证 | 否（CI） |
| `FanControlController.java` / `FanControlApp.java` | 控制器（`@FXML` 成员为 `public`）、诊断标记 | **会**（进 jar / 进 exe） |

### 2.2 受影响场景

| 场景 | 影响 |
| --- | --- |
| 原生包（`-native-windows-x64.zip` 里的 exe） | **致命** —— 启动即崩，用户拿到的包完全不能用 |
| JVM 包（`java -jar` / `Bootstrap.cmd`） | 无影响（同代码在 JVM 下正常） |
| 构建流水线 | 无报错、自证项全绿 ⇒ **缺陷不可见**（这才是最危险的部分） |

### 2.3 与上一份档案的关系

| 对比 | TS-2610-001（LCD 文本渲染退化） | 本档案（TS-2610-003） |
| --- | --- | --- |
| 性质 | 渲染**质量**退化（能跑，但字发虚） | 功能**完全不可用**（启动即崩） |
| 归属 | 主责在上游 native-image 侧（黑盒边界） | **本仓库配置缺陷**（元数据不完整 / 资源规则过宽） |
| 可观测性 | 需要取样比对 | 一次冒烟即可判定（改对断言之后） |

---

## 3. 结论分层

| 层次 | 结论 | 依据 |
| --- | --- | --- |
| **已观测事实** | ① 原生包启动时依次出现第 1.2 节的 5 条报错，JVM 模式全部正常；② 冒烟改正面断言后**第一轮**就抓到；③ `resource-config.json` 的 `excludes` 生效后崩溃消失；④ 本机最小复现实验证明「登记 `.class` 资源 ⇒ 该 `.class` 在镜像里作为**资源**可见」 | 第 4 节 |
| **机制 / 行为模型（结构性根因）** | `resource-config.json` 的宽匹配 `com/sun/javafx/.*` 把 `com/sun/javafx/reflect/Trampoline.class` 也当**资源**编进镜像 ⇒ JavaFX 的 `MethodUtil`（一个私有 `SecureClassLoader`）用 `getResourceAsStream` 读到字节 ⇒ 去**运行期 `defineClass`** ⇒ native-image 默认禁止 ⇒ 崩。排除该资源后，`MethodUtil.findClass` 抛 `ClassNotFoundException`，它自己的 `loadClass` 吞掉异常并**回落到父加载器** ⇒ 从镜像里取到该类（反射表已登记）⇒ 完全不再触发运行期定义 | 第 4.3、4.4 节；JavaFX `MethodUtil` / `MethodHelper` 源码；本机最小复现 |
| **机制 / 行为模型（登记层规律）** | FXML 反射访问成员时，按**声明类**解析：`allDeclaredMethods` 只覆盖**本类声明**的成员，**父类条目不会被子类条目覆盖** ⇒ 每个属性的声明类都必须单独 `allDeclared*`；此外**形参类型 / 返回值类型**（`EventHandler`、`ObservableList`）也要在登记表里 | 第 4.1、6 节；三次实测（`Node.getId` / `Pane.getChildren` / `ButtonBase.setOnAction`） |
| **机制 / 行为模型（按名字加载）** | 凡「按名字加载」的类（`Utils.forceInit("…")`、`Class.forName`、`FactoryFinder`），封闭世界分析都看不到 ⇒ 必须显式登记（登记即强制包含），光靠「代码里直接引用」盖不住 | ① 与 ⑤ 两条报错 |
| **未定位 / 未复现** | ① 真机手工双击的现场记录（用户侧）；② 跨 JDK / JavaFX 版本是否走**同一条** `MethodUtil` 路径（只在 JDK 25 + JavaFX 27 上验证过）；③ 命令行 `-H:ExcludeResources` 在 CI 上"未生效"的确切原因（见 Q5） | 第 10 节 |

---

## 4. 观测数据

### 4.1 修复后 CI 的正面证据（PR #16，run `38230303038`）

```text
构建产物: target/LegionFanControl.exe，大小 103.1 MB
冒烟通过：[ui-fxml-loaded] 命中，且无字符集 / 类初始化 / 视图加载失败 ✅
```

| 自证 / 检查项 | 结果 |
| --- | --- |
| 冒烟（正面断言 `[ui-fxml-loaded]`） | success ✅ |
| `CheckExeFxmlView`（exe 字节里找 `fx:controller="…FanControlController"` 与 `.fan-grid`） | success ✅ |
| `CheckExeIcon` / `CheckExeSubsystem` / `CheckExeManifest` / `CheckScriptLineEndings` | success ✅ |
| 自证③（可达性元数据完整性，含 `FanControlController`、无失效类名） | success ✅ |

### 4.2 体积与耗时对照（同一份代码、只改元数据 / 参数）

| 形态 | exe 大小 | native-image 耗时 | 结论 |
| --- | --- | --- | --- |
| 修复前（#14 的产物） | 102.2 MB | 与修复后持平 | **构建全绿但运行即崩** |
| 修复后（#16） | **103.1 MB** | **4m56s** | +0.9 MB 换回可启动 ✅ |
| 被证伪的 open-world 路线（`-H:+RuntimeClassLoading`） | 156.9 MB | 6m28s | 崩溃，已撤回（第 4.4 节） |

### 4.3 本机最小复现实验（把"资源登记是唯一来源"钉死）

环境：GraalVM CE 25.0.2 + 系统 `gcc`，实验目录 `/tmp/exp`，单个类 `R`，约 50 s / 轮。

| 实验 | 配置 | 结果 |
| --- | --- | --- |
| 不做任何资源登记 | 无 `resource-config.json` | `.class` 与普通文件**都不可见** ⇒ **不会被自动当资源** |
| 只做反射登记（不含资源规则） | `reflect-config.json` 登记 `R` | `.class` **仍不可见** ⇒ 反射登记**不会**把 `.class` 带成资源 |
| 登记该 `.class` 为资源 | `resource-config.json` 登记 `R\.class` | **可见** ⇒ 资源登记是本问题的**唯一来源** |
| 命令行排除 | `-H:+UnlockExperimentalVMOptions` + `-H:ExcludeResources=R.class` | 可排除（实验参数，顺序敏感） |
| JSON 排除 | `resources.excludes` 里 `R\.class` | 可排除 ⇒ **本修复采用** |

> 这张表的价值：它把「宽匹配资源规则会顺带把 `.class` 编进镜像」变成一条**可复现的规律**，
> 而不是只在本项目里成立的巧合。

### 4.4 三条被证伪路线（留证据，避免重走）

| 路线 | 做法 | 结局 | 关键证据 |
| --- | --- | --- | --- |
| 把成员改成 `public` | 认为「非 public 成员才触发 Trampoline」 | **无效**（崩溃点不变） | 最终由「排除资源」解决 ⇒ `public` 只是少一条动态生成路径，**不是**根因 |
| 放行运行期定义类 | `-H:+RuntimeClassLoading` | **崩溃**（更糟） | `Fatal error: Unable to call AOT method: InterpreterResolvedJavaMethod<holder=Ljava/lang/ClassLoader; name=getPlatformClassLoader descriptor=()Ljava/lang/ClassLoader; dispatchIndex=-1>` + `com.oracle.svm.interpreter.InterpreterUtil.shouldNotReachHere` → `EXCEPTION_ACCESS_VIOLATION`；exe 156.9 MB |
| tracing agent 预定义类 | `predefined-classes-config.json` + `.classdata`（agent 采集） | **无效** | 构建日志明说：`Skipped 1 predefined class(es) because the classpath already contains a class with the same name: com.sun.javafx.reflect.Trampoline` ⇒ 对「classpath 上已存在的类」这条路走不通；相关链路（4 个文件）已删除 |

另外两条**配置层面**的坑：

| 坑 | 证据 |
| --- | --- |
| 命令行 `-H:ExcludeResources=…` 在 **CI 未生效** | 本机可排除、CI 上同一配置无效 ⇒ 改用 JSON `excludes`（不经 shell，无转义链路） |
| `-H:ExcludeResources` 属 **experimental** | 必须让 `-H:+UnlockExperimentalVMOptions` 出现在它**之前**（GraalVM 按参数顺序判定是否已解锁） |

### 4.5 修复后的登记现状（`6fd12dd`）

```text
reflect-config.json 条目总数: 489
FXML 相关 21 个类全部为 4/4 allDeclared*（构造器 / 方法 / 字段 / 内部类）：
  io.github.acnxo.legion.fan.FanControlController
  javafx.fxml.FXMLLoader、javafx.fxml.FXML
  javafx.scene.Node、javafx.scene.Parent
  javafx.scene.layout.Region、javafx.scene.layout.Pane、javafx.scene.layout.Priority
  javafx.scene.control.Control、javafx.scene.control.Labeled、javafx.scene.control.ButtonBase
  javafx.scene.control.Button、javafx.scene.control.Label
  javafx.scene.layout.GridPane、javafx.scene.layout.HBox、javafx.scene.layout.ColumnConstraints
  javafx.geometry.Pos、javafx.collections.ObservableList、javafx.event.EventHandler
  com.sun.javafx.reflect.Trampoline、com.sun.xml.internal.stream.XMLInputFactoryImpl
```

---

## 5. 定位过程

1. **起点**：PR #14 合并后，JVM 模式正常、原生包"双击没反应"。第一判断是 **native-image 侧差异**，不是代码 bug（同一份代码在 JVM 下正常）。
2. **让缺陷可见**（PR #15）：把冒烟从「不得含『界面加载失败』」改为「必须命中 `[ui-fxml-loaded]`」，并把两个标记都改成**纯 ASCII**（规避 GBK 乱码）；同时把 `FanControlApp` 的 `catch` 范围扩到 `Error`（`AssertionError` 不是 `Exception`，否则连失败标记都打不出来），并在打印后 `flush`（重定向到文件时 `System.out` 不自动 flush，标记会丢）。
3. **抓到 ①**：CI 立刻报 `ClassNotFoundException: javafx.fxml.FXMLLoader` ⇒ 按名字加载（`Utils.forceInit`）⇒ 把它与 `javafx.fxml.FXML` 加入登记表。
4. **抓到 ②**：接着报 `Node.getId()` ⇒ 意识到「成员声明在祖先类上」，把 `Node` / `Parent` / `Region` / `Control` / `Labeled` **就地升级**为 `allDeclared*`（原先它们只有 `name`，是"存在但不完整"的条目）。
5. **抓到 ⑤**：`FactoryConfigurationError: Provider … not found` ⇒ FXMLLoader 解析 fxml 走 StAX（`FactoryFinder` 按名字找 JDK 内部实现）⇒ 登记 `com.sun.xml.internal.stream.XMLInputFactoryImpl`。
6. **抓到 ③（最难的一层）**：`UnsupportedFeatureError … Tried to define class: com.sun.javafx.reflect.Trampoline`。先读 JavaFX 源码确认谁在定义：`com.sun.javafx.property.MethodHelper` → `MethodUtil`（`extends SecureClassLoader`，私有无参构造）→ `getTrampolineClass()` 走 `Class.forName(TRAMPOLINE, true, new MethodUtil())` → `findClass` 读 `MethodUtil.class.getModule().getResourceAsStream("com/sun/javafx/reflect/Trampoline.class")`。
7. **证伪两条"大力出奇迹"的路线**（第 4.4 节）：`-H:+RuntimeClassLoading` 直接把 AOT 解释器打崩；tracing agent 预定义类被构建器跳过（classpath 上已有同名类）。
8. **本机最小复现**（第 4.3 节）：用最小工程把"资源登记 ⇒ `.class` 可见"钉死，确认**资源登记是本问题的唯一来源**，并确认 JSON `excludes` 可排除。
9. **修复**：`resource-config.json` 加 `excludes: com/sun/javafx/reflect/Trampoline\.class` ⇒ 资源不可见 ⇒ `findClass` 抛 `ClassNotFoundException` ⇒ `loadClass` 吞掉异常、回落父加载器 ⇒ 从镜像里取到已登记的类。
10. **补齐同一规律下的其余成员**：`Pane.getChildren()`、`ButtonBase.setOnAction(javafx.event.EventHandler)`（④），并登记形参 / 返回值类型（`EventHandler`、`ObservableList`）。
11. **加诊断标记**：`FanControlApp.logTrampolineDiagnostics()` 打印 `[diag-trampoline-resource] null|visible|error(…)`（期望 `null`）⇒ 让"排除是否生效"**每轮都可测量**，不必再靠猜。

---

## 6. 排除项

| 排除项 | 依据 |
| --- | --- |
| 不是 JavaFX 版本 / 依赖不匹配 | 同版本 JVM 模式正常；`javafx.version` 一致 |
| 不是「代码里引用了就不会被裁」 | ① 证明按名字加载（`forceInit`）的类照样会被裁 |
| 不是「FXML / CSS 没进镜像」 | `CheckExeFxmlView` 在 exe 字节里命中 `fx:controller=…` 与 `.fan-grid` ⇒ 资源确实在镜像里 |
| 不是「把成员改成 public 就能解决」 | 改 public 后崩溃点不变（第 4.4 节） |
| 不是「放行运行期定义类」 | `-H:+RuntimeClassLoading` ⇒ AOT 解释器 `EXCEPTION_ACCESS_VIOLATION`，产物更大更慢 |
| 不是「预定义类数据」路线 | 构建日志 `Skipped 1 predefined class(es) … same name` |
| 不是「命令行 `-H:ExcludeResources` 写错」 | 同一参数在本机可排除、在 CI 无效 ⇒ 属参数传递链路问题，不是语法问题（改 JSON 后解决） |
| 不是冒烟脚本自身坏了 | 它**正确地**在旧断言下放行了崩溃 ⇒ 问题在**断言的逻辑**（负面 → 正面），不在执行环境 |
| 不是 `Error` 之外还有别的异常类型漏捕 | 捕获范围已含 `Error`，且失败标记与堆栈都实测打出 |

---

## 7. 修复

### 7.1 资源侧（**结构性根因**，最关键）

`src/main/resources/META-INF/native-image/resource-config.json`：

```json
{
  "resources": {
    "includes": [ "…原有规则不变…" ],
    "excludes": [
      { "pattern": "com/sun/javafx/reflect/Trampoline\\.class" }
    ]
  }
}
```

| 问题 | 说明 |
| --- | --- |
| 为什么这么改 | 让该 `.class` **不作为资源**出现在镜像里 ⇒ JavaFX 的私有加载器读不到字节 ⇒ 不再走运行期 `defineClass`；转而回落到父加载器，从镜像里取到**已登记的类** |
| 为什么不删掉宽的 `com/sun/javafx/.*` | 该规则还负责其它 JavaFX 内部资源（缺了会引发别的运行期失败）；只**精确排除**这一个路径，风险最小 |
| 为什么不写命令行 | 命令行方式在 CI 实测未生效（第 4.4 节）；JSON 不经 shell、无转义问题 |
| 回退方式 | 删掉 `excludes` 条目即回到修复前行为（会崩），改动只影响 native-image profile，**不影响 jar 打包** |

### 7.2 反射侧（**按声明类登记**）

| 类别 | 登记对象 | 理由 |
| --- | --- | --- |
| 控制器 | `io.github.acnxo.legion.fan.FanControlController` | `fx:controller` 反射注入 `@FXML` 字段、反射调用事件方法 |
| FXML 机制本身 | `javafx.fxml.FXMLLoader`、`javafx.fxml.FXML` | 按名字加载（`Utils.forceInit`），不登记即被裁 |
| 场景图祖先 | `javafx.scene.Node`、`Parent`、`javafx.scene.layout.Region`、`Pane`、`javafx.scene.control.Control`、`Labeled`、`ButtonBase` | 属性 / 方法的**声明类**（`getId`→`Node`、`getChildren`→`Pane`、`setOnAction`→`ButtonBase`…） |
| FXML 里的具体控件 / 布局 | `Label`、`Button`、`GridPane`、`HBox`、`ColumnConstraints` | FXMLLoader 按类名实例化 |
| 形参 / 返回值类型 | `javafx.event.EventHandler`、`javafx.collections.ObservableList` | 反射解析方法签名时也要能拿到这些类型 |
| 枚举（属性值） | `javafx.geometry.Pos`、`javafx.scene.layout.Priority` | `alignment="CENTER"` → `Pos.valueOf`，`hgrow="ALWAYS"` → `Priority.valueOf` |
| JDK 内部实现 | `com.sun.xml.internal.stream.XMLInputFactoryImpl` | FXMLLoader 解析 fxml 走 StAX 的 `FactoryFinder`（按名字找） |
| JavaFX 反射工具 | `com.sun.javafx.reflect.Trampoline` | 「回落父加载器」能否成功的前提：父加载器得能从镜像里取到它 |

> 生成器 `Script/GenerateNativeImageMetadata.py` 里，这一族由 `FXML_UI_CLASSES` / `CONTROLLER_CLASS` 统一维护，
> **FXML 里新增任何控件 / 布局类都必须同步加进去**；生成器还会对「按名字加载」的类族做**完整性校验**（未 100% 在册即报错）。

### 7.3 代码侧（可观测性）

| 文件 | 改动 | 目的 |
| --- | --- | --- |
| `FanControlController` | `@FXML` 字段与事件方法声明为 **`public`**；`initialize()` 打印 `[ui-fxml-loaded]` + `flush()` | 减少一层动态生成路径；给出**正面断言**的落点 |
| `FanControlApp` | `catch (IOException \| RuntimeException \| Error)`；打印 `[ui-fxml-load-failed]` + 堆栈 + `flush()`；新增 `logTrampolineDiagnostics()` | 失败路径也能被冒烟看见；让资源排除是否生效**可测量** |

### 7.4 CI 侧（自证与断言）

| 项 | 内容 |
| --- | --- |
| 冒烟（改正面断言） | 必须命中 `[ui-fxml-loaded]`，且不得出现 `[ui-fxml-load-failed]`（两个标记都是纯 ASCII） |
| `CheckExeFxmlView`（新增） | 在 **exe 字节**里找 `fx:controller="…FanControlController"` 与 `.fan-grid` ⇒ 证明 fxml / css 真的进了镜像（"构建成功" ≠ "资源进了镜像"） |
| 自证③（扩展） | 可达性元数据里必须有 `FanControlController` |
| 参数顺序修正 | `-H:+UnlockExperimentalVMOptions` 前移到 `<buildArgs>` 最前 |

### 7.5 明确删除的失败尝试资产

| 资产 | 原因 |
| --- | --- |
| `src/main/resources/META-INF/native-image/predefined-classes-config.json` | 预定义类路线被构建器跳过（同名类已在 classpath） |
| `.../agent-extracted-predefined-classes/*.classdata` | 同上 |
| `.github/workflows/CollectPredefinedClasses.yml` | 采集作业随路线一起废弃 |
| `Script/FxmlLoadProbe.java` | 采集探针随之废弃 |
| `-H:+RuntimeClassLoading`（pom 参数） | 打崩 AOT 解释器 |

---

## 8. 验证

### 8.1 本地已验证

| 项 | 证据 |
| --- | --- |
| 「资源登记 ⇒ `.class` 可见」的规律 | 第 4.3 节最小复现实验（5 组对照） |
| JSON `excludes` 可排除 | 第 4.3 节 |
| 生成器 / 登记表自洽 | `reflect-config.json` 489 条；FXML 相关 21 类 `4/4 allDeclared*` |
| 诊断标记期望值 | `[diag-trampoline-resource] null` |

### 8.2 CI 已验证

| 项 | 证据 |
| --- | --- |
| 原生包可启动（冒烟真跑） | `冒烟通过：[ui-fxml-loaded] 命中，且无字符集 / 类初始化 / 视图加载失败 ✅` |
| fxml / css 真在镜像里 | `CheckExeFxmlView` success |
| exe 形态 | 103.1 MB（修复前 102.2 MB） |
| 构建耗时未退化 | native-image 4m56s（与修复前持平） |

### 8.3 真机（待确认）

| 项 | 期望 |
| --- | --- |
| 双击 `LegionFanControl.exe` | 界面正常出现，无控制台窗口 |
| `LegionFanControl.exe --console` | 出现控制台；日志中有 `[ui-fxml-loaded]`、`[diag-trampoline-resource] null` |
| 未提权运行 | 红字提示 + 按钮显示「需管理员权限」 |

---

## 9. 预防

### 9.1 已经落地的机器强制（优先于人工复查）

| 机制 | 位置 | 挡住什么 |
| --- | --- | --- |
| 冒烟**正面断言** | 工作流 `SmokeTestCharsets` | "程序根本没跑到好状态"（负面断言的盲区） |
| **纯 ASCII 标记** | `[ui-fxml-loaded]` / `[ui-fxml-load-failed]` / `[diag-trampoline-resource]` | GBK 冒烟下中文日志乱码导致判定失效 |
| exe 字节自证 | `CheckExeFxmlView` | "构建成功但资源没进镜像" |
| 可达性元数据完整性 | 自证③（含类族 100% 在册校验） | 运行期才拓类（`ClassNotFoundException`） |
| 资源可见性诊断 | `[diag-trampoline-resource]`（期望 `null`） | 日后又加宽匹配资源规则导致回归 |

### 9.2 改这个项目时必须记住的红线

| 红线 | 说明 |
| --- | --- |
| **宽匹配资源必须配精确排除** | `com/sun/javafx/.*` 这类规则会把 `.class` 也当资源编进镜像，**改变框架自身的类加载路径** ⇒ 触发运行期 `defineClass` |
| **反射登记的单元是"声明类"** | 子类登记**不会**覆盖父类成员；属性 / 方法的声明类必须各自 `allDeclared*` |
| **按名字加载的类必须显式登记** | `Utils.forceInit`、`Class.forName`、`ServiceLoader` / `FactoryFinder` 都走名字路径，封闭世界分析看不到 |
| **形参 / 返回值类型也要登记** | 反射解析方法签名时会用到（`EventHandler`、`ObservableList`） |
| **FXML 新增控件要同步登记** | 加进生成器的 `FXML_UI_CLASSES`；否则"构建全绿、启动即崩" |
| **实验性 native-image 参数顺序敏感** | `-H:+UnlockExperimentalVMOptions` 必须在实验参数**之前** |
| **别用「成员改 public」安慰自己** | 它不解决根因（第 4.4 节） |

### 9.3 建议（尚未落地，见第 10 节）

| 建议 | 目的 |
| --- | --- |
| 冒烟再加一条硬断言：`[diag-trampoline-resource]` 必须为 `null` | 万一日后又加宽匹配资源规则，直接失败而不是"崩溃等真机发现" |
| 把「宽匹配资源 + `.class`」写进 `Docs/DevelopmentConventions.md` | 让红线从"踩坑档案"升级为"改之前必读的约定" |

---

## 10. 未决问题

| 编号 | 问题 | 现状 |
| --- | --- | --- |
| Q1 | 真机手工双击的现场记录（无窗口 / 有界面、日志内容） | 待用户侧补全；CI 冒烟已证明 exe 可启动 |
| Q2 | 是否把 `[diag-trampoline-resource] null` 升级为冒烟硬断言 | 建议中（第 9.3 节），未实施 |
| Q3 | 「宽匹配资源 + `.class`」是否进 `Docs/DevelopmentConventions.md` | 建议中，未实施 |
| Q4 | 跨 JDK / JavaFX 版本是否走同一条 `MethodUtil` 路径 | 未跨版本验证（本项目固定在 JDK 25 + JavaFX 27） |
| Q5 | 命令行 `-H:ExcludeResources` 在 CI 未生效的确切环节（转义 / 参数解析顺序 / 版本差异） | 已用 JSON `excludes` 绕过，根因未定位 |
| Q6 | 其余「按名字加载」的类族是否会随 JDK / JavaFX 升级新增 | 由生成器的类族完整性校验兜底，但**新族本身**需要人工补充 |

---

## 11. 相关文档

| 文档 / 文件 | 关系 |
| --- | --- |
| `Docs/NativeImageCi.md` 第 5 节第 20 条 | FXML 界面与业务分离带来的元数据要求（资源规则、控制器与控件登记、自证项） |
| `Docs/NativeImageCi.md` 第 5 节第 21 条 | `Trampoline` 被当资源编进镜像的机制、三条被证伪路线、JSON `excludes` 修法 |
| `Script/GenerateNativeImageMetadata.py` | `FXML_UI_CLASSES` / `CONTROLLER_CLASS` 的定义处；类族完整性校验 |
| `src/main/resources/META-INF/native-image/reflect-config.json` | 修复后的登记表（489 条，FXML 相关 21 类为 `allDeclared*`） |
| `src/main/resources/META-INF/native-image/resource-config.json` | 结构性根因所在：宽匹配 + 精确 `excludes` |
| `.github/workflows/main.BuildReleaseWindowsX64.yml` | 冒烟正面断言、`CheckExeFxmlView`、自证③ |
| `Docs/TroubleShooting/TS-2610-002-CMD-LF-Line-Ending-Parsing-Drift-Fix-And-Prevention.md` | 同目录另一份档案（缺陷不可见 → 机器强制的同类思路） |
| `Docs/TroubleShooting/TS-2610-001-LCD-Subpixel-Text-Composition-Native-Image-Fix-And-Upstream-Tracking.md` | 同目录另一份档案（同为 native-image 相关，但归属上游） |
| `Docs/DevSpec/TroubleShootingDocSpec.md` | 本档案遵循的命名与格式规范 |
