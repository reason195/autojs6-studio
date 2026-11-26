package cn.dumate.a6studio.platform

/** Desktop and Android both sit on the JVM, so one actual covers both source sets. */
actual fun nowMillis(): Long = System.currentTimeMillis()
