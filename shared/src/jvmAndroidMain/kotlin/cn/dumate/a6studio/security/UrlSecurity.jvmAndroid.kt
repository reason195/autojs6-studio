package cn.dumate.a6studio.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/** 在 IO 线程做阻塞 DNS 解析（Android 主线程解析会抛 NetworkOnMainThreadException）。 */
actual suspend fun resolveHostAddresses(host: String): List<ByteArray> = withContext(Dispatchers.IO) {
    runCatching { InetAddress.getAllByName(host).map { it.address } }.getOrDefault(emptyList())
}
