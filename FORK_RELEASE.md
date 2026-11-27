# Fork 同步与应用更新

## 上游同步

在 fork 的 Actions 中选择 **Sync Upstream → Run workflow → main**，也可以执行 `gh workflow run sync-upstream.yml --ref main`。计划任务在每周一北京时间 10:00 合并上游 `main`，有新提交才推送。GitHub 可能延迟调度，长期无活动的 fork 也可能需要重新启用计划任务。

同步使用普通 Git 合并。若出现冲突，工作流会失败并保留远端分支。维护者可在本地合并上游 `main`、解决冲突并测试后推送。如果仓库规则拒绝默认 `GITHUB_TOKEN` 更新工作流文件，可用具有此仓库 Contents 和 Workflows 写权限的凭据配置 `UPSTREAM_SYNC_TOKEN`，或手动合并。

## 构建与发布

- 在 **Build Release APKs** 中对 `main` 执行 **Run workflow**，可以构建两个核心、运行更新器测试和 lint，并下载构建产物。分支运行不发布 Release，产物未签名。界面上的版本覆盖和 Telegram 输入仅供上游工作流使用。
- 准备发布时，将 `buildSrc/src/main/kotlin/BuildConfig.kt` 中的 `ProjectConfig.VERSION_NAME` 设置为稳定版本（例如 `1.3.1`），提交到 fork 的 `main`，再推送对应的 `v1.3.1` 标签。也可以对已存在的版本标签手动运行工作流。
- 在 **Settings → Secrets and variables → Actions** 配置 `RELEASE_KEYSTORE_BASE64`、`RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_ALIAS`，以及可选的 `RELEASE_KEY_PASSWORD`（默认与 keystore 密码相同）。妥善保管用于签名的 keystore，后续版本使用同一签名。采用 fork 自己的签名时，已安装的上游 APK 需要迁移到 fork 签名的构建。卸载旧 APK 前请备份配置。
- 工作流构建 Mihomo、Meow 各五个 APK，检查 Android 包内的版本，上传 APK 和 `update-manifest.json` 到草稿后才公开 Release。清单从签名后的 APK 生成，包含版本码、ABI 对应的资产、下载大小和 SHA-256。后续发版的 Git 提交数必须高于上一个 Release 的 `versionCode`，因此应沿 fork 的发布分支继续演进。

本地初始化子模块并使用上游 `ProjectConfig` 指定的 JDK 和 Android SDK：

```sh
git submodule update --init --recursive
bash ./gradlew :app:assembleRelease -Pcore=mihomo
bash ./gradlew :app:assembleRelease -Pcore=meow
```

两次本地构建共用输出目录，如需同时保留产物，请在第二次构建前保存第一组 APK。

## 在 Android 上更新

安装 fork 签名的正式 APK 后，可从 **关于 → 应用更新** 手动检查。启动时也会在后台检查，自动请求间隔为六小时。GitHub API 返回 HTTP 403 或 429 时，客户端读取最新 Release 的静态清单。更新首先匹配当前 APK 的核心，再按设备的 ABI 顺序选择，最后尝试同核心的 universal APK。下载后会检查包名、版本码、核心和签名，并在有 SHA-256 时校验文件。点选下载并确认后，按系统提示授予安装来源权限并在 Android 安装器内确认。带 `.debug` 后缀的构建不显示正式 APK 更新入口。
