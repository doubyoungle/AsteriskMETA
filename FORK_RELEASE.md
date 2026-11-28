# Fork 同步与应用更新

## 上游同步

在 fork 的 Actions 中选择 **Sync Upstream → Run workflow → main**，也可以执行 `gh workflow run sync-upstream.yml --ref main`。计划任务在每周一北京时间 10:00 合并上游 `main`，有新提交才推送。GitHub 可能延迟调度，长期无活动的 fork 也可能需要重新启用计划任务。

同步使用普通 Git 合并。若出现冲突，工作流会失败并保留远端分支。维护者可在本地合并上游 `main`、解决冲突并测试后推送。如果仓库规则拒绝默认 `GITHUB_TOKEN` 更新工作流文件，可用具有此仓库 Contents 和 Workflows 写权限的凭据配置 `UPSTREAM_SYNC_TOKEN`，或手动合并。

## 自动跟随上游正式发版

**Follow Upstream Release** 每六小时检查一次上游最新正式 Release，从 `v1.3.1` 起跟随。可用仓库变量 `UPSTREAM_START_TAG` 设置最低跟随版本，填写对应的上游正式标签。工作流以正式标签指向的源码为基线，把 fork 主分支相对上游的修改应用到这份源码，再调用双核心构建、测试、签名和发布流程。应用补丁有冲突、基线版本号不一致、测试失败或版本码不够高时，本次发布会失败，原因可在 Actions 日志中查看。

可以在 Actions 手动运行并保持 `check_only` 勾选来演练。演练使用只读令牌，不推送标签，也不调用发布任务。当前最新正式版已发布或低于起始版本时，演练和正式运行都会直接跳过。仓库变量 `AUTO_FORK_RELEASE_ENABLED=true` 启用定时发布。重复检查跳过当前或更早的已发布 fork 版本。失败后重新触发流程时，只会复用上游父提交正确、fork 父提交仍在主分支历史中、并且能按该父提交重现文件树的标签。

## 构建与发布

- 在 **Build Release APKs** 中对 `main` 执行 **Run workflow**，可以构建两个核心、运行更新器测试和 lint，并下载构建产物。普通分支运行会生成未签名产物。对 `main` 勾选 `fork_sign_test_build` 可生成沿用 fork 签名的 `signed-test-apks`，用于设备覆盖安装验证。分支测试包仅作为 Actions 产物上传。界面上的版本覆盖和 Telegram 输入仅供上游工作流使用。
- 正式发布基于对应的上游正式源码。保留上游 `ProjectConfig.VERSION_NAME`，在 `gradle.properties` 设置 `asteriskMetaForkRevision`，并推送 `v<上游版本>-r.<修订号>`。例如基于上游 `v1.3.0` 首次发版用 `v1.3.0-r.1`，同一基线的下一次发版用 `v1.3.0-r.2`。切换上游正式版本时从 `r.1` 开始。上游开发分支会保留 `-dev`，只做验证构建。应用内版本名与标签去掉开头的 `v` 后一致。也可以对 `main` 手动运行工作流，在 `fork_release_tag` 中填入已有标签以重新构建和发布。
- 在 **Settings → Secrets and variables → Actions** 配置 `RELEASE_KEYSTORE_BASE64`、`RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_ALIAS`，以及可选的 `RELEASE_KEY_PASSWORD`（默认与 keystore 密码相同）。工作流检查每个 APK 的签名证书 SHA-256，并与已发布版本核对。默认沿用两个既有正式发行版的公开证书指纹，配置可选的 `RELEASE_SIGNER_SHA256` 仓库变量时也必须通过签名连续性检查。采用 fork 自己的签名时，已安装的上游 APK 需要迁移到 fork 签名的构建。卸载旧 APK 前请备份配置。
- 工作流构建 Mihomo、Meow 各五个 APK，检查 Android 包内的版本，上传 APK 和 `update-manifest.json` 到草稿后才公开 Release。清单从签名后的 APK 生成，包含版本码、签名证书指纹、ABI 对应的资产、下载大小和 SHA-256。发布前会核验草稿的全部资产。新版本码必须高于上游基线、已分发的 fork 发行版和签名测试包。对于 GitHub Releases 外分发的 APK，可用仓库变量 `DISTRIBUTED_VERSION_CODE_FLOOR` 记录最高版本码。

本地初始化子模块并使用上游 `ProjectConfig` 指定的 JDK 和 Android SDK：

```sh
git submodule update --init --recursive
bash ./gradlew :app:assembleRelease -Pcore=mihomo
bash ./gradlew :app:assembleRelease -Pcore=meow
```

两次本地构建共用输出目录，如需同时保留产物，请在第二次构建前保存第一组 APK。

## 在 Android 上更新

安装 fork 签名的正式 APK 后，可从 **关于 → 应用更新** 手动检查。启动时也会在后台检查，自动请求间隔为六小时。GitHub API 返回 HTTP 403 或 429 时，客户端读取最新 Release 的静态清单。更新首先匹配当前 APK 的核心，再按设备的 ABI 顺序选择，最后尝试同核心的 universal APK。下载后会检查包名、版本码、核心和签名，并在有 SHA-256 时校验文件。点选下载并确认后，按系统提示授予安装来源权限并在 Android 安装器内确认。带 `.debug` 后缀的构建不显示正式 APK 更新入口。
