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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import nl.jan.airco.AircoViewModel
import nl.jan.airco.R
import nl.jan.airco.UiState
import nl.jan.airco.data.Auth

@Composable
fun LoginScreen(state: UiState, vm: AircoViewModel) {
    if (state.showWebLogin) WebLogin(state, vm) else LoginForm(state, vm)
}

@Composable
private fun LoginForm(state: UiState, vm: AircoViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val canSubmit = email.isNotBlank() && password.isNotEmpty() && !state.loading
    val submit = {
        if (canSubmit) {
            focus.clearFocus()
            vm.login(email, password)
        }
    }

    // Buitenste Box centreert; de Column scrollt alleen als het toetsenbord ruimte inneemt.
    Box(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(R.drawable.ic_launcher), contentDescription = null, modifier = Modifier.size(88.dp))
            Spacer(Modifier.height(20.dp))
            Text("Airco", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Log in met je MELCloud Home-account",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("E-mailadres") },
                singleLine = true,
                enabled = !state.loading,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Wachtwoord") },
                singleLine = true,
                enabled = !state.loading,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "Verberg" else "Toon")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )

            state.message?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { submit() },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Inloggen", style = MaterialTheme.typography.titleMedium)
                }
            }

            if (state.webLoginOffered) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { vm.showWebLogin(true) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Inloggen via MELCloud-pagina")
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Je gegevens worden versleuteld op dit toestel bewaard, zodat de app automatisch ingelogd blijft.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Reserve: de echte MELCloud-loginpagina. Alleen bereikbaar als het
 * formulier technisch faalt. Zo blijf je ook dan in je app kunnen.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebLogin(state: UiState, vm: AircoViewModel) {
    LaunchedEffect(Unit) { vm.prepareLogin() }
    var pageLoading by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.showWebLogin(false) }) { Text("← Terug") }
            Text("MELCloud-loginpagina", style = MaterialTheme.typography.titleMedium)
        }
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

        key(url) {
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
