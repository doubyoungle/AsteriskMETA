// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.updater

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import app.ProjectInfo
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object AppUpdater {
    private const val Repository = "doubyoungle/AsteriskMETA"
    private const val CheckInterval = 6 * 60 * 60 * 1000L
    private const val MaxMetadataBytes = 1024 * 1024
    private const val ApkMimeType = "application/vnd.android.package-archive"

    sealed interface Check {
        data object Idle : Check
        data object Checking : Check
        data object Current : Check
        data object Failed : Check
        data class Available(val asset: UpdateAsset) : Check
    }

    private val checkMutex = Mutex()
    private val checkState = MutableStateFlow<Check>(Check.Idle)
    val state = checkState.asStateFlow()

    fun enabled(context: Context): Boolean = context.packageName == "org.asterisk.zcc.ameta"

    suspend fun check(context: Context, manual: Boolean = false) {
        if (!enabled(context)) return
        checkMutex.withLock {
            val previous = checkState.value
            withContext(Dispatchers.IO) {
                val preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                if (!manual && now - preferences.getLong("last_check", 0) in 0 until CheckInterval) {
                    return@withContext
                }
                preferences.edit().putLong("last_check", now).apply()
                checkState.value = Check.Checking
                try {
                    val installed = context.packageManager.getPackageInfo(context.packageName, 0)
                    val metadata = loadUpdateMetadata(Repository, ::readMetadata)
                    coroutineContext.ensureActive()
                    val asset = metadata?.let {
                        selectUpdate(
                            it, Repository, PackageInfoCompat.getLongVersionCode(installed),
                            ProjectInfo.CORE_ID, Build.SUPPORTED_ABIS.toList(),
                        )
                    }
                    checkState.value = if (asset == null) Check.Current else Check.Available(asset)
                } catch (cancelled: CancellationException) {
                    checkState.value = previous
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w("AppUpdater", "Update check failed", error)
                    checkState.value = if (manual) Check.Failed else previous
                }
            }
        }
    }

    suspend fun download(context: Context, asset: UpdateAsset): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create update cache")
        val temporary = File.createTempFile("partial-", ".apk", directory)
        val apk = File(directory, asset.name)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            open(asset.url, "application/octet-stream") { connection ->
                if (connection.responseCode != 200) throw IOException("APK download failed (HTTP ${connection.responseCode})")
                var received = 0L
                connection.inputStream.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val length = input.read(buffer)
                            if (length == -1) break
                            received += length
                            if (received > asset.size) throw IOException("Unexpected APK size")
                            digest.update(buffer, 0, length)
                            output.write(buffer, 0, length)
                        }
                    }
                }
                if (received != asset.size) throw IOException("Incomplete APK download")
            }
            if (asset.sha256 != null && digest.digest().toHexString() != asset.sha256) {
                throw IOException("APK checksum mismatch")
            }
            verifyApk(context, temporary, asset)
            coroutineContext.ensureActive()
            if (!temporary.renameTo(apk)) throw IOException("Cannot save downloaded APK")
            apk
        } finally {
            temporary.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(context: Context, file: File, asset: UpdateAsset) {
        val manager = context.packageManager
        val signingFlag = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val installed = manager.getPackageInfo(context.packageName, signingFlag)
        val archive = manager.getPackageArchiveInfo(file.path, signingFlag or PackageManager.GET_META_DATA)
            ?: throw IOException("Cannot read the downloaded APK")
        val version = PackageInfoCompat.getLongVersionCode(archive)
        if (archive.packageName != context.packageName || version != asset.versionCode ||
            version <= PackageInfoCompat.getLongVersionCode(installed) ||
            archive.applicationInfo?.metaData?.getString("app.updater.CORE_ID") != ProjectInfo.CORE_ID
        ) {
            throw IOException("APK package, version or core mismatch")
        }
        fun signers(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners.orEmpty().toSet()
        } else {
            info.signatures.orEmpty().toSet()
        }
        val installedSigners = signers(installed)
        if (installedSigners.isEmpty() || installedSigners != signers(archive)) {
            throw IOException("APK signing certificate differs from the installed app")
        }
    }

    fun permissionSettings(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.fromParts("package", context.packageName, null),
    )

    fun install(context: Context, apk: File) {
        if (!apk.isFile) throw IOException("Downloaded APK is missing")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, ApkMimeType)
            clipData = ClipData.newRawUri(apk.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun readMetadata(url: String): MetadataResponse = open(url, "application/json") { connection ->
        val status = connection.responseCode
        if (status != 200) return@open MetadataResponse(status)
        val body = connection.inputStream.use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val length = input.read(buffer)
                if (length == -1) break
                if (bytes.size() + length > MaxMetadataBytes) throw IOException("Release metadata is too large")
                bytes.write(buffer, 0, length)
            }
            bytes.toString(Charsets.UTF_8.name())
        }
        MetadataResponse(status, body)
    }

    private inline fun <T> open(url: String, accept: String, read: (HttpURLConnection) -> T): T {
        var target = URI(url)
        repeat(6) {
            if (target.scheme != "https") throw IOException("Update URL must use HTTPS")
            val connection = target.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Accept", accept)
                connection.setRequestProperty("User-Agent", "AsteriskMETA-Updater")
                if (connection.responseCode !in setOf(301, 302, 303, 307, 308)) return read(connection)
                val location = connection.getHeaderField("Location") ?: throw IOException("Missing redirect location")
                target = target.resolve(location)
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many update redirects")
    }
}
