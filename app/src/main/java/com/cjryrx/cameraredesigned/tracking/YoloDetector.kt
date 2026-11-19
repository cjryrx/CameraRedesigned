package com.cjryrx.cameraredesigned.tracking

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.core.graphics.createBitmap
import com.cjryrx.cameraredesigned.domain.SubjectBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Optional offline detector. Camera2, not this model, drives the lens actuator. */
class YoloDetector(context: Context) : AutoCloseable {
    private val application = context.applicationContext
    private val modelDirectory = File(application.filesDir, "models")
    private val activeFile get() = File(modelDirectory, "active.onnx")
    private val mutex = Mutex()
    @Volatile private var closed = false
    private var loaded: Loaded? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var canvasBitmap: Bitmap? = null
    private val pixels by lazy(LazyThreadSafetyMode.NONE) { IntArray(YoloPostprocessor.SIZE * YoloPostprocessor.SIZE) }

    private class Loaded(
        val session: OrtSession,
        val inputName: String,
        val outputName: String,
        val classes: Int,
        val labels: List<String>,
        val input: FloatBuffer,
        val output: FloatBuffer,
        val inputTensor: OnnxTensor,
        val outputTensor: OnnxTensor,
    ) : AutoCloseable {
        override fun close() { try { session.close() } finally { try { inputTensor.close() } finally { outputTensor.close() } } }
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        withModelLock {
            check(!closed) { "Detector has been closed" }
            if (loaded != null) return@withModelLock
            val candidate = openValidated(activeFile)
            try {
                currentCoroutineContext().ensureActive()
                check(!closed) { "Detector has been closed" }
                loaded?.close()
                loaded = candidate
            } catch (error: Throwable) { candidate.close(); throw error }
            finally { closeIfRequested() }
        }
    }

    /** Validates the entire model before replacing the last working installed model. */
    suspend fun install(uri: Uri) = withContext(Dispatchers.IO) {
        val stream = requireNotNull(application.contentResolver.openInputStream(uri)) { "Cannot read selected model" }
        stream.use { installStream(it) }
    }

    suspend fun install(file: File) = withContext(Dispatchers.IO) { file.inputStream().use { installStream(it) } }

