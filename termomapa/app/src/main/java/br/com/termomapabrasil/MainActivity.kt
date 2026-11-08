package br.com.termomapabrasil

import android.content.Intent
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewAssetLoader

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<android.net.Uri>>? = null
    private val filesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val selected = if (result.resultCode == RESULT_OK) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else null
        fileCallback?.onReceiveValue(selected)
        fileCallback = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(9, 27, 48)
        window.navigationBarColor = android.graphics.Color.rgb(9, 27, 48)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        webView = WebView(this).also { v ->
            v.layoutParams = ViewGroup.LayoutParams(-1, -1)
            v.settings.javaScriptEnabled = true
            v.settings.domStorageEnabled = true
            v.settings.cacheMode = WebSettings.LOAD_DEFAULT
            v.settings.allowFileAccess = false
            v.settings.allowContentAccess = true
            v.webChromeClient = object: WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView?,
                    filePathCallback: ValueCallback<Array<android.net.Uri>>?,
                    fileChooserParams: WebChromeClient.FileChooserParams?
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = filePathCallback
                    return try {
                        val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "application/zip"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                        filesLauncher.launch(intent)
                        true
                    } catch(e: Exception) {
                        fileCallback?.onReceiveValue(null)
                        fileCallback = null
                        false
                    }
                }
            }
            v.webViewClient = object: WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val uri = request?.url ?: return null
                    return loader.shouldInterceptRequest(uri)
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val u = request?.url ?: return true
                    if (u.host == "appassets.androidplatform.net") return false
                    try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                    return true
                }
            }
            setContentView(v)
            v.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        }
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
