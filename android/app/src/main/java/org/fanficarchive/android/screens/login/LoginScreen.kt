package org.fanficarchive.android.screens.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.fanficarchive.android.data.ArchiveRepository
import org.fanficarchive.android.data.ServerInfo
import org.fanficarchive.android.screens.components.ButtonLabel
import org.fanficarchive.android.screens.components.PrimaryButton
import org.fanficarchive.android.screens.components.SecondaryButton

@Composable
fun LoginScreen(
    repository: ArchiveRepository,
    siteName: String,
    baseUrl: String,
    serverInfo: ServerInfo?,
    onLoggedIn: () -> Unit,
    onChangeServer: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf(serverInfo) }
    var browserMode by remember { mutableStateOf<BrowserLoginMode?>(null) }
    val scope = rememberCoroutineScope()
    val localAvailable = info?.auth?.local == true
    val oidcAvailable = info?.auth?.oidc == true
    val providerName = info?.auth?.oidcProviderName?.takeIf { it.isNotBlank() } ?: "SSO"

    LaunchedEffect(serverInfo) {
        info = serverInfo ?: runCatching { repository.refreshInfo() }.getOrNull()
    }

    if (browserMode != null) {
        BrowserLoginScreen(
            repository = repository,
            baseUrl = baseUrl,
            preferSso = browserMode == BrowserLoginMode.Sso,
            title = if (browserMode == BrowserLoginMode.Sso) {
                "Sign in with $providerName"
            } else {
                "Website sign-in"
            },
            onCancel = { browserMode = null },
            onLoggedIn = onLoggedIn,
            onError = { message ->
                error = message
                browserMode = null
            },
        )
        return
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(siteName.ifBlank { "Fanfic Archive" }, style = MaterialTheme.typography.headlineMedium)
            Text(
                when {
                    oidcAvailable && localAvailable ->
                        "Sign in with $providerName, or use a local username and password."
                    oidcAvailable ->
                        "Sign in with $providerName (opens your identity provider)."
                    else ->
                        "Sign in with a local username and password."
                },
                style = MaterialTheme.typography.bodyLarge,
            )

            if (oidcAvailable) {
                PrimaryButton(
                    onClick = { error = null; browserMode = BrowserLoginMode.Sso },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ButtonLabel("Sign in with $providerName")
                }
                SecondaryButton(
                    onClick = { error = null; browserMode = BrowserLoginMode.WebLogin },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ButtonLabel("Open website sign-in page")
                }
            }

            if (localAvailable) {
                if (oidcAvailable) {
                    Text("Or use a local account", style = MaterialTheme.typography.titleMedium)
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; error = null },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryButton(
                    onClick = {
                        scope.launch {
                            loading = true
                            error = null
                            runCatching { repository.login(username, password) }
                                .onSuccess { onLoggedIn() }
                                .onFailure { error = repository.errorMessage(it) }
                            loading = false
                        }
                    },
                    enabled = username.isNotBlank() && password.isNotBlank() && !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ButtonLabel(if (loading) "Signing in…" else "Sign in")
                }
            } else if (!oidcAvailable) {
                Text(
                    "This server has no local accounts and SSO is not configured. " +
                        "Ask an admin to enable sign-in, or open the website sign-in page.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                SecondaryButton(
                    onClick = { error = null; browserMode = BrowserLoginMode.WebLogin },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ButtonLabel("Open website sign-in page")
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            SecondaryButton(onClick = onChangeServer, modifier = Modifier.fillMaxWidth()) {
                ButtonLabel("Change server")
            }
        }
    }
}

private enum class BrowserLoginMode { Sso, WebLogin }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserLoginScreen(
    repository: ArchiveRepository,
    baseUrl: String,
    preferSso: Boolean,
    title: String,
    onCancel: () -> Unit,
    onLoggedIn: () -> Unit,
    onError: (String) -> Unit,
) {
    var startUrl by remember { mutableStateOf<String?>(null) }
    var loadingPage by remember { mutableStateOf(true) }
    var finishing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val archiveHost = remember(baseUrl) {
        (baseUrl.trimEnd('/') + "/").toHttpUrlOrNull()?.host
    }

    LaunchedEffect(preferSso) {
        runCatching { repository.browserLoginUrl(preferSso) }
            .onSuccess { startUrl = it }
            .onFailure { onError(repository.errorMessage(it)) }
    }

    fun maybeFinish(url: String) {
        if (finishing || archiveHost == null) return
        val parsed = url.toHttpUrlOrNull() ?: return
        if (!parsed.host.equals(archiveHost, ignoreCase = true)) return
        val path = parsed.encodedPath
        if (path.startsWith("/login") || path.startsWith("/auth") || path.startsWith("/setup")) return
        val cookieHeader = CookieManager.getInstance().getCookie(baseUrl.trimEnd('/') + "/") ?: return
        if (!cookieHeader.contains("session=")) return

        finishing = true
        scope.launch {
            runCatching { repository.completeBrowserLogin() }
                .onSuccess { onLoggedIn() }
                .onFailure {
                    finishing = false
                    onError(repository.errorMessage(it))
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (loadingPage || finishing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (finishing) {
                Text(
                    "Finishing sign-in…",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val url = startUrl
            if (url != null) {
                BrowserWebView(
                    url = url,
                    onLoadingChanged = { loadingPage = it },
                    onUrl = ::maybeFinish,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserWebView(
    url: String,
    onLoadingChanged: (Boolean) -> Unit,
    onUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        onLoadingChanged(true)
                        url?.let(onUrl)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        onLoadingChanged(false)
                        CookieManager.getInstance().flush()
                        url?.let(onUrl)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        request?.url?.toString()?.let(onUrl)
                        return false
                    }
                }
                loadUrl(url)
            }
        },
        update = { webView ->
            if (webView.url != url) {
                webView.loadUrl(url)
            }
        },
    )
}
