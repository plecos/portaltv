package com.portal.tv.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Portal's own connection to the TV's debug service, so setup can do what scripts/install.sh does without a
 * computer. The key and certificate are made once and kept in the app's private files; the TV remembers them.
 */
class PortalAdb private constructor(context: Context) : AbsAdbConnectionManager() {

    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val keyFile = File(context.filesDir, "adb_key.pk8")
        val certFile = File(context.filesDir, "adb_cert.der")
        val stored = if (keyFile.exists() && certFile.exists()) {
            runCatching {
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream())
                key to cert
            }.getOrNull()
        } else null
        if (stored != null) {
            privateKey = stored.first
            certificate = stored.second
        } else {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
            val pair = generator.generateKeyPair()
            val cert = AdbCertificate.create(pair)
            keyFile.writeBytes(pair.private.encoded)
            certFile.writeBytes(cert.encoded)
            privateKey = pair.private
            certificate = cert
        }
    }

    override fun getPrivateKey(): PrivateKey = privateKey
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = "Portal"

    companion object {
        @Volatile private var instance: PortalAdb? = null

        fun get(context: Context): PortalAdb = instance ?: synchronized(this) {
            instance ?: PortalAdb(context.applicationContext).also { instance = it }
        }
    }
}
