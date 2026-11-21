package cn.dumate.a6studio.platform

/**
 * Current wall-clock time in milliseconds since the epoch.
 *
 * `commonMain` used to call `System.currentTimeMillis()` directly, which only works because the
 * project currently targets the JVM (desktop) and Android. Going through this expect declaration
 * keeps the shared code buildable on any future target (iOS, web, native).
 */
expect fun nowMillis(): Long
