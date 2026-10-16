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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import nl.jan.airco.data.Mode
import nl.jan.airco.AircoViewModel
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

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Ink.Text,
        unfocusedTextColor = Ink.Text,
        focusedBorderColor = Ink.Accent,
        unfocusedBorderColor = Ink.white(0.12f),
        focusedContainerColor = Ink.white(0.05f),
        unfocusedContainerColor = Ink.white(0.03f),
        disabledContainerColor = Ink.white(0.02f),
        focusedLabelColor = Ink.Accent,
        unfocusedLabelColor = Ink.Muted,
        cursorColor = Ink.Accent,
    )
    val fieldShape = RoundedCornerShape(16.dp)
    val cool = Mode.Cool.style()

    // Buitenste Box centreert; de Column scrollt alleen als het toetsenbord ruimte inneemt.
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.Ground)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        0f to Ink.GroundTop,
                        1f to Ink.Ground,
                        center = Offset(size.width / 2, 0f),
                        radius = size.height * 0.55f,
                    ),
                )
            }
            .safeDrawingPadding()
            .imePadding(),
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
            MiniDial(target = 21.0, range = 16.0..31.0, on = true, style = cool, showTarget = true)
            Spacer(Modifier.height(24.dp))
            Text(
                "Airco",
                fontFamily = Sora,
                fontWeight = FontWeight.SemiBold,
                fontSize = 32.sp,
                letterSpacing = (-0.8).sp,
                color = Ink.Text,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Log in met je MELCloud Home-account",
                fontFamily = Manrope,
                fontSize = 15.sp,
                color = Ink.Muted2,
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
                colors = fieldColors,
                shape = fieldShape,
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
                        Text(if (showPassword) "Verberg" else "Toon", fontFamily = Manrope, color = Ink.IconSoft)
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                colors = fieldColors,
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth(),
            )

            state.message?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    color = Ink.Error,
                    fontFamily = Manrope,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))
            // Knop in de "Koel"-gradient van het ontwerp.
            val btnShape = RoundedCornerShape(16.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .then(if (canSubmit) Modifier.shadow(16.dp, btnShape, ambientColor = cool.c2, spotColor = cool.c2) else Modifier)
                    .clip(btnShape)
                    .background(
                        if (canSubmit || state.loading) Brush.linearGradient(listOf(cool.c1, cool.c2))
                        else Brush.linearGradient(listOf(Ink.white(0.06f), Ink.white(0.06f))),
                    )
                    .clickable(enabled = canSubmit, role = Role.Button, onClick = { submit() }),
                contentAlignment = Alignment.Center,
            ) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text(
                        "Inloggen",
                        fontFamily = Manrope,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = if (canSubmit) Color.White else Ink.Muted,
                    )
                }
            }

            if (state.webLoginOffered) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { vm.showWebLogin(true) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("Inloggen via MELCloud-pagina", fontFamily = Manrope, color = Ink.IconSoft)
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Je gegevens worden versleuteld op dit toestel bewaard, zodat de app automatisch ingelogd blijft.",
                fontFamily = Manrope,
                fontSize = 12.sp,
                color = Ink.Faint,
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
                // Weg uit beeld (ingelogd, terug, nieuwe URL): renderer en geheugen direct vrijgeven.
                onRelease = { web ->
                    web.stopLoading()
                    web.webViewClient = WebViewClient()
                    web.destroy()
                },
            )
        }
    }
}
