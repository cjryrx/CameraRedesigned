# Camera Redesigned

Kotlin 原生专业摄影应用。包名 `com.cjryrx.cameraredesigned`，Android 10+，当前目标 Android 17。

首次打开后授予相机权限即可取景。按住右上角的圆形滑块对焦，向下滑动触发快门。在连拍模式下，保持在触发位置会继续拍摄，松开即停止。照片保存在系统相册的 `DCIM/CameraRedesigned/`。设备实测结果见[开发记录](docs/PROGRESS.md)，完整操作规则见[需求基线](docs/REQUIREMENTS.md)。

## 架构

Compose Material 3 界面、Camera2 拍摄、OpenGL ES 3 实时取景处理、可选 ONNX 主体识别。默认使用设备系统 AF；模型下载必须由用户触发。照片保存采用内部工作文件和 MediaStore 发布事务，包围拍摄与图像处理使用有界队列。

目录按实际职责分离：`domain`、`camera`、`imaging`、`tracking`、`data`、`ui`。详见 [架构记录](docs/ARCHITECTURE.md)。

## 构建与验证

使用项目 Gradle Wrapper 和 Android Studio 配置的 JDK。需要 Android SDK API 37；项目最低 API 29。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:assembleDebugAndroidTest
.\gradlew.bat :app:assembleRelease :app:bundleRelease
```

调试 APK 可直接安装。发布构建生成经过 R8 优化的 APK 和 Android App Bundle，分发前请使用自己的发布密钥签名。

连接已授权的 Android 测试设备后，可在独立包名下运行包含相册发布的验收：

```powershell
.\gradlew.bat -PisolatedDeviceTests=true :app:connectedDebugAndroidTest
```

独立包名为 `com.cjryrx.cameraredesigned.acceptance`。工作流用例通过实际滑块、Camera2 和 MediaStore 拍摄，再从系统相册读回照片，检查全分辨率像素、EXIF、DNG 与合成结果。清理按独立包的所有权和本次新增 URI 执行。运行时请保持设备已解锁，并预留至少 300 MB 可用空间。附加模型、性能及权限验收的参数见[开发记录](docs/PROGRESS.md)。

当前 Windows 执行环境的 JDK Unix-domain socket 不可用时，可仅对当前终端设置：

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\nonexistent-camera-uds'
```

这会令 JDK 的本地管道回退到 TCP。该不存在目录是有意设置的环境兼容选项，不应创建该目录；正常开发环境无需此设置。

## 模型与滤镜

默认下载模型采用固定版本、HTTPS 和 SHA-256 校验。首次使用由系统自动对焦，安装模型后可在设置中切换。自定义模型的输入输出形状与验证规则见[模型协议](docs/MODEL_CONTRACT.md)。

`.crfilter` 文件为 ZIP 包，仅包含 `filter.json` 和可选 `lut.cube`。参数滤镜和 LUT 互斥选择；滤镜仅作用于 JPEG。RAW 使用 DNG。

## 贡献

请为拍摄时序、资源所有权、格式解析、图像数值和恢复逻辑的修改提供相应验证。避免让 UI 直接持有相机设备或编码器；避免在相机回调线程进行照片处理或网络请求。设备相关问题请附 Android 版本、镜头能力和可复现步骤。

作者与贡献者：cjryrx（梦得斯鸫）、ChatGPT。

许可：[GNU AGPL-3.0](LICENSE)。第三方库与模型适用各自的许可；模型来源与校验信息见协议文档。
