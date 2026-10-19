package com.aster.app.data.network

import java.io.InputStream
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

data class AsterTlsConfiguration(
    val sslSocketFactory: SSLSocketFactory,
    val trustManager: X509TrustManager
)

class DualTrustManager(
    val customTrustManager: X509TrustManager,
    val systemTrustManager: X509TrustManager
) : X509ExtendedTrustManager() {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        try {
            customTrustManager.checkClientTrusted(chain, authType)
        } catch (_: Exception) {
            systemTrustManager.checkClientTrusted(chain, authType)
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        try {
            customTrustManager.checkServerTrusted(chain, authType)
        } catch (customEx: Exception) {
            try {
                systemTrustManager.checkServerTrusted(chain, authType)
            } catch (_: Exception) {
                throw customEx
            }
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        try {
            if (customTrustManager is X509ExtendedTrustManager) {
                customTrustManager.checkClientTrusted(chain, authType, socket)
            } else {
                customTrustManager.checkClientTrusted(chain, authType)
            }
        } catch (_: Exception) {
            if (systemTrustManager is X509ExtendedTrustManager) {
                systemTrustManager.checkClientTrusted(chain, authType, socket)
            } else {
                systemTrustManager.checkClientTrusted(chain, authType)
            }
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        try {
            if (customTrustManager is X509ExtendedTrustManager) {
                customTrustManager.checkServerTrusted(chain, authType, socket)
            } else {
                customTrustManager.checkServerTrusted(chain, authType)
            }
        } catch (customEx: Exception) {
            try {
                if (systemTrustManager is X509ExtendedTrustManager) {
                    systemTrustManager.checkServerTrusted(chain, authType, socket)
                } else {
                    systemTrustManager.checkServerTrusted(chain, authType)
                }
            } catch (_: Exception) {
                throw customEx
            }
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        try {
            if (customTrustManager is X509ExtendedTrustManager) {
                customTrustManager.checkClientTrusted(chain, authType, engine)
            } else {
                customTrustManager.checkClientTrusted(chain, authType)
            }
        } catch (_: Exception) {
            if (systemTrustManager is X509ExtendedTrustManager) {
                systemTrustManager.checkClientTrusted(chain, authType, engine)
            } else {
                systemTrustManager.checkClientTrusted(chain, authType)
            }
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        try {
            if (customTrustManager is X509ExtendedTrustManager) {
                customTrustManager.checkServerTrusted(chain, authType, engine)
            } else {
                customTrustManager.checkServerTrusted(chain, authType)
            }
        } catch (customEx: Exception) {
            try {
                if (systemTrustManager is X509ExtendedTrustManager) {
                    systemTrustManager.checkServerTrusted(chain, authType, engine)
                } else {
                    systemTrustManager.checkServerTrusted(chain, authType)
                }
            } catch (_: Exception) {
                throw customEx
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        val customIssuers = customTrustManager.acceptedIssuers ?: emptyArray()
        val sysIssuers = systemTrustManager.acceptedIssuers ?: emptyArray()
        return customIssuers + sysIssuers
    }
}

object AsterTlsManager {
    /**
     * Builds SSLSocketFactory and X509TrustManager supporting dual-trust:
     * Aster Root CA (for private local deployments) and System CAs (for public Web PKI / tunnels).
     * Enforces strict X.509 certificate chain validation and OkHttp hostname verification without bypasses.
     */
    fun createTlsConfiguration(caCertificateInputStream: InputStream): AsterTlsConfiguration {
        val certFactory = CertificateFactory.getInstance("X.509")
        val caCert = certFactory.generateCertificate(caCertificateInputStream) as X509Certificate

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("aster_root_ca", caCert)
        }

        val customTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        val customTrustManager = customTmf.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
            ?: throw IllegalStateException("No X509TrustManager found for Aster Root CA")

        val systemTrustManager = try {
            val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(null as KeyStore?)
            }
            systemTmf.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
        } catch (_: Exception) {
            null
        }

        val effectiveTrustManager: X509TrustManager = if (systemTrustManager != null) {
            DualTrustManager(customTrustManager, systemTrustManager)
        } else {
            customTrustManager
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(effectiveTrustManager), null)
        }

        return AsterTlsConfiguration(
            sslSocketFactory = sslContext.socketFactory,
            trustManager = effectiveTrustManager
        )
    }
}
