package me.ash.reader.ui.component.webview

import android.content.Context
import android.net.http.SslError
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import me.ash.reader.ui.ext.isUrl
import java.io.FilterInputStream
import me.ash.reader.infrastructure.di.cachingHttpClient
import me.ash.reader.infrastructure.di.withServerTrust
import okhttp3.Request

const val INJECTION_TOKEN = "/android_asset_font/"

class WebViewClient(
    private val context: Context,
    private val refererDomain: String?,
    private val onOpenLink: (url: String) -> Unit,
) : WebViewClient() {
    private val client by lazy { cachingHttpClient(context) }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        val url = request?.url?.toString()
        if (url != null && url.contains(INJECTION_TOKEN)) {
            try {
                val assetPath = url.substring(
                    url.indexOf(INJECTION_TOKEN) + INJECTION_TOKEN.length,
                    url.length
                )
                return WebResourceResponse(
                    "text/HTML",
                    "UTF-8",
                    context.assets.open(assetPath)
                )
            } catch (e: Exception) {
                Log.e("RLog", "WebView shouldInterceptRequest: $e")
            }
        } else if (url != null && url.isUrl()) {
            try {
                if (request.method != "GET") return blockedResponse()
                val serverClient = client.withServerTrust(context, url)
                val builder = Request.Builder().url(url)
                var response = serverClient.newCall(builder.build()).execute()
                if (response.code == 403 && refererDomain != null) {
                    response.close()
                    response = serverClient.newCall(builder.header("Referer", refererDomain).build()).execute()
                }
                // Redirects have already been checked by the same policy as RSS/API traffic.
                val body = response.body ?: run { response.close(); return blockedResponse() }
                val type = body.contentType()
                val stream = object : FilterInputStream(body.byteStream()) {
                    override fun close() { try { super.close() } finally { response.close() } }
                }
                return WebResourceResponse(
                    type?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream",
                    type?.charset()?.name() ?: "UTF-8", response.code,
                    response.message.ifEmpty { "Response" }, response.headers.toMap(), stream,
                )
            } catch (e: Exception) {
                Log.w("RLog", "WebView resource request failed: ${e.javaClass.simpleName}")
                return blockedResponse()
            }
        }
        return super.shouldInterceptRequest(view, request)
    }

    private fun blockedResponse() = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Resource blocked", emptyMap(), byteArrayOf().inputStream(),
    )

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view!!.evaluateJavascript(OnImgClickScript, null)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (null == request?.url) return false
        val url = request.url.toString()
        if (url.isNotEmpty()) onOpenLink(url)
        return true
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        Log.e("RLog", "RYWebView onReceivedError: $error")
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        handler?.cancel()
    }

    companion object {
        private const val OnImgClickScript = """
            javascript:(function() {
                var imgs = document.getElementsByTagName("img");
                for(var i = 0; i < imgs.length; i++){
                    imgs[i].pos = i;
                    imgs[i].onclick = function(event) {
                        event.preventDefault();
                        window.${JavaScriptInterface.NAME}.onImgTagClick(this.src, this.alt);
                    }
                }
            })()
            """
    }
}
