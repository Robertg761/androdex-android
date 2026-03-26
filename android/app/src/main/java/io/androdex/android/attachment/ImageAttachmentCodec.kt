package io.androdex.android.attachment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Base64
import android.util.LruCache
import io.androdex.android.model.ImageAttachment
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import java.io.ByteArrayOutputStream

private const val ThumbnailSidePx = 70
private const val MaxPayloadDimensionPx = 1600f
private const val JpegQuality = 80

private val thumbnailCache = object : LruCache<String, Bitmap>(48) {}

fun buildImageAttachmentFromBytes(sourceData: ByteArray, sourceUrl: String? = null): ImageAttachment? {
    val normalizedPayload = normalizePayloadJpeg(sourceData) ?: return null
    val thumbnailBase64 = makeThumbnailBase64Jpeg(normalizedPayload) ?: return null
    return ImageAttachment(
        thumbnailBase64Jpeg = thumbnailBase64,
        payloadDataUrl = "data:image/jpeg;base64,${Base64.encodeToString(normalizedPayload, Base64.NO_WRAP)}",
        sourceUrl = sourceUrl,
    )
}

fun decodeAttachmentThumbnailBitmap(thumbnailBase64: String): Bitmap? {
    val trimmed = thumbnailBase64.trim()
    if (trimmed.isEmpty()) {
        return null
    }
    thumbnailCache.get(trimmed)?.let { return it }
    val bytes = runCatching {
        Base64.decode(trimmed, Base64.DEFAULT)
    }.getOrNull() ?: return null
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    thumbnailCache.put(trimmed, bitmap)
    return bitmap
}

fun decodeDataUrlImageData(dataUrl: String): ByteArray? {
    val trimmed = dataUrl.trim()
    if (!trimmed.startsWith("data:image", ignoreCase = true)) {
        return null
    }
    val payload = trimmed.substringAfter(',', missingDelimiterValue = "")
    if (payload.isEmpty()) {
        return null
    }
    return runCatching {
        Base64.decode(payload, Base64.DEFAULT)
    }.getOrNull()
}

private fun normalizePayloadJpeg(sourceData: ByteArray): ByteArray? {
    val bitmap = BitmapFactory.decodeByteArray(sourceData, 0, sourceData.size) ?: return null
    if (bitmap.width <= 0 || bitmap.height <= 0) {
        return null
    }

    val longestSide = max(bitmap.width, bitmap.height).toFloat()
    val scale = min(1f, MaxPayloadDimensionPx / longestSide)
    val targetWidth = max(1, floor(bitmap.width * scale).toInt())
    val targetHeight = max(1, floor(bitmap.height * scale).toInt())
    val normalizedBitmap = if (targetWidth == bitmap.width && targetHeight == bitmap.height) {
        bitmap
    } else {
        Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    return ByteArrayOutputStream().use { output ->
        if (!normalizedBitmap.compress(Bitmap.CompressFormat.JPEG, JpegQuality, output)) {
            return null
        }
        output.toByteArray()
    }
}

private fun makeThumbnailBase64Jpeg(imageData: ByteArray): String? {
    val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size) ?: return null
    if (bitmap.width <= 0 || bitmap.height <= 0) {
        return null
    }

    val scale = max(
        ThumbnailSidePx.toFloat() / bitmap.width.toFloat(),
        ThumbnailSidePx.toFloat() / bitmap.height.toFloat(),
    )
    val scaledWidth = max(1, floor(bitmap.width * scale).toInt())
    val scaledHeight = max(1, floor(bitmap.height * scale).toInt())
    val scaledBitmap = if (scaledWidth == bitmap.width && scaledHeight == bitmap.height) {
        bitmap
    } else {
        Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
    }

    val thumbnail = Bitmap.createBitmap(
        ThumbnailSidePx,
        ThumbnailSidePx,
        Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(thumbnail)
    val left = (ThumbnailSidePx - scaledBitmap.width) / 2f
    val top = (ThumbnailSidePx - scaledBitmap.height) / 2f
    canvas.drawBitmap(scaledBitmap, left, top, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

    return ByteArrayOutputStream().use { output ->
        if (!thumbnail.compress(Bitmap.CompressFormat.JPEG, JpegQuality, output)) {
            return null
        }
        Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }
}
