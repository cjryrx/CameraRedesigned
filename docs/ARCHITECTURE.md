# Architecture

One Android application module initially, with dependency boundaries enforced by packages. Split Gradle modules only when a measured build or ownership need warrants it.

- `domain`: immutable product state, capabilities, parameter policies; no Android dependency.
- `camera`: Camera2 ownership, request serialization, timestamp-paired image acquisition, capability discovery. No UI or filter persistence.
- `imaging`: color/LUT kernels, image alignment and exposure/focus fusion. No camera or UI ownership.
- `data`: settings/filter persistence, atomic MediaStore publishing and durable work files.
- `tracking`: optional ONNX inference, subject association and coordinate transforms.
- `ui`: stateless Compose views consuming CameraUiState and emitting CameraAction.
- application coordinator: lifecycle, capture sequence state machine and bounded processing queue.

The coordinator reserves a processing slot and writes a journal containing every expected JPEG and DNG name before submitting the first exposure. Camera2 matches image buffers to sensor timestamps. Each completed file is flushed and atomically renamed in the journal directory. The worker processes committed frames, copies photos into pending MediaStore rows, syncs the bytes and then makes each row visible. A completed journal becomes a cleanup marker. After interruption, recovery uses the same names and URIs, retains usable frames from incomplete brackets and avoids publishing incomplete bytes. A failed write can be retried from the viewfinder after storage becomes available.

Preview and acquisition have independent surfaces. EGL applies the native buffer transform once, preserves aspect ratio and optionally grades the live image. Bounded readback supplies upright, unfiltered analysis frames. The coordinator drops frames when inference is busy. Detection and software exposure metering can run independently of still-image processing. The source JPEG pipeline applies the selected filter regardless of the live preview switch.
