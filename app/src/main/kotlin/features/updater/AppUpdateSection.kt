// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.updater

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.R
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ui.components.AsteriskListRow
import ui.components.AsteriskSection

@Composable
internal fun AppUpdateSection() {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    if (!AppUpdater.enabled(activity)) return
    // The language provider supplies a configuration Context for resources.
    CompositionLocalProvider(LocalActivityResultRegistryOwner provides activity) {
        AppUpdateControls(activity)
    }
}

@Composable
private fun AppUpdateControls(activity: ComponentActivity) {
    val state by AppUpdater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<UpdateAsset?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }

    fun install(apk: File) {
        try {
            AppUpdater.install(activity, apk)
        } catch (error: Exception) {
            android.util.Log.w("AppUpdater", "Could not launch package installer", error)
            message = R.string.app_update_install_failed
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        working = false
        if (activity.packageManager.canRequestPackageInstalls()) {
            downloaded?.let(::install)
        } else {
            message = R.string.app_update_permission_needed
        }
    }

    fun requestInstall(apk: File) {
        message = null
        if (activity.packageManager.canRequestPackageInstalls()) {
            install(apk)
        } else {
            working = true
            try {
                permissionLauncher.launch(AppUpdater.permissionSettings(activity))
            } catch (error: Exception) {
                android.util.Log.w("AppUpdater", "Could not launch install permission settings", error)
                working = false
                message = R.string.app_update_install_failed
            }
        }
    }

    val asset = (state as? AppUpdater.Check.Available)?.asset
    val apk = downloaded?.takeIf { it.name == asset?.name }
    val description = message?.let { stringResource(it) } ?: when {
        working && apk == null -> stringResource(R.string.app_update_downloading)
        state is AppUpdater.Check.Checking -> stringResource(R.string.app_update_checking)
        asset != null -> stringResource(R.string.app_update_available, asset.versionName, asset.versionCode)
        state is AppUpdater.Check.Current -> stringResource(R.string.app_update_current)
        state is AppUpdater.Check.Failed -> stringResource(R.string.app_update_check_failed)
        else -> null
    }
    AsteriskSection(title = stringResource(R.string.app_update_title)) {
        AsteriskListRow(
            title = stringResource(R.string.app_update_check),
            summary = description,
            enabled = !working && state !is AppUpdater.Check.Checking,
            onClick = {
                message = null
                scope.launch { AppUpdater.check(activity.applicationContext, manual = true) }
            },
        )
        if (asset != null) {
            AsteriskListRow(
                title = stringResource(
                    if (apk == null) R.string.app_update_download else R.string.app_update_install,
                ),
                enabled = !working,
                onClick = { if (apk == null) confirm = asset else requestInstall(apk) },
            )
        }
    }
    confirm?.let { update ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.app_update_available, update.versionName, update.versionCode)) },
            text = { Text(stringResource(R.string.app_update_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    message = null
                    working = true
                    scope.launch {
                        val file = try {
                            AppUpdater.download(activity.applicationContext, update)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            android.util.Log.w("AppUpdater", "Update download failed", error)
                            message = R.string.app_update_download_failed
                            null
                        } finally {
                            working = false
                        }
                        if (file != null) {
                            downloaded = file
                            requestInstall(file)
                        }
                    }
                }) { Text(stringResource(R.string.app_update_download)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
