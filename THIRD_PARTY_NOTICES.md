# 第三方归属与许可说明

## Kyant0/AndroidLiquidGlass（Backdrop）

- 上游项目：https://github.com/Kyant0/AndroidLiquidGlass
- 归属：Copyright 2025 Kyant（与当前 `Glass.java` 的原有声明一致）。
- 许可：Apache License, Version 2.0。
- 本仓库涉及文件：`src/main/java/local/honor/transparentkeyboard/Glass.java` 中移植的 RoundedRectSDF、DefaultHighlightShader 和相关高光、阴影、表面参数。
- 修改概述：将 Compose/AGSL 相关绘制思路适配到 Android Canvas/IME；调整高光角度和衰减、上下边缘权重、表面透明度、阴影与键盘尺寸；没有移植完整镜片折射。更具体的原始归属与修改说明保留在 `Glass.java` 文件头。
- 随附上游实际许可原文：[Apache-2.0-AndroidLiquidGlass.txt](licenses/Apache-2.0-AndroidLiquidGlass.txt)。

### 本次许可及 NOTICE 核查

2026-11-16 核查上游提交 `eb4a0ea2e17542ea28c2e71088f7dd9acf77aad5` 的完整递归 Git 文件树（289 项，未截断），存在根目录 `LICENSE`，没有发现名为 NOTICE 或其变体的文件。因此没有凭空构造一个“上游 NOTICE”。本说明是本项目自己的第三方归属说明，不冒称上游 NOTICE。

- 核查提交：https://github.com/Kyant0/AndroidLiquidGlass/tree/eb4a0ea2e17542ea28c2e71088f7dd9acf77aad5
- 许可原文：https://github.com/Kyant0/AndroidLiquidGlass/blob/eb4a0ea2e17542ea28c2e71088f7dd9acf77aad5/LICENSE
- 许可原文 Git blob SHA-1：`e29d7cc03d2595fe32466a3ff9f3d7bc75d56339`（10,750 字节）；随附文件按字节校验匹配。

上述提交用于本次许可核查，**不宣称它就是最初移植时使用的精确源码提交**；原始移植记录未锁定完整上游提交号。若以后确认引入内容对应的历史版本包含额外 NOTICE，应补充保留其中适用的声明。

本文件及随附 Apache 许可只说明相应第三方内容的权利，不给本项目其他自有代码另行授予开源许可。

## 构建依赖

Android/Gradle/Xposed 等依赖通过构建配置引用，本次不把它们的安装包、缓存、JAR 或第三方反编译源码打包进仓库。它们各自的许可证与使用条件不因本项目而改变；以后若发布二进制或捆绑依赖，仍须重新核对相应分发义务。
## LiquidGlassOptics 中的额外移植与行为参考（v1.8.3 Preview 同窗口验证）

- `LiquidGlassOptics.java` 中的圆角矩形 SDF、圆周映射边缘折射和梯度偏移，移植自 Kyant0/AndroidLiquidGlass（Copyright 2025 Kyant，Apache License 2.0）。既有 `licenses/Apache-2.0-AndroidLiquidGlass.txt` 仍然适用。
- 默认位移/模糊/饱和、轻微色散、over-light（位移减半、额外霜化、暗层，而不是描边）以及文字后叠且不进入滤镜，参考 liquid-glass-react（https://github.com/rdev/liquid-glass-react，Copyright 2025 MAX ROVENSKY，MIT License）的公开行为说明。本仓库未复制其 SVG/CSS/React 源码或位移 JPEG。若以后复制这些资产或实质性源码，须完整保留 MIT 版权声明。
- 该光学路径目前只用于 Preview 内置公开测试图案的同窗口采样，不是 iOS 26 原生渲染，也不是跨应用真折射。
