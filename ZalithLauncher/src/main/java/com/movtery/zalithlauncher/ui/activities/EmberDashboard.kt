/*
 * Ember hybrid dashboard integration.
 * Copyright (C) 2026 SagaOwl and contributors.
 *
 * This file is part of an unofficial modified build based on Zalith Launcher 2.
 * It is distributed under the GNU General Public License, version 3 or later.
 */
package com.movtery.zalithlauncher.ui.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.movtery.zalithlauncher.BuildConfig
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.AccountType
import com.movtery.zalithlauncher.game.account.AccountsManager
import com.movtery.zalithlauncher.game.account.isLocalAccount
import kotlinx.coroutines.runBlocking
import com.movtery.zalithlauncher.game.path.getGameHome
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.setting.AllSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

@androidx.compose.runtime.Composable
fun EmberDashboard(
    activity: MainActivity,
    onPlay: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenVersions: () -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.rgb(12, 15, 13))
                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.allowFileAccess = true // Required to load the bundled android_asset page.
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.javaScriptCanOpenWindowsAutomatically = false
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                        val uri = request.url
                        if (uri.scheme == "file" && uri.path?.startsWith("/android_asset/ember/") == true) {
                            return false
                        }
                        if (uri.scheme == "https" && !uri.host.isNullOrBlank()) {
                            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            return true
                        }
                        return true
                    }
                }
                addJavascriptInterface(
                    EmberHybridBridge(
                        activity = activity,
                        webView = this,
                        onPlay = onPlay,
                        onOpenAccounts = onOpenAccounts,
                        onOpenVersions = onOpenVersions,
                    ),
                    "Ember"
                )
                loadUrl("file:///android_asset/ember/index.html")
            }
        },
    )
}

/**
 * Native bridge for the Ember HTML/CSS/JS interface. The actual game account,
 * version manager, launch pipeline, and install paths remain owned by Zalith's runtime.
 */