    /** Explicit user action only. The version and publisher's SHA-256 are pinned, never "latest". */
    suspend fun downloadDefault(progress: (Int) -> Unit = {}) = withTimeout(180_000) {
        withContext(Dispatchers.IO) {
            check(!closed) { "Detector has been closed" }
            require(modelDirectory.isDirectory || modelDirectory.mkdirs()) { "Cannot create model directory" }
            val temporary = File.createTempFile("download-", ".onnx", modelDirectory)
            var connection: HttpsURLConnection? = null
            try {
                var url = URL(DEFAULT_MODEL_URL)
                var connected = false
                for (redirect in 0..5) {
                    currentCoroutineContext().ensureActive()
                    val request = url.openConnection() as HttpsURLConnection
                    connection = request
                    request.connectTimeout = 15_000
                    request.readTimeout = 15_000
                    request.instanceFollowRedirects = false
                    request.setRequestProperty("User-Agent", "CameraRedesigned/0.1")
                    val status = request.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        val location = requireNotNull(request.getHeaderField("Location")) { "Invalid model download redirect" }
                        val redirected = URL(url, location)
                        require(redirected.protocol == "https") { "Model download requires HTTPS" }
                        request.disconnect()
                        url = redirected
                    } else {
                        require(status == 200) { "Model download failed (HTTP $status)" }
                        connected = true
                        break
                    }
                }
                require(connected) { "Too many model download redirects" }
                val request = requireNotNull(connection)
                val length = request.contentLengthLong
                require(length <= MAX_MODEL_BYTES) { "Model download exceeds 128 MiB" }
                val digest = MessageDigest.getInstance("SHA-256")
                progress(0)
                request.inputStream.use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        var reported = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            check(!closed) { "Detector has been closed" }
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_MODEL_BYTES) { "Model download exceeds 128 MiB" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            val percent = if (length > 0) (total * 99 / length).toInt().coerceIn(0, 99) else 0
                            if (percent != reported) { progress(percent); reported = percent }
                        }
                        require(total > 0 && (length <= 0 || total == length)) { "Incomplete model download" }
                        output.fd.sync()
                    }
                }
                val expected = DEFAULT_MODEL_SHA256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                require(MessageDigest.isEqual(expected, digest.digest())) { "Model checksum mismatch; previous model was retained" }
                install(temporary)
                progress(100)
            } finally { connection?.disconnect(); temporary.delete() }
        }
    }

    private suspend fun installStream(input: InputStream) {
        require(modelDirectory.isDirectory || modelDirectory.mkdirs()) { "Cannot create model directory" }
        val temporary = File.createTempFile("model-", ".onnx", modelDirectory)
        try {
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(64 * 1024)
                var bytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= MAX_MODEL_BYTES) { "Model exceeds 128 MiB limit" }
                    output.write(buffer, 0, count)
                }
                require(bytes > 0) { "Empty model file" }
                output.fd.sync()
            }
            withModelLock {
                check(!closed) { "Detector has been closed" }
                val candidate = openValidated(temporary)
                try {
                    currentCoroutineContext().ensureActive()
                    check(!closed) { "Detector has been closed" }
                    Files.move(temporary.toPath(), activeFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    loaded?.close()
                    loaded = candidate
                } catch (error: Throwable) { candidate.close(); throw error }
                finally { closeIfRequested() }
            }
        } finally { temporary.delete() }
    }

    suspend fun detect(bitmap: Bitmap): List<SubjectBox> = withContext(Dispatchers.Default) {
        withModelLock {
            check(!closed) { "Detector has been closed" }
            val model = checkNotNull(loaded) { "No subject model is loaded" }
            try {
                currentCoroutineContext().ensureActive()
                val transform = YoloPostprocessor.letterbox(bitmap.width, bitmap.height)
                val target = canvasBitmap ?: createBitmap(YoloPostprocessor.SIZE, YoloPostprocessor.SIZE).also { canvasBitmap = it }
                val canvas = Canvas(target)
                canvas.drawColor(Color.rgb(114, 114, 114))
                canvas.drawBitmap(bitmap, null, RectF(transform.left.toFloat(), transform.top.toFloat(),
                    (transform.left + transform.resizedWidth).toFloat(), (transform.top + transform.resizedHeight).toFloat()), paint)
                target.getPixels(pixels, 0, YoloPostprocessor.SIZE, 0, 0, YoloPostprocessor.SIZE, YoloPostprocessor.SIZE)
                val plane = pixels.size
                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    model.input.put(i, (pixel ushr 16 and 255) / 255f)
                    model.input.put(plane + i, (pixel ushr 8 and 255) / 255f)
                    model.input.put(plane * 2 + i, (pixel and 255) / 255f)
                }
                currentCoroutineContext().ensureActive()
                model.session.run(mapOf(model.inputName to model.inputTensor), mapOf(model.outputName to model.outputTensor)).use {
                    currentCoroutineContext().ensureActive()
                    model.output.rewind()
                    YoloPostprocessor.decode(model.output, model.classes, model.labels, transform)
                }
            } finally { closeIfRequested() }
        }
    }

    /** Nonblocking for the UI thread; an in-flight native run releases resources in its finally. */
    override fun close() {
        closed = true
        if (mutex.tryLock()) try { closeIfRequested() } finally { mutex.unlock() }
    }

    private suspend fun <T> withModelLock(action: suspend () -> T): T = mutex.withLock {
        // The outer finally also covers a close race before validation or a failed session creation.
        try { action() } finally { closeIfRequested() }
    }

    private fun closeIfRequested() {
        if (closed) {
            try { loaded?.close() } finally {
                loaded = null
                canvasBitmap?.recycle()
                canvasBitmap = null
            }
        }
    }

    private fun openValidated(file: File): Loaded {
        require(file.isFile && file.length() in 1..MAX_MODEL_BYTES) { "Import a valid ONNX model first" }
        val environment = OrtEnvironment.getEnvironment()
        val session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Official Ultralytics exports use these symbolic axes. Bind them to the v1 protocol.
            options.setSymbolicDimensionValue("batch", 1)
            options.setSymbolicDimensionValue("height", 640)
            options.setSymbolicDimensionValue("width", 640)
            options.setSymbolicDimensionValue("anchors", 8400)
            environment.createSession(file.path, options)
        }
        var inputTensor: OnnxTensor? = null
        var outputTensor: OnnxTensor? = null
        try {
            require(session.inputInfo.size == 1 && session.outputInfo.size == 1) { "Model must have exactly one input and one output" }
            val inputNode = session.inputInfo.entries.single()
            val inputInfo = inputNode.value.info as? TensorInfo ?: throw IllegalArgumentException("Model input must be a tensor")
            require(inputInfo.type == OnnxJavaType.FLOAT && inputInfo.shape.contentEquals(longArrayOf(1, 3, 640, 640))) {
                "Expected Float32 RGB input [1,3,640,640]"
            }
            val outputNode = session.outputInfo.entries.single()
            val outputInfo = outputNode.value.info as? TensorInfo ?: throw IllegalArgumentException("Model output must be a tensor")
            val shape = outputInfo.shape
            require(outputInfo.type == OnnxJavaType.FLOAT && shape.size == 3 && shape[0] == 1L && shape[1] in 5L..260L && shape[2] == 8400L) {
                "Expected Float32 output [1,4+C,8400] with 1..256 classes; export without embedded NMS"
            }
            val classes = shape[1].toInt() - 4
            val task = session.metadata.customMetadata["task"]
            require(task == null || task == "detect") { "Only object detection models are supported" }
            val labels = labels(session.metadata.customMetadata["names"], classes)
            val input = floatBuffer(3 * 640 * 640)
            val output = floatBuffer((4 + classes) * 8400)
            inputTensor = OnnxTensor.createTensor(environment, input, longArrayOf(1, 3, 640, 640))
            outputTensor = OnnxTensor.createTensor(environment, output, shape)
            // A warm-up catches unsupported graphs before changing the user's installed model.
            for (i in 0 until input.capacity()) input.put(i, 114f / 255)
            session.run(mapOf(inputNode.key to inputTensor), mapOf(outputNode.key to outputTensor)).use {
                for (i in 0 until output.capacity()) {
                    require(output[i].isFinite()) { "Model returned non-finite output" }
                    if (i >= 4 * 8400) require(output[i] in 0f..1f) { "Model class outputs must be probabilities, not logits" }
                }
            }
            return Loaded(session, inputNode.key, outputNode.key, classes, labels, input, output, inputTensor, outputTensor)
        } catch (error: Throwable) {
            try { session.close() } finally { try { inputTensor?.close() } finally { outputTensor?.close() } }
            throw error
        }
    }

    companion object {
        const val MAX_MODEL_BYTES = 128L * 1024 * 1024
        const val DEFAULT_MODEL_URL = "https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo11n.onnx"
        const val DEFAULT_MODEL_SHA256 = "634279b40c07c6391472c51ad45b81ebc48706a9a1fe72dd3396322acd0c053b"
        private fun floatBuffer(size: Int): FloatBuffer = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        internal fun labels(metadata: String?, classes: Int): List<String> {
            val fallback = if (classes == 80) COCO else List(classes) { "class_$it" }
            if (metadata == null || metadata.length > 65_536) return fallback
            // Ultralytics stores a Python dictionary literal. Parse data only; never evaluate it.
            val matches = Regex("(?:^|[,{\\s])['\"]?(\\d+)['\"]?\\s*:\\s*(['\"])(.*?)\\2").findAll(metadata)
            val names = matches.mapNotNull { match ->
                val index = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                if (index !in 0 until classes) null else index to match.groupValues[3].take(100)
            }.toMap()
            return List(classes) { names[it]?.takeIf(String::isNotBlank) ?: fallback[it] }
        }

        private val COCO = listOf("person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush")
    }
}
