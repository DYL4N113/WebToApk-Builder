package com.dyl4n.webtoapk.engine

import android.content.Context
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object SignerHelper {
    private const val KEY_ALIAS = "dyl4n"
    private const val KEY_PASSWORD = "dyl4n123"
    private const val STORE_TYPE = "PKCS12"
    private const val VALIDITY_DAYS = 10_000L

    fun getOrCreateKeystore(context: Context): File {
        val keystoreFile = File(context.filesDir, "release.keystore")
        if (keystoreFile.isFile) return keystoreFile

        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()
        val certificate = createSelfSignedCertificate(keyPair)

        val keyStore = KeyStore.getInstance(STORE_TYPE)
        keyStore.load(null, null)
        keyStore.setKeyEntry(
            KEY_ALIAS,
            keyPair.private,
            KEY_PASSWORD.toCharArray(),
            arrayOf(certificate),
        )

        FileOutputStream(keystoreFile).use { output ->
            keyStore.store(output, KEY_PASSWORD.toCharArray())
        }
        return keystoreFile
    }

    fun signApk(inputApk: File, outputApk: File, keystoreFile: File) {
        require(inputApk.isFile) { "APK input tidak ditemukan: ${inputApk.absolutePath}" }
        require(keystoreFile.isFile) {
            "Keystore tidak ditemukan: ${keystoreFile.absolutePath}"
        }

        val keyStore = KeyStore.getInstance(STORE_TYPE)
        FileInputStream(keystoreFile).use { input ->
            keyStore.load(input, KEY_PASSWORD.toCharArray())
        }
        val privateKey = keyStore.getKey(KEY_ALIAS, KEY_PASSWORD.toCharArray())
            ?: error("Private key dengan alias '$KEY_ALIAS' tidak ditemukan")
        val certificate = keyStore.getCertificate(KEY_ALIAS)
            ?: error("Sertifikat dengan alias '$KEY_ALIAS' tidak ditemukan")

        val signerConfig = ApkSigner.SignerConfig.Builder(
            KEY_ALIAS,
            privateKey as java.security.PrivateKey,
            listOf(certificate as java.security.cert.X509Certificate),
        ).build()
        val signer = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
        signer.sign()
    }

    fun verifyApk(apkFile: File) {
        require(apkFile.isFile) { "APK untuk verifikasi tidak ditemukan: ${apkFile.absolutePath}" }

        val result = ApkVerifier.Builder(apkFile).build().verify()
        if (!result.isVerifiedUsingV2Scheme && !result.isVerifiedUsingV3Scheme) {
            val errors = result.errors.joinToString(separator = "\n") { it.toString() }
                .ifBlank { "ApkVerifier tidak memberikan detail error." }
            val details = """
                APK verified: ${result.isVerified}
                V2 verified: ${result.isVerifiedUsingV2Scheme}
                V3 verified: ${result.isVerifiedUsingV3Scheme}
                Errors:
                $errors
            """.trimIndent()
            throw IllegalStateException("Verifikasi APK gagal:\n$details")
        }
    }

    private fun createSelfSignedCertificate(keyPair: java.security.KeyPair): Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60L * 60L * 1000L)
        val notAfter = Date(now + VALIDITY_DAYS * 24L * 60L * 60L * 1000L)
        val signatureAlgorithm = sequence(
            objectIdentifier("1.2.840.113549.1.1.11"),
            der(0x05, byteArrayOf()),
        )
        val distinguishedName = sequence(
            set(
                sequence(
                    objectIdentifier("2.5.4.3"),
                    der(0x0c, "dyl4n".toByteArray(StandardCharsets.UTF_8)),
                ),
            ),
        )
        val validity = sequence(derTime(notBefore), derTime(notAfter))
        val tbsCertificate = sequence(
            integer(BigInteger(160, SecureRandom()).abs().add(BigInteger.ONE)),
            signatureAlgorithm,
            distinguishedName,
            validity,
            distinguishedName,
            keyPair.public.encoded,
        )

        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(tbsCertificate)
        val certificateBytes = sequence(
            tbsCertificate,
            signatureAlgorithm,
            der(0x03, byteArrayOf(0) + signer.sign()),
        )

        return CertificateFactory.getInstance("X.509")
            .generateCertificate(certificateBytes.inputStream())
    }

    private fun derTime(date: Date): ByteArray {
        val calendarYear = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            time = date
        }.get(java.util.Calendar.YEAR)
        val generalized = calendarYear !in 1950..2049
        val format = if (generalized) "yyyyMMddHHmmss'Z'" else "yyMMddHHmmss'Z'"
        val tag = if (generalized) 0x18 else 0x17
        val formatter = SimpleDateFormat(format, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return der(tag, formatter.format(date).toByteArray(StandardCharsets.US_ASCII))
    }

    private fun integer(value: BigInteger): ByteArray = der(0x02, value.toByteArray())

    private fun objectIdentifier(value: String): ByteArray {
        val components = value.split('.').map(String::toLong)
        require(components.size >= 2 && components[0] in 0..2)
        require(components[0] == 2L || components[1] in 0..39)
        val encoded = ArrayList<Byte>()
        encoded += encodeBase128(components[0] * 40 + components[1])
        components.drop(2).forEach { encoded += encodeBase128(it) }
        return der(0x06, encoded.toByteArray())
    }

    private fun encodeBase128(value: Long): List<Byte> {
        require(value >= 0)
        val result = ArrayList<Byte>()
        var remaining = value
        result.add((remaining and 0x7f).toByte())
        remaining = remaining ushr 7
        while (remaining > 0) {
            result.add(0, ((remaining and 0x7f) or 0x80).toByte())
            remaining = remaining ushr 7
        }
        return result
    }

    private fun sequence(vararg values: ByteArray): ByteArray = der(0x30, values.concat())

    private fun set(vararg values: ByteArray): ByteArray = der(0x31, values.concat())

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val lengthBytes = when {
            content.size < 0x80 -> byteArrayOf(content.size.toByte())
            else -> {
                var remaining = content.size
                val encodedLength = ArrayList<Byte>()
                while (remaining > 0) {
                    encodedLength.add(0, (remaining and 0xff).toByte())
                    remaining = remaining ushr 8
                }
                byteArrayOf((0x80 or encodedLength.size).toByte()) + encodedLength.toByteArray()
            }
        }
        return byteArrayOf(tag.toByte()) + lengthBytes + content
    }

    private fun Array<out ByteArray>.concat(): ByteArray {
        val size = sumOf { it.size }
        val result = ByteArray(size)
        var offset = 0
        forEach { value ->
            value.copyInto(result, offset)
            offset += value.size
        }
        return result
    }
}
