package com.prototypejust.weworkshop.steam

import com.github.luben.zstd.Zstd
import `in`.dragonbra.javasteam.steam.cdn.DepotChunk
import `in`.dragonbra.javasteam.types.ChunkData
import `in`.dragonbra.javasteam.util.VZipUtil
import `in`.dragonbra.javasteam.util.ZipUtil
import `in`.dragonbra.javasteam.util.stream.MemoryStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.Provider
import java.security.Security
import java.util.zip.Adler32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android 硬件加速 + 零冗余拷贝的 Steam Depot 分块解密解压器。
 *
 * 解决 javasteam 1.8.0 `DepotChunk.process` 的三大性能瓶颈：
 *  1. `CryptoHelper` 将纯 Java 版 BouncyCastle ("BC") 插到了 Security Provider 第 1 位，
 *     导致 AES-256 与 SHA-1 全部退化为纯软件字节码查表；此处显式定位 AndroidOpenSSL (Conscrypt)
 *     直接调用 ARMv8 CE 硬件 AES / SHA-1 指令集；
 *  2. 每个 worker 线程原地复用 Cipher、decryptedBuf 与原生 zlib Adler32，消除每块 3~4 次
 *     ~1MB `ByteArray` 临时分配与清零开销，Zstd 走 `decompressByteArray` 偏移量直解；
 *  3. 完整保留全部校验：分块解压长度校验 + Steam 初值 0 的 Adler32 逐字节校验（通过模 65521
 *     恒等变换直接复用 Android 原生 NEON 加速的 `java.util.zip.Adler32`）。
 *
 * 注意：单实例非线程安全，每个消费协程独立持有 1 个实例（零锁竞争）。
 */
internal class FastChunkProcessor(private val rawDepotKey: ByteArray) {

    init {
        require(rawDepotKey.size == 32) { "Tried to decrypt depot chunk with non 32 byte key!" }
    }

    private val keySpec = SecretKeySpec(rawDepotKey, "AES")
    private val ecbCipher: Cipher = createCipher("AES/ECB/NoPadding")
    private val cbcCipher: Cipher = createCipher("AES/CBC/PKCS5Padding")
    private val ivBuf = ByteArray(16)
    private var decryptedBuf = ByteArray((1 shl 20) + 1024)
    private val nativeAdler = Adler32()

    fun process(info: ChunkData, data: ByteArray, destination: ByteArray): Int {
        require(destination.size >= info.uncompressedLength) {
            "The destination buffer must be longer than the chunk uncompressedLength."
        }
        if (data.size < 32 || (data.size and 15) != 0) {
            return DepotChunk.process(info, data, destination, rawDepotKey)
        }

        // 1. 硬件 AES-ECB 解出前 16 字节 IV
        ecbCipher.init(Cipher.DECRYPT_MODE, keySpec)
        val ivWritten = ecbCipher.doFinal(data, 0, 16, ivBuf, 0)
        if (ivWritten != 16) {
            throw IOException("Failed to decrypt chunk IV: $ivWritten")
        }

        // 2. 硬件 AES-CBC 原地解密主体密文至线程复用缓冲 decryptedBuf
        val cipherPayloadLen = data.size - 16
        if (decryptedBuf.size < cipherPayloadLen) {
            decryptedBuf = ByteArray(cipherPayloadLen)
        }
        cbcCipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(ivBuf))
        val decryptedLen = cbcCipher.doFinal(data, 16, cipherPayloadLen, decryptedBuf, 0)
        if (decryptedLen < 16) {
            throw IOException("Not enough data in decrypted chunk: $decryptedLen")
        }

        // 3. 按魔数零冗余拷贝解压至 destination
        val b0 = decryptedBuf[0]
        val b1 = decryptedBuf[1]
        val b2 = decryptedBuf[2]
        val b3 = decryptedBuf[3]

