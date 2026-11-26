package com.vidgrab.app

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Persistent device identity for direct transfer: an ECDSA P-256 keypair
 * with a self-signed X.509 certificate, generated on first use and stored
 * in the app's private files directory (not world-readable, excluded from
 * backups via the manifest's backup rules). The private key never leaves
 * the device. The SHA-256 fingerprint of the certificate is what a sender
 * compares against this device's screen before first trust.
 *
 * The platform can do TLS (javax.net.ssl) but cannot BUILD an X.509
 * certificate, so Bouncy Castle is used for certificate construction only.
 */
class Identity private constructor(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {
    val fingerprint: String by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
    }

    /** First 8 hex chars of the fingerprint; stable, public, not a secret. */
    val deviceId: String get() = fingerprint.substring(0, 8)

    /** SSLContext that presents this identity as a TLS server (TLS 1.2+). */
    fun serverSocketFactory(): javax.net.ssl.SSLServerSocketFactory {
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("identity", privateKey, CharArray(0), arrayOf(certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, CharArray(0))
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, SecureRandom())
        return ctx.serverSocketFactory
    }

    companion object {
        private const val KEY_FILE = "identity.key"
        private const val CERT_FILE = "identity.crt"
        private const val CERT_DAYS = 3650L

        @Synchronized
        fun loadOrCreate(context: Context): Identity {
            val dir = context.filesDir
            val keyFile = File(dir, KEY_FILE)
            val certFile = File(dir, CERT_FILE)
            if (keyFile.exists() && certFile.exists()) {
                try {
                    return load(keyFile.readBytes(), certFile.readBytes())
                } catch (_: Exception) {
                    // Corrupt store: regenerate rather than crash.
                }
            }
            val created = create()
            keyFile.writeBytes(created.privateKey.encoded)
            certFile.writeBytes(created.certificate.encoded)
            return created
        }

        private fun load(keyBytes: ByteArray, certBytes: ByteArray): Identity {
            val kf = KeyFactory.getInstance("EC")
            val key = kf.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            val cert = java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(certBytes.inputStream()) as X509Certificate
            // Sanity: the stored key must match the stored certificate.
            val pub = kf.generatePublic(X509EncodedKeySpec(cert.publicKey.encoded))
            check(pub.encoded.contentEquals(cert.publicKey.encoded))
            return Identity(key, cert)
        }

        private fun create(): Identity {
            val gen = KeyPairGenerator.getInstance("EC")
            gen.initialize(java.security.spec.ECGenParameterSpec("secp256r1"), SecureRandom())
            val pair = gen.generateKeyPair()
            val now = System.currentTimeMillis()
            val subject = X500Name("CN=VidGrab, O=VidGrab")
            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger(160, SecureRandom()),
                Date(now - 60_000),
                Date(now + CERT_DAYS * 24 * 60 * 60 * 1000),
                subject,
                pair.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)
            val cert = JcaX509CertificateConverter()
                .getCertificate(builder.build(signer))
            return Identity(pair.private, cert)
        }
    }
}
