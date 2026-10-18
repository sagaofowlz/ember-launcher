package dev.sagaowl.emberlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private val io = Executors.newCachedThreadPool()
    private val prefs by lazy { getSharedPreferences("ember_launcher", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(12, 15, 13)
        window.navigationBarColor = Color.rgb(12, 15, 13)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(12, 15, 13))
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(LauncherBridge(), "Ember")
        }
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("Ember")
            webView.destroy()
        }
        io.shutdownNow()
        super.onDestroy()
    }

    private fun sendEvent(type: String, payload: JSONObject = JSONObject()) {
        val envelope = JSONObject().put("type", type).put("payload", payload)
        val quoted = JSONObject.quote(envelope.toString())
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript("window.onNativeEvent && window.onNativeEvent($quoted)", null)
            }
        }
    }

    private fun saveProfile(name: String, uuid: String, provider: String) {
        prefs.edit()
            .putString("profile_name", name)
            .putString("profile_uuid", uuid)
            .putString("profile_provider", provider)
            .apply()
    }

    private fun startupData(): JSONObject {
        val profile = JSONObject()
        val name = prefs.getString("profile_name", null)
        if (!name.isNullOrBlank()) {
            profile.put("name", name)
                .put("uuid", prefs.getString("profile_uuid", ""))
                .put("provider", prefs.getString("profile_provider", "Offline"))
        }
        val settings = JSONObject()
        for ((key, value) in prefs.all) {
            if (key.startsWith("setting_") && value is String) {
                settings.put(key.removePrefix("setting_"), value)
            }
        }
        return JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("profile", if (profile.length() == 0) JSONObject.NULL else profile)
            .put("settings", settings)
            .put("library", libraryStats())
    }

    private fun libraryDir(kind: String): File {
        val leaf = if (kind == "shader") "shaderpacks" else "mods"
        return File(File(filesDir, "ember-library"), leaf).apply { mkdirs() }
    }

    private fun libraryStats(): JSONObject {
        fun count(dir: File) = (dir.listFiles()?.count { it.isFile } ?: 0)
        return JSONObject()
            .put("mods", count(libraryDir("mod")))
            .put("shaders", count(libraryDir("shader")))
    }

    private inner class LauncherBridge {
        @JavascriptInterface
        fun getStartupData(): String = startupData().toString()

        @JavascriptInterface
        fun saveSetting(key: String, value: String) {
            val allowed = setOf("mcVersion", "loader", "memory", "touchScale", "msClientId")
            if (key in allowed) prefs.edit().putString("setting_$key", value.take(80)).apply()
        }

        @JavascriptInterface
        fun saveOfflineProfile(rawName: String) {
            val name = rawName.trim()
            if (!name.matches(Regex("[A-Za-z0-9_]{3,16}"))) {
                sendEvent("auth-error", JSONObject().put("provider", "Offline").put("message", "Use a 3–16 character Minecraft name with letters, numbers, or underscores."))
                return
            }
            val id = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(StandardCharsets.UTF_8))
                .toString().replace("-", "")
            saveProfile(name, id, "Offline")
            sendEvent("auth-success", JSONObject().put("provider", "Offline").put("name", name).put("uuid", id))
        }

        @JavascriptInterface
        fun searchModrinth(rawQuery: String, kind: String, mcVersion: String, loader: String) {
            val cleanKind = if (kind == "shader") "shader" else "mod"
            io.execute {
                try {
                    sendEvent("catalog-loading", JSONObject().put("kind", cleanKind))
                    val facets = JSONArray().put(JSONArray().put("project_type:$cleanKind"))
                    if (mcVersion.isNotBlank()) facets.put(JSONArray().put("versions:$mcVersion"))
                    if (cleanKind == "mod" && loader in setOf("fabric", "forge", "neoforge", "quilt")) {
                        facets.put(JSONArray().put("categories:$loader"))
                    }
                    val params = mutableListOf("facets=" + enc(facets.toString()), "limit=20", "index=relevance")
                    if (rawQuery.isNotBlank()) params.add("query=" + enc(rawQuery.trim().take(100)))
                    val result = getJson("https://api.modrinth.com/v2/search?" + params.joinToString("&"))
                    val hits = result.optJSONArray("hits") ?: JSONArray()
                    val cleaned = JSONArray()
                    for (i in 0 until hits.length()) {
                        val item = hits.optJSONObject(i) ?: continue
                        cleaned.put(JSONObject()
                            .put("id", item.optString("project_id"))
                            .put("slug", item.optString("slug"))
                            .put("title", item.optString("title", "Untitled project"))
                            .put("description", item.optString("description"))
                            .put("icon", item.optString("icon_url"))
                            .put("downloads", item.optLong("downloads", 0)))
                    }
                    sendEvent("catalog-results", JSONObject().put("kind", cleanKind).put("results", cleaned))
                } catch (e: Exception) {
                    sendEvent("catalog-error", JSONObject().put("kind", cleanKind).put("message", friendlyError(e)))
                }
            }
        }

        @JavascriptInterface
        fun installModrinth(projectId: String, kind: String, mcVersion: String, loader: String) {
            val cleanKind = if (kind == "shader") "shader" else "mod"
            if (!projectId.matches(Regex("[A-Za-z0-9]{5,64}"))) {
                sendEvent("download-result", JSONObject().put("success", false).put("message", "Invalid Modrinth project ID."))
                return
            }
            io.execute {
                try {
                    sendEvent("download-started", JSONObject().put("kind", cleanKind))
                    val params = mutableListOf("game_versions=" + enc(JSONArray().put(mcVersion).toString()))
                    if (cleanKind == "mod" && loader in setOf("fabric", "forge", "neoforge", "quilt")) {
                        params.add("loaders=" + enc(JSONArray().put(loader).toString()))
                    }
                    val versions = getArray("https://api.modrinth.com/v2/project/$projectId/version?" + params.joinToString("&"))
                    if (versions.length() == 0) throw IllegalStateException("No compatible files were found for Minecraft $mcVersion. Try another version or loader.")
                    var chosen: JSONObject? = null
                    var selectedFile: JSONObject? = null
                    for (i in 0 until versions.length()) {
                        val version = versions.optJSONObject(i) ?: continue
                        val files = version.optJSONArray("files") ?: continue
                        if (files.length() == 0) continue
                        var primary: JSONObject? = null
                        for (j in 0 until files.length()) {
                            val file = files.optJSONObject(j) ?: continue
                            if (file.optBoolean("primary")) { primary = file; break }
                        }
                        chosen = version
                        selectedFile = primary ?: files.optJSONObject(0)
                        if (selectedFile != null) break
                    }
                    val file = selectedFile ?: throw IllegalStateException("Modrinth did not return a downloadable file.")
                    val filename = file.optString("filename", if (cleanKind == "shader") "shaderpack.zip" else "mod.jar")
                    val url = file.optString("url")
                    val saved = downloadToLibrary(url, filename, cleanKind)
                    sendEvent("download-result", JSONObject()
                        .put("success", true)
                        .put("kind", cleanKind)
                        .put("filename", saved.name)
                        .put("version", chosen?.optString("version_number", "") ?: "")
                        .put("message", "Saved ${saved.name} to Ember's ${if (cleanKind == "shader") "shaderpack" else "mod"} library. The game runtime is not connected yet.")
                        .put("library", libraryStats()))
                } catch (e: Exception) {
                    sendEvent("download-result", JSONObject().put("success", false).put("kind", cleanKind).put("message", friendlyError(e)))
                }
            }
        }

        @JavascriptInterface
        fun beginMicrosoftLogin(rawClientId: String) {
            val clientId = rawClientId.trim()
            if (!clientId.matches(Regex("[0-9a-fA-F-]{20,50}"))) {
                sendEvent("auth-error", JSONObject().put("provider", "Microsoft").put("message", "Enter the Client ID from your Microsoft Entra app registration first."))
                return
            }
            io.execute {
                try {
                    sendEvent("auth-progress", JSONObject().put("provider", "Microsoft").put("message", "Requesting a Microsoft device code…"))
                    val device = postForm("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode", mapOf(
                        "client_id" to clientId,
                        "scope" to "XboxLive.signin offline_access"
                    ))
                    val deviceCode = device.getString("device_code")
                    val userCode = device.getString("user_code")
                    val verifyUri = device.optString("verification_uri", "https://microsoft.com/devicelogin")
                    val interval = device.optInt("interval", 5).coerceAtLeast(2)
                    val expiresAt = System.currentTimeMillis() + device.optLong("expires_in", 900) * 1000
                    sendEvent("microsoft-code", JSONObject()
                        .put("userCode", userCode)
                        .put("verificationUri", verifyUri)
                        .put("message", device.optString("message", "Open the verification page and enter this code.")))
                    var aadToken: String? = null
                    while (System.currentTimeMillis() < expiresAt && aadToken == null) {
                        Thread.sleep(interval * 1000L)
                        try {
                            val token = postForm("https://login.microsoftonline.com/consumers/oauth2/v2.0/token", mapOf(
                                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                                "client_id" to clientId,
                                "device_code" to deviceCode
                            ))
                            aadToken = token.optString("access_token").takeIf { it.isNotBlank() }
                            if (aadToken == null) throw ApiException(token.optString("error", "token_error"), token.optString("error_description", "Could not complete Microsoft login."))
                        } catch (e: ApiException) {
                            when (e.code) {
                                "authorization_pending" -> continue
                                "slow_down" -> { Thread.sleep(5000); continue }
                                else -> throw e
                            }
                        }
                    }
                    val microsoftToken = aadToken ?: throw IllegalStateException("The Microsoft sign-in code expired. Please try again.")
                    sendEvent("auth-progress", JSONObject().put("provider", "Microsoft").put("message", "Verifying Xbox and Minecraft ownership…"))
                    val xbl = postJson("https://user.auth.xboxlive.com/user/authenticate", JSONObject()
                        .put("Properties", JSONObject().put("AuthMethod", "RPS").put("SiteName", "user.auth.xboxlive.com").put("RpsTicket", "d=$microsoftToken"))
                        .put("RelyingParty", "http://auth.xboxlive.com")
                        .put("TokenType", "JWT"))
                    val xblToken = xbl.getString("Token")
                    val uhs = xbl.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs")
                    val xsts = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", JSONObject()
                        .put("Properties", JSONObject().put("SandboxId", "RETAIL").put("UserTokens", JSONArray().put(xblToken)))
                        .put("RelyingParty", "rp://api.minecraftservices.com/")
                        .put("TokenType", "JWT"))
                    val xstsToken = xsts.getString("Token")
                    val mcLogin = postJson("https://api.minecraftservices.com/authentication/login_with_xbox", JSONObject()
                        .put("identityToken", "XBL3.0 x=$uhs;$xstsToken"))
                    val mcToken = mcLogin.getString("access_token")
                    val entitlements = getJson("https://api.minecraftservices.com/entitlements/mcstore", mcToken)
                    if ((entitlements.optJSONArray("items")?.length() ?: 0) == 0) {
                        throw IllegalStateException("This Microsoft account did not return Minecraft Java entitlements. Make sure it owns Minecraft: Java Edition.")
                    }
                    val profile = getJson("https://api.minecraftservices.com/minecraft/profile", mcToken)
                    val name = profile.getString("name")
                    val uuid = profile.getString("id")
                    // Auth tokens are deliberately not persisted; only the profile display data is saved.
                    saveProfile(name, uuid, "Microsoft")
                    sendEvent("auth-success", JSONObject().put("provider", "Microsoft").put("name", name).put("uuid", uuid))
                } catch (e: Exception) {
                    sendEvent("auth-error", JSONObject().put("provider", "Microsoft").put("message", friendlyError(e)))
                }
            }
        }

        @JavascriptInterface
        fun loginElyBy(username: String, password: String) {
            val cleanUser = username.trim()
            if (cleanUser.isBlank() || password.isBlank()) {
                sendEvent("auth-error", JSONObject().put("provider", "Ely.by").put("message", "Enter your Ely.by username/email and password."))
                return
            }
            io.execute {
                try {
                    sendEvent("auth-progress", JSONObject().put("provider", "Ely.by").put("message", "Authenticating directly with Ely.by over HTTPS…"))
                    val clientToken = prefs.getString("ely_client_token", null) ?: UUID.randomUUID().toString().also {
                        prefs.edit().putString("ely_client_token", it).apply()
                    }
                    val body = JSONObject()
                        .put("agent", JSONObject().put("name", "Minecraft").put("version", 1))
                        .put("username", cleanUser)
                        .put("password", password)
                        .put("clientToken", clientToken)
                        .put("requestUser", true)
                    val result = postJson("https://authserver.ely.by/authenticate", body)
                    val profile = result.getJSONObject("selectedProfile")
                    val name = profile.getString("name")
                    val id = profile.getString("id")
                    // Password and access token stay in memory only and are never written to preferences.
                    saveProfile(name, id, "Ely.by")
                    sendEvent("auth-success", JSONObject().put("provider", "Ely.by").put("name", name).put("uuid", id))
                } catch (e: Exception) {
                    sendEvent("auth-error", JSONObject().put("provider", "Ely.by").put("message", friendlyError(e)))
                }
            }
        }

        @JavascriptInterface
        fun openExternal(rawUrl: String) {
            try {
                val uri = Uri.parse(rawUrl)
                if (uri.scheme != "https" || uri.host.isNullOrBlank()) return
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: Exception) {
                sendEvent("notice", JSONObject().put("message", "Could not open that link."))
            }
        }
    }

    private fun downloadToLibrary(rawUrl: String, rawFilename: String, kind: String): File {
        var current = URL(rawUrl)
        var connection: HttpURLConnection? = null
        try {
            repeat(6) {
                requireAllowedCdn(current)
                connection = (current.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "EmberLauncher/${BuildConfig.VERSION_NAME}")
                }
                val response = connection!!.responseCode
                if (response in 300..399) {
                    val location = connection!!.getHeaderField("Location") ?: throw IllegalStateException("Modrinth returned a redirect without a location.")
                    current = URL(current, location)
                    connection!!.disconnect()
                    connection = null
                    return@repeat
                }
                if (response !in 200..299) throw IllegalStateException("File download failed (HTTP $response).")
                val reportedSize = connection!!.contentLengthLong
                if (reportedSize > 256L * 1024 * 1024) throw IllegalStateException("This file is larger than the 256 MB safety limit.")
                val safeName = rawFilename.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(180).trim().ifBlank { if (kind == "shader") "shaderpack.zip" else "mod.jar" }
                val expectedExt = if (kind == "shader") ".zip" else ".jar"
                if (!safeName.lowercase().endsWith(expectedExt)) throw IllegalStateException("The selected file is not a $expectedExt file.")
                val targetDir = libraryDir(kind)
                var target = File(targetDir, safeName)
                if (target.exists()) target = File(targetDir, safeName.removeSuffix(expectedExt) + "-" + System.currentTimeMillis().toString().takeLast(6) + expectedExt)
                var total = 0L
                BufferedInputStream(connection!!.inputStream).use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > 256L * 1024 * 1024) {
                                target.delete()
                                throw IllegalStateException("This file is larger than the 256 MB safety limit.")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                return target
            }
            throw IllegalStateException("Too many redirects while downloading the file.")
        } finally {
            connection?.disconnect()
        }
    }

    private fun requireAllowedCdn(url: URL) {
        val allowed = setOf("cdn.modrinth.com", "cdn-raw.modrinth.com")
        if (url.protocol != "https" || url.host.lowercase() !in allowed) {
            throw IllegalArgumentException("For safety, mod downloads must come directly from a Modrinth CDN host.")
        }
    }

    private class ApiException(val code: String, override val message: String) : Exception(message)

    private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8")

    private fun getJson(rawUrl: String, bearer: String? = null): JSONObject = requestJson("GET", rawUrl, null, bearer)
    private fun getArray(rawUrl: String): JSONArray = requestArray("GET", rawUrl, null)
    private fun postJson(rawUrl: String, body: JSONObject): JSONObject = requestJson("POST", rawUrl, body.toString(), null, "application/json; charset=utf-8")

    private fun postForm(rawUrl: String, fields: Map<String, String>): JSONObject {
        val form = fields.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        return requestJson("POST", rawUrl, form, null, "application/x-www-form-urlencoded; charset=utf-8")
    }

    private fun requestJson(
        method: String,
        rawUrl: String,
        body: String?,
        bearer: String?,
        contentType: String = "application/json; charset=utf-8"
    ): JSONObject {
        val text = requestText(method, rawUrl, body, bearer, contentType)
        val json = try { JSONObject(text) } catch (_: Exception) { throw IllegalStateException("The server returned an unreadable response.") }
        val error = json.optString("error")
        if (error.isNotBlank()) throw ApiException(error, json.optString("error_description", json.optString("errorMessage", json.optString("message", error))))
        return json
    }

    private fun requestArray(method: String, rawUrl: String, body: String?): JSONArray {
        val text = requestText(method, rawUrl, body, null, "application/json; charset=utf-8")
        return try { JSONArray(text) } catch (_: Exception) { throw IllegalStateException("The server returned an unreadable list.") }
    }

    private fun requestText(method: String, rawUrl: String, body: String?, bearer: String?, contentType: String): String {
        val url = URL(rawUrl)
        require(url.protocol == "https") { "Only secure HTTPS requests are allowed." }
        val connection = (url.openConnection() as HttpURLConnection)
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "EmberLauncher/${BuildConfig.VERSION_NAME}")
            if (!bearer.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 4 * 1024 * 1024) throw IllegalStateException("The server response was unexpectedly large.")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            val text = String(bytes, StandardCharsets.UTF_8)
            if (code !in 200..299) {
                val failure = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
                val apiCode = failure.optString("error")
                val detail = failure.optString("error_description", failure.optString("errorMessage", failure.optString("message", "")))
                if (apiCode.isNotBlank()) throw ApiException(apiCode, detail.ifBlank { apiCode })
                throw IllegalStateException("Request failed (HTTP $code)" + if (detail.isNotBlank()) ": $detail" else ".")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun friendlyError(error: Exception): String {
        val message = error.message?.trim().orEmpty()
        return when {
            message.isBlank() -> "Something went wrong. Please try again."
            message.contains("UnknownHostException", true) -> "No network connection. Check your internet and retry."
            message.length > 260 -> message.take(257) + "…"
            else -> message
        }
    }
}
