// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.updater

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateReleaseTest {
    private val repository = "example/AsteriskMETA"

    @Test
    fun ignoresEqualAndOlderVersionCodes() {
        val asset = "AsteriskMETA_1.3.0-615-arm64-v8a.apk"
        assertNull(select(listOf(asset), installed = 615))
        assertNull(select(listOf(asset), installed = 616))
        assertEquals(615L, select(listOf(asset), installed = 614)?.versionCode)
    }

    @Test
    fun keepsBundledCoreEvenWhenAnotherCoreHasAnExactAbi() {
        val mihomo = "AsteriskMETA_1.3.0-615-arm64-v8a.apk"
        val meow = "AsteriskMETA_1.3.0-meow-615-universal.apk"
        val trailingMeow = "AsteriskMETA_1.3.0-615-arm64-v8a-meow.apk"
        assertEquals(mihomo, select(listOf(meow, mihomo))?.name)
        assertEquals(meow, select(listOf(mihomo, meow), core = "meow")?.name)
        assertEquals(trailingMeow, select(listOf(mihomo, trailingMeow), core = "meow")?.name)
        assertNull(select(listOf(mihomo), core = "meow"))
        assertNull(select(listOf(meow), core = "mihomo"))
    }

    @Test
    fun usesPreferredAbiThenSecondaryAbiThenUniversal() {
        val preferred = "AsteriskMETA_1.3.0-615-arm64-v8a.apk"
        val secondary = "AsteriskMETA_1.3.0-615-armeabi-v7a.apk"
        val universal = "AsteriskMETA_1.3.0-615-universal.apk"
        assertEquals(preferred, select(listOf(universal, secondary, preferred))?.name)
        assertEquals(secondary, select(listOf(universal, secondary))?.name)
        assertEquals(universal, select(listOf(universal))?.name)
        assertNull(select(listOf("AsteriskMETA_1.3.0-615-x86_64.apk")))
    }

    @Test
    fun usesStaticManifestOnBothRateLimitStatuses() {
        val asset = "AsteriskMETA_1.3.0-meow-615-arm64-v8a.apk"
        for (status in listOf(403, 429)) {
            val urls = mutableListOf<String>()
            val metadata = loadUpdateMetadata(repository) { url ->
                urls.add(url)
                if (urls.size == 1) MetadataResponse(status)
                else MetadataResponse(200, release(listOf(asset), versionCode = 615))
            }
            assertEquals(
                listOf(
                    "https://api.github.com/repos/$repository/releases/latest",
                    "https://github.com/$repository/releases/latest/download/update-manifest.json",
                ),
                urls,
            )
            assertEquals(
                asset,
                selectUpdate(requireNotNull(metadata), repository, 614, "meow", listOf("arm64-v8a"))?.name,
            )
        }
    }

    @Test
    fun distinguishesNoReleaseFromFailedFallback() {
        assertNull(loadUpdateMetadata(repository) { MetadataResponse(404) })
        assertThrows(IOException::class.java) {
            loadUpdateMetadata(repository) { url ->
                MetadataResponse(if (url.startsWith("https://api.")) 403 else 404)
            }
        }
    }

    @Test
    fun rejectsManifestVersionAndDownloadUrlMismatches() {
        val asset = "AsteriskMETA_1.3.0-615-arm64-v8a.apk"
        val mismatched = release(listOf(asset), versionCode = 616)
        assertNull(selectUpdate(mismatched, repository, 1, "mihomo", listOf("arm64-v8a")))
        val foreign = release(listOf(asset)).replace("https://github.com/", "https://example.com/")
        assertNull(selectUpdate(foreign, repository, 1, "mihomo", listOf("arm64-v8a")))
    }

    private fun select(names: List<String>, installed: Long = 614, core: String = "mihomo") =
        selectUpdate(release(names), repository, installed, core, listOf("arm64-v8a", "armeabi-v7a"))

    private fun release(names: List<String>, versionCode: Int? = null): String {
        val assets = names.joinToString(",") { name ->
            """{"name":"$name","size":42,"browser_download_url":"https://github.com/$repository/releases/download/v1.3.0/$name"}"""
        }
        val version = versionCode?.let { """"versionCode":$it,""" }.orEmpty()
        return """{${version}"tag_name":"v1.3.0","assets":[$assets]}"""
    }
}
