package com.cjryrx.cameraredesigned.camera

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Same-directory commit: readers see a complete, fsynced file or no destination file at all. */
internal fun commitCaptureFile(destination: File, write: (FileOutputStream) -> Unit): File {
    require(!destination.exists()) { "Capture destination already exists: ${destination.name}" }
    val directory = requireNotNull(destination.absoluteFile.parentFile)
    check(directory.mkdirs() || directory.isDirectory) { "Cannot create capture directory" }
    val part = File.createTempFile(".${destination.name}-", ".capture-part", directory)
    try {
        FileOutputStream(part).use { output ->
            write(output)
            output.fd.sync()
        }
        check(part.length() > 0) { "Refusing to commit an empty camera image" }
        // ATOMIC_MOVE is required; do not fall back to copy-and-delete for a completed image.
        Files.move(part.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return destination
    } finally {
        // Never remove destination: it may be the only recoverable frame after caller cancellation.
        part.delete()
    }
}
