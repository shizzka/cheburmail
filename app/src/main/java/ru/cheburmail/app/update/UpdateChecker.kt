package ru.cheburmail.app.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проверяет наличие обновлений через GitHub Releases API.
 *
 * Формат: tag_name = "v<versionCode>" (например "v3")
 * Body содержит versionName для отображения.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val GITHUB_API =
        "https://api.github.com/repos/shizzka/cheburmail/releases/latest"
    private const val PREFS_NAME = "update_checker"
    private const val KEY_LATEST_VERSION_CODE = "latest_version_code"
    private const val KEY_LATEST_VERSION_NAME = "latest_version_name"
    private const val KEY_DOWNLOAD_URL = "download_url"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_DISMISSED_VERSION = "dismissed_version_code"

    const val GITHUB_RELEASES_URL = "https://github.com/shizzka/cheburmail/releases/latest"
    const val BOT_URL = "https://t.me/my_fabrica_bot"

    data class UpdateInfo(
        val latestVersionCode: Int,
        val latestVersionName: String,
        val downloadUrl: String
    )

    /**
     * Проверяет GitHub Releases и сохраняет результат в SharedPreferences.
     * @return UpdateInfo если есть обновление, null если нет или ошибка.
     */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(GITHUB_API).openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            if (connection.responseCode != 200) {
                Log.w(TAG, "GitHub API returned ${connection.responseCode}")
                return@withContext null
            }

            val body = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(body)

            val tagName = json.getString("tag_name") // "v3"
            val versionName = json.optString("name", tagName) // "0.2.0"
            val htmlUrl = json.getString("html_url")

            // Парсим versionCode из tag: "v3" → 3
            val remoteVersionCode = tagName.removePrefix("v").toIntOrNull()
            if (remoteVersionCode == null) {
                Log.w(TAG, "Cannot parse versionCode from tag: $tagName")
                return@withContext null
            }

            // Ищем APK в assets
            var apkUrl = htmlUrl
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }
            }

            val currentVersionCode = getCurrentVersionCode(context)

            // Сохраняем результат
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putInt(KEY_LATEST_VERSION_CODE, remoteVersionCode)
                .putString(KEY_LATEST_VERSION_NAME, versionName)
                .putString(KEY_DOWNLOAD_URL, apkUrl)
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .apply()

            Log.i(TAG, "Check complete: current=$currentVersionCode, latest=$remoteVersionCode ($versionName)")

            if (remoteVersionCode > currentVersionCode) {
                UpdateInfo(remoteVersionCode, versionName, apkUrl)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /**
     * В��звращает сохранённую информацию об обновлении (без сетевого запроса).
     */
    fun getCached(context: Context): UpdateInfo? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val latestCode = prefs.getInt(KEY_LATEST_VERSION_CODE, 0)
        val dismissedCode = prefs.getInt(KEY_DISMISSED_VERSION, 0)
        val currentCode = getCurrentVersionCode(context)

        if (latestCode <= currentCode || latestCode <= dismissedCode) return null

        val name = prefs.getString(KEY_LATEST_VERSION_NAME, "") ?: ""
        val url = prefs.getString(KEY_DOWNLOAD_URL, GITHUB_RELEASES_URL) ?: GITHUB_RELEASES_URL

        return UpdateInfo(latestCode, name, url)
    }

    /**
     * Скрыть баннер для текущей версии обновления.
     */
    fun dismiss(context: Context, versionCode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DISMISSED_VERSION, versionCode)
            .apply()
    }

    private fun getCurrentVersionCode(context: Context): Int {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        } catch (e: PackageManager.NameNotFoundException) {
            0
        }
    }
}