        val written: Int = when {
            // VSZa (Zstandard)
            b0 == 0x56.toByte() && b1 == 0x53.toByte() && b2 == 0x5A.toByte() && b3 == 0x61.toByte() -> {
                if (decryptedLen < 23) {
                    throw IOException("Buffer too small to contain VZstd header and footer")
                }
                if (decryptedBuf[decryptedLen - 3] != 0x7A.toByte() ||
                    decryptedBuf[decryptedLen - 2] != 0x73.toByte() ||
                    decryptedBuf[decryptedLen - 1] != 0x76.toByte()
                ) {
                    throw IOException("Expecting VZstdFooter at end of stream")
                }
                val expectedSize = ByteBuffer.wrap(decryptedBuf, decryptedLen - 11, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int
                if (expectedSize < 0 || destination.size < expectedSize) {
                    throw IllegalArgumentException("The destination buffer is smaller than the decompressed data size.")
                }
                val res = Zstd.decompressByteArray(
                    destination, 0, expectedSize,
                    decryptedBuf, 8, decryptedLen - 23
                )
                if (Zstd.isError(res) || res.toInt() != expectedSize) {
                    throw IOException("VZstd decompress failed: ${Zstd.getErrorName(res)} ($res != $expectedSize)")
                }
                expectedSize
            }
            // VZa (LZMA / VZip)
            b0 == 0x56.toByte() && b1 == 0x5A.toByte() && b2 == 0x61.toByte() -> {
                MemoryStream(decryptedBuf, 0, decryptedLen).use { ms ->
                    VZipUtil.decompress(ms, destination, false)
                }
            }
            // PK\x03\x04 (Zip)
            b0 == 0x50.toByte() && b1 == 0x4B.toByte() && b2 == 0x03.toByte() && b3 == 0x04.toByte() -> {
                MemoryStream(decryptedBuf, 0, decryptedLen).use { ms ->
                    ZipUtil.decompress(ms, destination, false)
                }
            }
            // 未知新格式兜底回退官方实现
            else -> return DepotChunk.process(info, data, destination, rawDepotKey)
        }

        if (written != info.uncompressedLength) {
            throw IOException("Processed data checksum failed: written $written != expected ${info.uncompressedLength}")
        }

        // 4. Steam Seed-0 Adler32 校验（复用原生 zlib NEON 加速 Adler32，模 65521 转换初值 1 -> 0）
        val actualChecksum = steamAdler32(nativeAdler, destination, 0, written)
        if (actualChecksum != info.checksum) {
            throw IOException("Adler32 checksum mismatch for chunk! ($actualChecksum != ${info.checksum})")
        }
        return written
    }

    companion object {
        private const val ADLER_MOD = 65521L

        /** 显式定位 Android BoringSSL/Conscrypt 硬件 Provider，绕过第 1 位的纯软件 BouncyCastle。 */
        private val hwProvider: Provider? = Security.getProviders().firstOrNull { p ->
            val n = p.name
            n.equals("AndroidOpenSSL", ignoreCase = true) || n.contains("Conscrypt", ignoreCase = true)
        }

        fun providerName(): String = hwProvider?.name ?: "default"

        private fun createCipher(transformation: String): Cipher {
            val p = hwProvider
            if (p != null) {
                runCatching { return Cipher.getInstance(transformation, p) }
            }
            return Cipher.getInstance(transformation)
        }

        fun newSha1Digest(): MessageDigest {
            val p = hwProvider
            if (p != null) {
                runCatching { return MessageDigest.getInstance("SHA-1", p) }
            }
            return MessageDigest.getInstance("SHA-1")
        }

        /**
         * 利用模 65521 精确恒等式把标准 zlib Adler32 (a0=1, b0=0) 转换为 Steam Adler32 (a0=0, b0=0)：
         * a_steam = (a_zlib - 1) mod 65521
         * b_steam = (b_zlib - (len mod 65521)) mod 65521
         */
        internal fun steamAdler32(adler: Adler32, buf: ByteArray, offset: Int, len: Int): Int {
            adler.reset()
            adler.update(buf, offset, len)
            val v = adler.value
            val a = ((v and 0xffffL) + ADLER_MOD - 1L) % ADLER_MOD
            val b = (((v ushr 16) and 0xffffL) + ADLER_MOD - (len.toLong() % ADLER_MOD)) % ADLER_MOD
            return ((b shl 16) or a).toInt()
        }
    }
}