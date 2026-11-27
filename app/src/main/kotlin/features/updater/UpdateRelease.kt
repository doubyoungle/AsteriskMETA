// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.updater

import java.io.IOException
import kotlinx.serialization.json.Json
import utils.arrayValue
import utils.jsonObjectOrNull
import utils.longValue
import utils.stringValue

internal data class UpdateAsset(
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val core: String,
    val abi: String,
    val url: String,
    val size: Long,
    val sha256: String?,
)

private val apkName = Regex(
    """AsteriskMETA_([0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.]+)*?)(-meow)?-([1-9][0-9]*)-(arm64-v8a|armeabi-v7a|x86_64|x86|universal)(-meow)?\.apk""",
)
private val sha256Digest = Regex("""sha256:([a-fA-F0-9]{64})""")
private val tagPattern = Regex("""v[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.]+)*""")

internal fun selectUpdate(
    metadata: String,
    repository: String,
    installedVersion: Long,
    core: String,
    supportedAbis: List<String>,
): UpdateAsset? {
    val release = Json.parseToJsonElement(metadata).jsonObjectOrNull()
        ?: throw IOException("Invalid release metadata")
    val tag = release.stringValue("tag_name")
        ?.takeIf(tagPattern::matches) ?: throw IOException("Missing or invalid release tag")
    val assets = release.arrayValue("assets") ?: throw IOException("Missing release assets")
    val manifestVersion = release.longValue("versionCode")
    if (release.containsKey("versionCode") && (manifestVersion == null || manifestVersion <= 0)) {
        throw IOException("Invalid manifest version code")
    }
    if (core != "mihomo" && core != "meow") return null
    val compatible = assets.mapNotNull { element ->
        val asset = element.jsonObjectOrNull() ?: return@mapNotNull null
        val name = asset.stringValue("name") ?: return@mapNotNull null
        val match = apkName.matchEntire(name) ?: return@mapNotNull null
        val (versionName, meow, code, abi, trailingMeow) = match.destructured
        if (meow.isNotEmpty() && trailingMeow.isNotEmpty()) return@mapNotNull null
        val assetCore = if (meow.isNotEmpty() || trailingMeow.isNotEmpty()) "meow" else "mihomo"
        if (versionName != tag.removePrefix("v") || assetCore != core) return@mapNotNull null
        val versionCode = code.toLongOrNull() ?: return@mapNotNull null
        if (versionCode <= installedVersion || (manifestVersion != null && versionCode != manifestVersion)) {
            return@mapNotNull null
        }
        val url = "https://github.com/$repository/releases/download/$tag/$name"
        if (asset.stringValue("browser_download_url") != url) return@mapNotNull null
        val size = asset.longValue("size")?.takeIf { it > 0 } ?: return@mapNotNull null
        val digest = asset.stringValue("digest")
        val sha256 = digest?.let {
            sha256Digest.matchEntire(it)?.groupValues?.get(1)?.lowercase() ?: return@mapNotNull null
        }
        UpdateAsset(name, versionName, versionCode, assetCore, abi, url, size, sha256)
    }
    val preferredAbis = (supportedAbis + "universal").distinct()
    return compatible.filter { it.abi in preferredAbis }
        .maxWithOrNull(compareBy<UpdateAsset> { it.versionCode }.thenBy { -preferredAbis.indexOf(it.abi) })
}

internal fun loadUpdateMetadata(
    repository: String,
    request: (String) -> MetadataResponse,
): String? {
    val latest = request("https://api.github.com/repos/$repository/releases/latest")
    val response = if (latest.status == 403 || latest.status == 429) {
        request("https://github.com/$repository/releases/latest/download/update-manifest.json")
    } else {
        latest
    }
    return when {
        response.status == 200 -> response.body
        latest.status == 404 -> null
        else -> throw IOException("Release metadata request failed (HTTP ${response.status})")
    }
}

internal data class MetadataResponse(val status: Int, val body: String = "")
