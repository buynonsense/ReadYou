/*
 * Feeder: Android RSS reader app
 * https://gitlab.com/spacecowboy/Feeder
 *
 * Copyright (C) 2022  Jonas Kalderstam
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.ash.reader.infrastructure.di

import android.content.Context
import android.security.KeyChain
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import me.ash.reader.BuildConfig
import me.ash.reader.infrastructure.net.CleartextServerInterceptor
import me.ash.reader.infrastructure.net.ServerSecurityStore
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

@Module
@InstallIn(SingletonComponent::class)
object OkHttpClientModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient =
        cachingHttpClient(context, cacheDirectory = context.cacheDir.resolve("http"))
            .newBuilder().addNetworkInterceptor(UserAgentInterceptor).build()
}

fun cachingHttpClient(
    context: Context,
    cacheDirectory: File? = null,
    cacheSize: Long = 10L * 1024L * 1024L,
    connectTimeoutSecs: Long = 30L,
    readTimeoutSecs: Long = 30L,
    clientCertificateAlias: String? = null,
): OkHttpClient {
    val policy = CleartextServerInterceptor(ServerSecurityStore(context))
    return OkHttpClient.Builder().apply {
        if (cacheDirectory != null) cache(Cache(cacheDirectory, cacheSize))
        connectTimeout(connectTimeoutSecs, TimeUnit.SECONDS)
        readTimeout(readTimeoutSecs, TimeUnit.SECONDS)
        followRedirects(true)
        followSslRedirects(false)
        addInterceptor(policy)
        addNetworkInterceptor(policy)
        if (!clientCertificateAlias.isNullOrBlank()) setupSsl(context, clientCertificateAlias)
    }.build()
}

/** Retain normal hostname validation even when the user trusts a private server certificate. */
fun OkHttpClient.withServerTrust(context: Context, url: String, clientCertificateAlias: String? = null): OkHttpClient {
    val store = ServerSecurityStore(context)
    val pem = store.certificatePem(url)
    if (pem == null && clientCertificateAlias.isNullOrBlank()) return this
    val origin = requireNotNull(url.toHttpUrlOrNull())
    val defaultVerifier = hostnameVerifier
    return newBuilder().apply {
        setupSsl(context, clientCertificateAlias, pem?.let(ServerSecurityStore::parseCertificate))
        hostnameVerifier { hostname, session -> hostname == origin.host && defaultVerifier.verify(hostname, session) }
        addNetworkInterceptor { chain ->
            val target = chain.request().url
            if (target.scheme != origin.scheme || target.host != origin.host || target.port != origin.port) {
                throw java.io.IOException("The trusted certificate is restricted to this server's origin")
            }
            chain.proceed(chain.request())
        }
    }.build()
}

internal fun systemTrustManager(): X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
    init(null as KeyStore?)
    trustManagers.filterIsInstance<X509TrustManager>().single()
}

internal class ServerCertificateTrustManager(
    private val system: X509TrustManager,
    private val trusted: X509Certificate,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + trusted
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            val leaf = chain.firstOrNull() ?: throw e
            leaf.checkValidity()
            if (!java.security.MessageDigest.isEqual(leaf.encoded, trusted.encoded)) throw e
        }
    }
}

fun OkHttpClient.Builder.setupSsl(
    context: Context,
    clientCertificateAlias: String?,
    trustedCertificate: X509Certificate? = null,
) {
    val keyManager = clientCertificateAlias?.let { alias ->
        object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<Principal>?) = arrayOf(alias)
            override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?) = alias
            override fun getServerAliases(keyType: String?, issuers: Array<Principal>?) = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?) = null
            override fun getCertificateChain(requested: String?): Array<X509Certificate>? =
                if (requested == alias) KeyChain.getCertificateChain(context, alias) else null
            override fun getPrivateKey(requested: String?): PrivateKey? =
                if (requested == alias) KeyChain.getPrivateKey(context, alias) else null
        }
    }
    val trustManager = systemTrustManager().let { system ->
        trustedCertificate?.let { ServerCertificateTrustManager(system, it) } ?: system
    }
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(keyManager?.let { arrayOf(it) }, arrayOf(trustManager), null)
    }
    sslSocketFactory(sslContext.socketFactory, trustManager)
}

object UserAgentInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(
        chain.request().newBuilder().header("User-Agent", USER_AGENT_STRING).build()
    )
}

const val USER_AGENT_STRING = BuildConfig.USER_AGENT_STRING
