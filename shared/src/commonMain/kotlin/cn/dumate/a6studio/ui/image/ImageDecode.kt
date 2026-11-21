package cn.dumate.a6studio.ui.image

import androidx.compose.ui.graphics.ImageBitmap

/** Decodes a base64 PNG/JPEG payload from `screen_capture` into a Compose bitmap. */
expect fun decodeBase64ToImage(base64: String): ImageBitmap