package cn.dumate.a6studio.ui.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.util.Base64

actual fun decodeBase64ToImage(base64: String): ImageBitmap {
    val clean = base64.substringAfter("base64,", base64)
    val bytes = Base64.getMimeDecoder().decode(clean)
    return Image.makeFromEncoded(bytes).toComposeImageBitmap()
}