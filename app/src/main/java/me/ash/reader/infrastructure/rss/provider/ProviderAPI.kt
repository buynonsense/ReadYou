package me.ash.reader.infrastructure.rss.provider

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import me.ash.reader.infrastructure.di.UserAgentInterceptor
import me.ash.reader.infrastructure.di.cachingHttpClient
import me.ash.reader.infrastructure.di.withServerTrust
import okhttp3.OkHttpClient

abstract class ProviderAPI(
    private val context: Context,
    private val clientCertificateAlias: String?,
    private val serverUrl: String,
) {

    private val baseClient: OkHttpClient = cachingHttpClient(
        context = context,
        clientCertificateAlias = clientCertificateAlias,
    )
        .newBuilder()
        .addNetworkInterceptor(UserAgentInterceptor)
        .build()

    protected val client: OkHttpClient
        get() = baseClient.withServerTrust(context, serverUrl, clientCertificateAlias)

    protected val gson: Gson = GsonBuilder().create()

    protected inline fun <reified T> toDTO(jsonStr: String): T =
        gson.fromJson(jsonStr, T::class.java)!!
}
