# 开发与验收记录

## 2026-11-19 · OnePlus PKX110 / Android 17（API 37）

调试版、R8 优化的未签名发布版、单元测试和 Android Lint 均已构建执行。独立验收包 `com.cjryrx.cameraredesigned.acceptance` 已通过一轮 26 项设备测试，覆盖以下路径。

| 路径 | 检查结果 |
| --- | --- |
| 从拍摄界面触发快门 | 真机触摸按下启动 AF，下拉越过阈值后发布一张 JPEG。同一手势重复跨阈值仍只拍一次，连拍松开后停止。 |
| 预览与方向 | 横竖屏均有连续纹理帧，预览宽高比匹配传感器流，进竖屏菜单返回后可以继续拍摄。系统相册中的 JPEG 可解码，尺寸与方向检查通过。 |
| RAW 保存 | RAW+JPEG 传感器时间戳配对；4096×3072 DNG 1.4 的 TIFF 目录、CFA、3072 个数据条带及 25,165,824 字节像素区完整。 |
| 曝光包围 | 从实际应用以 RAW 格式拍摄三张，系统相册中可读回三张完整 DNG 和一张全分辨率合成 JPEG。 |
| 对焦距离、曝光与白平衡 | 实际 CaptureResult 验证 P 固定 ISO、S 固定快门、M 固定两者、EV 和包围曝光时长。请求对焦位置 0 与 4 时分别稳定回报约 0.120 和 3.994。该镜头报告未标定。这些值表示相对对焦位置。4200 K 手动白平衡关闭 AWB，并取得了实际通道增益。 |
| 中断与背压 | 采集前记录预期文件，JPEG/DNG 分别原子提交。恢复测试覆盖半组 RAW、写了一部分的 MediaStore 行、重复提交以及四个作业槽位耗尽。 |
| 滤镜与成像 | 参数/LUT 导入导出、分享快照、冷启动素材保留、保存后 EXIF、真实 GPU 着色和取景回读通过。对焦融合图案在两个景深区域均恢复了细节。 |
| 模型与追踪 | 在真机运行已校验的官方 YOLO11n；七帧平移街景识别出公交车和行人，保持所选主体 ID，切换主体成功。一次测得每帧识别和解码 185–246 ms。 |

主摄公开最大 JPEG 和 RAW 均为 4096×3072，前置为 2304×1728。四个公开 Camera2 ID 均未提供单独的高分辨率尺寸或 maximum-resolution map。主摄光圈固定 ƒ/1.88，ISO 100–6400，快门约 1/12800–30 秒。EV 范围为 ±3，步长 1/6 EV。

后续 8 项扩展验收均已通过。实拍取景点选取得 4.385、4.523 两个镜头位置，保存两张源片和一张全尺寸对焦合成 JPEG。音量键在合焦优先下快速松开仍保存一张；倒计时切后台立即返回后没有误拍，重新倒计时正常保存。RAW 单张使用预览与 RAW 两个输出，未分配 JPEG 流。

改用有界多请求管线后，主摄各档按住快门约八秒，从系统相册逐张确认 4096×3072 JPEG。按相邻传感器时间戳计算的平均速率如下。场景、光线和机身温度都会影响可达到的速率。

| 连拍档位 | 保存张数 | 实测速率 |
| --- | ---: | ---: |
| 低 | 16 | 1.97 张/秒 |
| 中 | 39 | 4.83 张/秒 |
| 高 | 75 | 9.33 张/秒 |
| 最高 | 85 | 10.59 张/秒 |

同设备先前的串行路径在每个八秒窗口内各保存 13 张，实测 1.53–1.62 张/秒。上述各轮 Android 热状态均为 0。独立权限用例也已通过，首次拒绝相机权限后，可从应用界面重新授权并恢复实时取景。调试包四次进程冷启动的界面显示用时为 395–643 ms。最后一次从启动到第一帧实际取景为 1.553 秒。

## 复现附加验收

下列参数添加在独立包 `adb shell am instrument -w -r` 与 runner `com.cjryrx.cameraredesigned.acceptance.test/androidx.test.runner.AndroidJUnitRunner` 之间。

- `-e measureBurst true` 启用每档八秒的连拍验收。记录成功发布数、原始传感器时间戳跨度、实际 JPEG 尺寸以及系统热状态。
- `-e testStagedModel true` 验证 `files/models/active.onnx` 的 SHA-256、实际推理以及失败导入后原模型可用性。模型来源见[模型协议](MODEL_CONTRACT.md)。
- `-e testTrackingFixture true` 另外验证真实物体与移动后的选区关联。将 Ultralytics v8.4.0 的 [bus.jpg](https://github.com/ultralytics/ultralytics/blob/v8.4.0/ultralytics/assets/bus.jpg) 放入同一包的 `cache/tracking-fixture.jpg`。样图 SHA-256 为 `c02019c4979c191eb739ddd944445ef408dad5679acab6fd520ef9d434bfbc63`。
- 权限用例须单独运行，先撤销独立包的 CAMERA 权限并清除该权限的 `user-set` 和 `user-fixed` 标记，再传入 `-e testPermissionRecovery true -e class com.cjryrx.cameraredesigned.integration.CameraPermissionDeviceTest`。用例会走一次系统拒绝，再从应用界面重新授权。
