package cn.dumate.a6studio.catalog

/** Local wall-clock parts used in generated-file banners. */
data class LocalTimeParts(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int
)

expect fun nowLocal(): LocalTimeParts