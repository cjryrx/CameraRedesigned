# Imaging boundary

`ImageProcessor` owns no source files and never overwrites an input. The caller owns source retention, durable job state, publication, retries, and errors. A single processor instance serializes full-resolution operations; use one application-scoped instance.

Public suspend APIs: `process(File, File, FilterSpec?, Int)`, `mergeExposure(List<File>, File, Int, FilterSpec?)`, `mergeFocus(...)`, `preview(Bitmap, FilterSpec?)`, `decodePreview(File, maxEdge)`.

Filter units: exposure -5..5 EV; temperature, tint, saturation, vibrance, contrast, highlights and shadows -1..1; sharpness, blur and strength 0..1. Strength applies to the active filter. A LUT takes precedence over parameter adjustments, making these alternatives mutually exclusive. LUTs must map display-referred sRGB to sRGB; a log camera LUT needs an appropriate conversion and is not inferred from an unannotated .cube file.

LUT import accepts bounded 3D .cube (2..65 nodes per axis, 32 MiB text, finite samples, optional DOMAIN_MIN/MAX). Red is fastest. Mixed 1D/3D shapers and LUT_3D_INPUT_RANGE are rejected rather than silently misinterpreted. Trilinear interpolation is performed in LUT input space.

JPEG inputs decode explicitly to sRGB. Exposure gain and fusion accumulation use linear light; displayed filter controls and LUTs use display-referred values. Output retains dimensions and EXIF orientation, capture information, timestamp, author, and location if present. Old thumbnail data is not copied. Fusion metadata describes the operation and retains the selected reference frame's capture metadata. Exposure stacks accept an explicit neutral reference index.

The reference frame defines the full output dimensions and framing. The thumbnail fit estimates uniform magnification, roll and translation. It searches up to roughly 10% magnification change, 5° roll and 16 thumbnail pixels of centre displacement. Four independently normalized areas compare common low-frequency structure across focus or exposure changes. At least three areas must support a scale/roll estimate. A validated translation remains available when scene structure supports only that motion.

At sensor resolution, up to nine separated patches refine the fit. At least four consistent correspondences spanning both axes and a majority of patch votes are required to change the coarse fit. Fusion reads transformed JPEG regions in bounded tiles with bilinear sampling. Integer translations use an exact pixel copy. Uncovered borders use valid frames in the original reference framing. Perspective changes, rolling shutter, local depth-dependent parallax and independently moving focus subjects can exceed this global similarity model.

Exposure fusion excludes clipped samples, smooths weights, and suppresses brightness-inconsistent movement relative to the selected reference. A fully clipped candidate thumbnail contributes no image. Motion in a clipped reference region cannot be estimated reliably. Focus fusion blends two-scale local sharpness energy and excludes invalid edge gradients.

Memory is one full-resolution output bitmap plus bounded stripes and small registration thumbnails, independent of source frame count. No automatic resolution reduction. A memory preflight can reject an image too large for the available heap; the caller must retain sources and report this. A future streaming JPEG encoder would remove the full output bitmap constraint. Files are atomically replaced only after successful JPEG encoding, EXIF writing, and sync. Cancellation and failure remove incomplete output staging files.

CPU preview is for the filter editor. Real-time viewfinder integration requires a GPU renderer or explicit throttled analysis preview outside this package; these APIs do not claim to implement that renderer.
