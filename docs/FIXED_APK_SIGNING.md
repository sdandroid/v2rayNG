# 固定 APK 签名（仅 arm64-v8a）

这个仓库的专用 Actions 工作流使用同一份 **keystore** 签署所有 `fdroidRelease` APK。密钥别名固定为 `v2rayng`，且密钥密码和 keystore 密码使用同一个密码。

## 首次设置（只做一次）

1. GitHub 仓库页面选择 **Code → Codespaces → Create codespace on master**，打开终端（不需要 Android Studio）。
2. 在仓库根目录运行：

   ```bash
   bash scripts/setup-android-signing.sh
   ```

3. 终端输入至少 12 个字符的签名密码（不显示字符），再次输入确认。
4. 脚本创建 `v2rayng-release.jks`（如果已有则复用），并使用 `gh secret set` 保存仓库的 Actions Secrets：
   - `APP_KEYSTORE_BASE64` — keystore 的 Base64 内容；
   - `APP_KEYSTORE_PASSWORD` — 上述密码。
5. **立即下载、离线备份** `v2rayng-release.jks`，并单独保存密码。Codespaces 中可右键文件选择 **Download**。不要提交、截图或发送私钥。
6. 打开 **Actions → Build Wi-Fi auto-disconnect APK → Run workflow**。下载 `v2rayNG-arm64-v8a-signed`。

> 如果 Codespaces 未登录 GitHub CLI，运行 `gh auth login`；如果当前登录身份没有配置仓库 Secrets 的权限，请改用有管理员权限的终端。
>
> 初始化脚本为了防止误换签名，检测到 `APP_KEYSTORE_BASE64` 已存在时会拒绝覆盖。丢失 keystore 后无法从 GitHub Secret 读取回来，因此备份非常重要。

## 签名规则

- 密钥别名：`v2rayng`（固定）。
- keystore/密钥密码：`APP_KEYSTORE_PASSWORD`（同一个密码）。
- keystore 文件内容：`APP_KEYSTORE_BASE64`。
- 只编译并上传 `arm64-v8a` 架构 APK。
- 配置这两个 Secrets 后，`master` 的自动构建输出固定签名的 `fdroidRelease` APK。
- 尚未配置 Secrets 时，只上传明确标有 `NOT-FIXED-SIGNED` 的临时 Debug 测试 APK，**不能**拿它当稳定更新包。
- 手动 `Build APK` 工作流同样使用这两项 Secrets；它没有 Debug 回退。填写 `release_tag` 可发布已用 Android keystore 签名的 APK，不再要求额外的 GPG 私钥。
- 不会把 keystore、密码写入 Git 仓库或构建日志。
- 更新时仍需要相同的 Android 应用包名与签名；如果已安装的是以前的随机 Debug 签名版本，不能直接覆盖安装。先导出节点配置备份，再卸载旧版并安装固定签名版。
- 后续如果升级应用版本，记得增加 APK 的 versionCode；不要更换 signing keystore。

## 手动配置 Secrets

也可以自行创建一个带有别名 `v2rayng`、密钥与库密码一致的 keystore，然后进入 **Settings → Secrets and variables → Actions → New repository secret** 分别填写以上两个 Secrets。Base64 内容可以通过 `base64 < v2rayng-release.jks | tr -d '\r\n'` 生成（注意不要将输出贴到公开仓库）。
