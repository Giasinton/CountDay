# 自动发布 Release

打一个 tag 就自动出包、发 GitHub Release，并同步一份到 Gitee。

Gitee 没有 GitHub Actions 那样的免费原生 CI（Gitee Go 属于企业版能力），所以做法是：
**在 GitHub Actions 里构建一次**，发完 GitHub Release 之后，用 Gitee OpenAPI 把同一个 APK
同步成 Gitee 的 Release。

相关文件：

| 文件 | 作用 |
|---|---|
| `.github/workflows/release.yml` | 打 tag / 手动触发时构建并发布 |
| `tools/publish-gitee.sh` | 用 Gitee OpenAPI 创建 Release 并上传 APK |

## 一次性设置

### 1. GitHub Secrets

仓库 → Settings → Secrets and variables → Actions → New repository secret，加三个：

| Secret | 用途 | 怎么拿 |
|---|---|---|
| `KEYSTORE_BASE64` | 签名证书 | `base64 -w0 ~/.countday-env/debug.keystore`（把输出整段贴进去） |
| `GITEE_TOKEN` | 调 Gitee 接口 | Gitee → 设置 → 私人令牌 → 新建，勾选 `projects` |
| `GITEE_REPO` | Gitee 仓库路径 | 形如 `yourname/count-day` |

> **`KEYSTORE_BASE64` 很重要**：Android 只允许签名一致的 APK 覆盖安装。用它签名，
> 新版本才能直接盖在旧版本上；不配的话 CI 会临时生成一张证书，那样发出来的包只能卸载重装。
> 这个证书请自己留一份备份，丢了就没法给老用户发升级包了。

### 2. Gitee 侧

不需要配 CI，只要保证**代码和 tag 都推到了 Gitee**（Release 要挂在对应的 tag 上）。

## 发一个版本

```bash
# 1) 提交改动（顺手把版本号提一下，见下）
git add -A && git commit -m "..."
git push github master && git push gitee master

# 2) 打 tag 并推给两个远端
git tag -a v1.5 -m "v1.5"
git push github v1.5
git push gitee  v1.5
```

推 `v1.5` 到 GitHub 的那一刻，Actions 就会：

1. 按 tag 算出 `VERSION_NAME=1.5`、`VERSION_CODE=10500`
   （规则：`主*10000 + 次*100 + 修订`，所以 `1.5.2` → `10502`）
2. 跑 `tools/run-tests.sh` 离线测试
3. `./build.sh` 出签名 APK
4. 发 GitHub Release（附 APK + 自动生成的提交列表）
5. 调 Gitee API 同步发一份 Gitee Release（失败不影响 GitHub 那步）

### 不想打 tag 也可以

Actions → 左侧 `Release` → Run workflow → 填版本号（如 `1.5`）→ Run。
这时 GitHub Release 会用当前提交新建 tag。

### 本地手动发（不依赖 CI）

```bash
VERSION_NAME=1.5 VERSION_CODE=10500 ./build.sh

# GitHub（用 gh 最省事）
gh release create v1.5 dist/countday-1.5.apk --title v1.5 --notes "..."

# Gitee
GITEE_TOKEN=xxx GITEE_REPO=yourname/count-day TAG=v1.5 ./tools/publish-gitee.sh
```

`tools/publish-gitee.sh` 支持 `DRY_RUN=1`，只打印将要发送的请求、不真的调接口：

```bash
DRY_RUN=1 GITEE_REPO=yourname/count-day TAG=v1.5 ./tools/publish-gitee.sh
```

## 常见问题

**Gitee 那步失败，但 GitHub 成功了？**
这是预期内的（那一步设了 `continue-on-error`）。看 Actions 日志里的 `HTTP xxx` 和响应体：

- `401`：令牌不对或过期了
- `403`：令牌没勾 `projects`
- `404`：`GITEE_REPO` 写错了，或者这个 tag 还没推到 Gitee
- 附件已存在：脚本会跳过上传；要替换先在 Gitee 网页上删掉旧附件

补发只需要本地跑一次：

```bash
GITEE_TOKEN=xxx GITEE_REPO=yourname/count-day TAG=v1.5 ./tools/publish-gitee.sh
```

**想只发一个平台？**
把 `.github/workflows/release.yml` 里对应的那一步（`发布 GitHub Release` 或 `同步到 Gitee Release`）删掉即可。

**版本号在哪改？**
CI 发布时会用 tag 覆盖 `VERSION_NAME` / `VERSION_CODE`，所以打 tag 就够了。
`app/build.gradle.kts` 和 `build.sh` 里的默认值只是给本地构建和 Android Studio 用的，顺手改一下保持整齐即可。
