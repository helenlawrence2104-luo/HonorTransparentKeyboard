# HonorTransparentKeyboard（荣耀通透键盘）

适用于百度输入法荣耀版的 LSPosed 模块。已在荣耀 MagicOS 11、百度输入法荣耀版 `com.baidu.input_hihonor` 8.2.510.383 上实机验证。

## v1.9.23

- 键盘底板、键帽和工具栏使用荣耀原生玻璃材质。页面有纹理时可产生边缘折射，纯色背景使用局部光影保持键帽可辨认。
- 支持背景、键帽透明度与按键振动设置，背景可跟随荣耀系统。
- 根据 Android 提供的输入框位置自动判断是否叠层，各应用使用同一规则。实测 B 站顶部搜索框启用叠层且按键触控区保持原位。抖音评论和荣耀搜索在实测场景保留正常占位。
- 页面铺设不透明底色或未在键盘后方绘制内容时，无法透出更底层的图案。

## 安装和构建

1. 从 [v1.9.23 Release](https://github.com/helenlawrence2104-luo/HonorTransparentKeyboard/releases/tag/v1.9.23) 下载模块 APK。
2. 在 LSPosed 启用模块，作用域选择百度输入法荣耀版（`com.baidu.input_hihonor`）。
3. 保存当前输入后，在模块设置页重启输入法。页面可调透明度与振动，也可打开预览。

发布附件使用与实机测试相同的调试签名。不同签名的现有安装无法直接覆盖。其他输入法和系统版本需要重新验证。

使用 JDK 21、Gradle 8.14.5、Android SDK Platform 35 和 Build Tools 35.0.1。在根目录执行 `gradle assembleDebug`。APK 位于 `build/outputs/apk/debug/`。

## 诊断与权利

LSPosed 日志可能包含窗口或视图诊断信息。分享问题时请移除原始设备日志、输入内容和私人截图。背景适配读取荣耀接口返回的平均亮度，叠层规则使用编辑器边界。

本项目为独立模块。自有代码沿用仓库既有的权利保留声明。第三方代码的归属与适用许可证见 [第三方归属说明](THIRD_PARTY_NOTICES.md)。
