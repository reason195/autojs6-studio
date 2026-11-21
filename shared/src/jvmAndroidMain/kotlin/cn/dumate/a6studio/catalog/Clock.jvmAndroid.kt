package cn.dumate.a6studio.catalog

import java.time.LocalDateTime

actual fun nowLocal(): LocalTimeParts {
    val now = LocalDateTime.now()
    return LocalTimeParts(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute)
}