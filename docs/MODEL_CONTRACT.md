# Offline subject model contract, v1

Default autofocus requires no model and no network. Import or download is an explicit action. An enabled detector supplies an image-space rectangular region; Camera2 autofocus still moves the lens.

## Model and tensors

- Single-file ONNX object detector; one input, one output, maximum 128 MiB. External tensor data files, segmentation, pose and embedded-NMS outputs are unsupported.
- Input Float32 `[1,3,640,640]`, planar RGB, values 0..1. Preserve aspect ratio, resize with bilinear filtering, center in a 640-square canvas filled with RGB 114/114/114. Padding is integer, left/top receive floor(remaining/2).
- Output Float32 `[1,4+C,8400]`, 1..256 classes. First four planes are center X, center Y, width, height in 640-input pixel coordinates; remaining planes are class probabilities 0..1. No separate objectness plane, no sigmoid in the application.
- Fixed shapes are preferred. The standard Ultralytics symbolic names `batch`, `height`, `width`, `anchors` are accepted and bound to 1, 640, 640, 8400 before session validation. Other unbound/dynamic layouts are rejected.
- Optional `task` metadata must be `detect`. Optional `names` metadata is parsed as a dictionary of integer keys and quoted labels; it is never evaluated. Without labels, 80-class models use COCO names, other class counts show `class_N`. Custom 80-class models must provide their label dictionary.
- Confidence threshold 0.35, class-aware IoU NMS 0.45; retain at most 300 candidates before NMS and 100 detections. Letterbox coordinates are converted to normalized original-image coordinates.

## Default download

Publisher: Ultralytics; model: YOLO11n detection, COCO 80 classes; release: v8.4.0.

- Download: https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo11n.onnx
- Publisher asset listing: https://github.com/ultralytics/assets/releases/expanded_assets/v8.4.0
- SHA-256: `634279b40c07c6391472c51ad45b81ebc48706a9a1fe72dd3396322acd0c053b`
- On 2026-11-18 the binary was downloaded into the local build directory and its checksum matched the publisher's listing. Protobuf inspection confirmed Float32 `images` input `[batch,3,height,width]` and `output0` output `[batch,84,anchors]`.
- Runtime loading additionally verifies shapes after binding and performs a warm-up into the fixed output tensor. Network download verifies the SHA before installation. Failed validation leaves the previously installed model untouched.
- HTTPS only, bounded redirects and bytes, 15-second connection/read timeouts, 3-minute coroutine deadline, progress, cancellation checks and temporary-file cleanup. No model is embedded in the APK or fetched at first launch.

Model source/export documentation: https://docs.ultralytics.com/integrations/onnx/ . Ultralytics licensing: https://www.ultralytics.com/license . Project license is AGPL-3.0; retain applicable upstream attribution when redistributing model artifacts.

## Runtime and tracking

Use one `YoloDetector` instance. Load/install/detect are mutex-serialized; direct native-order FloatBuffers and pinned input/output tensors are reused. Session CPU parallelism is bounded to two inference threads. Detection processes the latest upright preview supplied by the coordinator; it does not queue stale frames or own the caller's bitmap. The coordinator drops work while a previous analysis is active.

`close()` is nonblocking; it requests closure and releases resources when an in-flight operation exits. Native inference runs to completion unless the runtime itself fails; coroutine cancellation is checked around it. A model's CPU/GPU speed is not inferred from its filename and must be measured on the target device.

Subject IDs use class-aware IoU association with four detection intervals of retention. This is lightweight positional association, not a visual re-identification network. A temporarily missing subject produces no stale focus box. Region mode reacquires only within the selected area; continuous mode follows the acquired target outside that area while pressed; full-screen mode acquires anywhere. The coordinator calls `next()` on release/custom key, and the next analysis selects the next eligible subject. Without a suitable detection, it must restore region/system AF.