private class EmberHybridBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val onPlay: () -> Unit,
    private val onOpenAccounts: () -> Unit,
    private val onOpenVersions: () -> Unit,
) {
    private val io = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "EmberBridgeIO").apply { isDaemon = true }
    }
    private val prefs = activity.getSharedPreferences("ember_hybrid_ui", Context.MODE_PRIVATE)

    @JavascriptInterface
    fun getStartupData(): String {
        val current = AccountsManager.currentAccountFlow.value
        val profile = current?.let {
            val provider = when {
                it.isLocalAccount() -> "Offline"
                it.accountType == AccountType.MICROSOFT.tag -> "Microsoft"
                else -> it.accountType ?: "Minecraft account"
            }
            JSONObject()
                .put("name", it.username)
                .put("uuid", it.profileId)
                .put("provider", provider)
        }
        val selectedVersion = VersionsManager.currentVersion.value
        val actualMcVersion = selectedVersion?.getVersionInfo()?.minecraftVersion?.takeIf { it.isNotBlank() }
            ?: "1.21.1"
        val settings = JSONObject()
        settings.put("mcVersion", prefs.getString("mcVersion", actualMcVersion) ?: actualMcVersion)
        settings.put("loader", prefs.getString("loader", "fabric") ?: "fabric")
        settings.put("memory", prefs.getString("memory", AllSettings.ramAllocation.getValue()?.toString() ?: "2048") ?: "2048")
        settings.put("touchScale", prefs.getString("touchScale", "100") ?: "100")
        settings.put("msClientId", prefs.getString("msClientId", "") ?: "")
        return JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("runtimeConnected", true)
            .put("runtimeVersion", selectedVersion?.getVersionName() ?: JSONObject.NULL)
            .put("profile", profile ?: JSONObject.NULL)
            .put("settings", settings)
            .put("library", libraryStats())
            .toString()
    }

    @JavascriptInterface
    fun saveSetting(key: String, value: String) {
        val allowed = setOf("mcVersion", "loader", "memory", "touchScale", "msClientId")
        if (key !in allowed) return
        val clean = value.take(80)
        prefs.edit().putString(key, clean).apply()
        if (key == "memory") {
            clean.toIntOrNull()?.let { memoryMb ->
                // This is the real runtime JVM-memory setting, not just a display preference.
                AllSettings.ramAllocation.save(memoryMb.coerceAtLeast(256))
            }
        }
    }

    @JavascriptInterface
    fun saveOfflineProfile(rawName: String) {
        val name = rawName.trim()
        if (!name.matches(Regex("[A-Za-z0-9_]{3,16}"))) {
            emit("auth-error", JSONObject()
                .put("provider", "Offline")
                .put("message", "Use a 3–16 character Minecraft name with letters, numbers, or underscores."))
            return
        }
        val uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(StandardCharsets.UTF_8))
            .toString().replace("-", "")
        io.execute {
            val account = Account(
                username = name,
                profileId = uuid,
                accountType = AccountType.LOCAL.tag
            )
            runCatching {
                runBlocking { AccountsManager.suspendSaveAccount(account) }
            }.onSuccess {
                if (AccountsManager.isAccountExists(account.uniqueUUID)) {
                    emit("auth-success", JSONObject().put("provider", "Offline").put("name", name).put("uuid", uuid))
                    emit("notice", JSONObject().put("message", "Local profile saved in the runtime account manager."))
                } else {
                    emit("auth-error", JSONObject().put("provider", "Offline").put("message", "The runtime could not save this local profile."))
                }
            }.onFailure { error ->
                emit("auth-error", JSONObject().put("provider", "Offline").put("message", friendlyError(Exception(error.message ?: "Could not save profile."))))
            }
        }
    }

    @JavascriptInterface
    fun openNativeAccounts() {
        activity.runOnUiThread { onOpenAccounts() }
    }

    @JavascriptInterface
    fun openNativeVersions() {
        activity.runOnUiThread { onOpenVersions() }
    }

    @JavascriptInterface
    fun launchGame() {
        activity.runOnUiThread { onPlay() }
    }

    @JavascriptInterface
    fun openExternal(rawUrl: String) {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank()) return
        activity.runOnUiThread {
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                .onFailure { emit("notice", JSONObject().put("message", "Could not open that HTTPS link.")) }
        }
    }

    @JavascriptInterface
    fun searchModrinth(rawQuery: String, kind: String, mcVersion: String, loader: String) {
        val cleanKind = if (kind == "shader") "shader" else "mod"
        io.execute {
            try {
                emit("catalog-loading", JSONObject().put("kind", cleanKind))
                val facets = JSONArray().put(JSONArray().put("project_type:$cleanKind"))
                if (mcVersion.isNotBlank()) facets.put(JSONArray().put("versions:$mcVersion"))
                if (cleanKind == "mod" && loader in setOf("fabric", "forge", "neoforge", "quilt")) {
                    facets.put(JSONArray().put("categories:$loader"))
                }
                val params = mutableListOf("facets=${enc(facets.toString())}", "limit=20", "index=relevance")
                if (rawQuery.isNotBlank()) params.add("query=${enc(rawQuery.trim().take(100))}")
                val response = JSONObject(apiText("https://api.modrinth.com/v2/search?${params.joinToString("&")}"))
                val hits = response.optJSONArray("hits") ?: JSONArray()
                val result = JSONArray()
                for (i in 0 until hits.length()) {
                    val item = hits.optJSONObject(i) ?: continue
                    result.put(JSONObject()
                        .put("id", item.optString("project_id"))
                        .put("slug", item.optString("slug"))
                        .put("title", item.optString("title", "Untitled project"))
                        .put("description", item.optString("description"))
                        .put("icon", item.optString("icon_url"))
                        .put("downloads", item.optLong("downloads", 0)))
                }
                emit("catalog-results", JSONObject().put("kind", cleanKind).put("results", result))
            } catch (e: Exception) {
                emit("catalog-error", JSONObject().put("kind", cleanKind).put("message", friendlyError(e)))
            }
        }
    }

    @JavascriptInterface
    fun installModrinth(projectId: String, kind: String, mcVersion: String, loader: String) {
        val cleanKind = if (kind == "shader") "shader" else "mod"
        if (!projectId.matches(Regex("[A-Za-z0-9]{5,64}"))) {
            emit("download-result", JSONObject().put("success", false).put("message", "Invalid Modrinth project ID."))
            return
        }
        io.execute {
            try {
                emit("download-started", JSONObject().put("kind", cleanKind))
                val selected = VersionsManager.currentVersion.value
                    ?: throw IllegalStateException("No runtime version is selected. Open Runtime Versions and select or install a Minecraft version first.")
                if (!selected.isValid()) throw IllegalStateException("The selected runtime version is not valid. Select another version in Runtime Versions.")
                val actualMcVersion = selected.getVersionInfo()?.minecraftVersion
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Could not determine the Minecraft version of the selected runtime profile.")
                if (mcVersion.isNotBlank() && mcVersion != actualMcVersion) {
                    throw IllegalStateException("Your Modrinth filter is set to Minecraft $mcVersion, but the selected runtime profile is $actualMcVersion. Change the filter or select a matching runtime version first.")
                }
                if (cleanKind == "mod") verifyLoader(selected, loader)

                val params = mutableListOf("game_versions=${enc(JSONArray().put(actualMcVersion).toString())}")
                if (cleanKind == "mod" && loader in setOf("fabric", "forge", "neoforge", "quilt")) {
                    params.add("loaders=${enc(JSONArray().put(loader).toString())}")
                }
                val versions = JSONArray(apiText("https://api.modrinth.com/v2/project/$projectId/version?${params.joinToString("&")}"))
                if (versions.length() == 0) throw IllegalStateException("No compatible Modrinth files were found for Minecraft $actualMcVersion. Try another project or loader.")

                var chosenVersion = ""
                var chosenFile: JSONObject? = null
                for (i in 0 until versions.length()) {
                    val item = versions.optJSONObject(i) ?: continue
                    val files = item.optJSONArray("files") ?: continue
                    if (files.length() == 0) continue
                    var primary: JSONObject? = null
                    for (j in 0 until files.length()) {
                        val candidate = files.optJSONObject(j) ?: continue
                        if (candidate.optBoolean("primary")) { primary = candidate; break }
                    }
                    val file = primary ?: files.optJSONObject(0)
                    if (file != null) {
                        chosenVersion = item.optString("version_number", "")
                        chosenFile = file
                        break
                    }
                }
                val file = chosenFile ?: throw IllegalStateException("Modrinth did not return a downloadable file.")
                val filename = file.optString("filename", if (cleanKind == "shader") "shaderpack.zip" else "mod.jar")
                val url = file.optString("url")
                val gameDir = selected.getGameDir()
                val targetDir = File(gameDir, if (cleanKind == "shader") "shaderpacks" else "mods")
                targetDir.mkdirs()
                val saved = downloadToGame(url, filename, cleanKind, targetDir)
                emit("download-result", JSONObject()
                    .put("success", true)
                    .put("kind", cleanKind)
                    .put("filename", saved.name)
                    .put("version", chosenVersion)
                    .put("message", "Installed ${saved.name} into the selected runtime profile's ${targetDir.name} folder.")
                    .put("library", libraryStats()))
            } catch (e: Exception) {
                emit("download-result", JSONObject()
                    .put("success", false)
                    .put("kind", cleanKind)
                    .put("message", friendlyError(e))
                    .put("library", libraryStats()))
            }
        }
    }

    private fun verifyLoader(version: Version, loader: String) {
        if (loader !in setOf("fabric", "forge", "neoforge", "quilt")) {
            throw IllegalStateException("Choose Fabric, Forge, NeoForge, or Quilt for a mod download.")
        }
        val marker = when (loader) {
            "fabric" -> "fabric-loader"
            "forge" -> "net.minecraftforge:forge"
            "neoforge" -> "net.neoforged:neoforge"
            else -> "org.quiltmc:quilt-loader"
        }
        val metadataFile = File(version.getVersionPath(), "${version.getVersionName()}.json")
        val metadata = if (metadataFile.isFile) metadataFile.readText() else ""
        val isMatching = metadata.contains(marker, ignoreCase = true) || version.getVersionName().contains(loader, ignoreCase = true)
        if (!isMatching) {
            throw IllegalStateException("The selected runtime profile does not appear to use $loader. Open Runtime Versions and select a matching loader profile before installing this mod.")
        }
    }

    private fun libraryStats(): JSONObject {
        val gameRoot = VersionsManager.currentVersion.value?.getGameDir() ?: File(getGameHome())
        fun count(name: String): Int = File(gameRoot, name).listFiles()?.count { it.isFile } ?: 0
        return JSONObject().put("mods", count("mods")).put("shaders", count("shaderpacks"))
    }

    private fun apiText(rawUrl: String): String {
        val url = URL(rawUrl)
        require(url.protocol == "https" && url.host.equals("api.modrinth.com", ignoreCase = true)) {
            "Only the HTTPS Modrinth API is allowed for catalog requests."
        }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 25000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "EmberLauncher/${BuildConfig.VERSION_NAME} (https://github.com/sagaofowlz/ember-launcher)")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Modrinth returned HTTP $code${if (body.isBlank()) "." else ": ${body.take(160)}"}")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadToGame(rawUrl: String, rawFilename: String, kind: String, directory: File): File {
        var current = URL(rawUrl)
        repeat(6) { attempt ->
            requireAllowedCdn(current)
            val connection = current.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "EmberLauncher/${BuildConfig.VERSION_NAME}")
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("Modrinth returned a redirect without a location.")
                    current = URL(current, location)
                    return@repeat
                }
                if (code !in 200..299) throw IllegalStateException("File download failed (HTTP $code).")
                if (connection.contentLengthLong > 256L * 1024 * 1024) {
                    throw IllegalStateException("This file is larger than the 256 MB safety limit.")
                }
                val extension = if (kind == "shader") ".zip" else ".jar"
                val safeName = rawFilename.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                    .take(180).trim().ifBlank { if (kind == "shader") "shaderpack.zip" else "mod.jar" }
                if (!safeName.lowercase().endsWith(extension)) {
                    throw IllegalStateException("The selected file is not a $extension file.")
                }
                var target = File(directory, safeName)
                if (target.exists()) {
                    val base = safeName.removeSuffix(extension)
                    target = File(directory, "$base-${System.currentTimeMillis().toString().takeLast(6)}$extension")
                }
                var total = 0L
                try {
                    BufferedInputStream(connection.inputStream).use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > 256L * 1024 * 1024) {
                                    throw IllegalStateException("This file is larger than the 256 MB safety limit.")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                } catch (e: Exception) {
                    target.delete()
                    throw e
                }
                return target
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Too many or unsafe redirects while downloading from Modrinth.")
    }

    private fun requireAllowedCdn(url: URL) {
        val allowed = setOf("cdn.modrinth.com", "cdn-raw.modrinth.com")
        require(url.protocol == "https" && url.host.lowercase() in allowed) {
            "For safety, downloads must come directly from a Modrinth HTTPS CDN host."
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun friendlyError(error: Exception): String {
        val message = error.message?.trim().orEmpty()
        return when {
            message.isBlank() -> "Something went wrong. Please try again."
            message.length > 260 -> message.take(257) + "…"
            else -> message
        }
    }

    private fun emit(type: String, payload: JSONObject = JSONObject()) {
        val quoted = JSONObject.quote(JSONObject().put("type", type).put("payload", payload).toString())
        activity.runOnUiThread {
            runCatching {
                webView.evaluateJavascript("window.onNativeEvent && window.onNativeEvent($quoted)", null)
            }
        }
    }
}
