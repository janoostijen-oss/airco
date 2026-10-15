package nl.jan.airco.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import nl.jan.airco.AircoViewModel
import nl.jan.airco.UiState
import nl.jan.airco.data.Auth

/**
 * Toont de echte MELCloud Home-loginpagina. Zodra die doorstuurt naar
 * `melcloudhome://?code=…` pakken wij de code af en ruilen hem in voor tokens.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(state: UiState, vm: AircoViewModel) {
    LaunchedEffect(Unit) { vm.prepareLogin() }
    var pageLoading by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Text(
            "Inloggen met je MELCloud Home-account",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        state.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (pageLoading || state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        val url = state.loginUrl
        if (url == null) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                if (state.message != null) {
                    TextButton(onClick = { vm.clearMessage(); vm.prepareLogin() }) { Text("Opnieuw proberen") }
                }
            }
            return@Column
        }

        Box(Modifier.fillMaxSize()) {
            // key(url): bij een nieuwe login-poging een verse WebView.
            androidx.compose.runtime.key(url) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        CookieManager.getInstance().removeAllCookies(null)
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            var handled = false
                            fun intercept(target: String): Boolean {
                                if (!target.startsWith(Auth.REDIRECT_URI.removeSuffix("//"))) return false
                                if (!handled) {
                                    handled = true
                                    val uri = Uri.parse(target)
                                    vm.onLoginRedirect(
                                        code = uri.getQueryParameter("code"),
                                        error = uri.getQueryParameter("error_description")
                                            ?: uri.getQueryParameter("error"),
                                    )
                                }
                                return true
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                    intercept(request.url.toString())

                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                    if (url != null && intercept(url)) {
                                        view.stopLoading()
                                        return
                                    }
                                    pageLoading = true
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    pageLoading = false
                                }
                            }
                            loadUrl(url)
                        }
                    },
                )
            }
        }
    }
}
