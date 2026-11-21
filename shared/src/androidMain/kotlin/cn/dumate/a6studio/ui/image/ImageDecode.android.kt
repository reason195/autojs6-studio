package cn.dumate.a6studio.ui.image

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeBase64ToImage(base64: String): ImageBitmap {
    val clean = base64.substringAfter("base64,", base64)
    val bytes = Base64.decode(clean, Base64.DEFAULT)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw IllegalStateException("无法解码截图")
    return bitmap.asImageBitmap()
}