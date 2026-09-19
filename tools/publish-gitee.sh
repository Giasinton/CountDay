#!/usr/bin/env bash
#
# 把已经构建好的 APK 发布（或更新）到 Gitee 的 Release。
#
# Gitee 没有 GitHub Actions 那种免费的原生 CI，所以流程是：
#   GitHub Actions 里构建一次 -> 发 GitHub Release -> 用 Gitee OpenAPI 同步一份过去。
# GitHub 的服务器在国外，调 gitee.com 可能超时，所以流水线里那一步设了 continue-on-error：
# 真失败了就在本机重跑这个脚本即可（可重复执行，不会重复建 Release 或重复传附件）。
#
# 需要：
#   GITEE_TOKEN   Gitee 私人令牌（设置 -> 私人令牌，勾 projects 即可）
#   GITEE_REPO    owner/repo，例如 someone/count-day
#   TAG           形如 v1.5
# 可选：
#   APK           要上传的 APK；默认取 dist/ 下最新的那个
#   VERSION_NAME  仅用于日志
#   NOTES_FILE    发布说明（markdown 文件）
#   GITEE_TIMEOUT 单次请求超时秒数（默认 30，会重试 3 次）
#   DRY_RUN=1     只打印将要发送的请求，不真的调用接口
#
# 用法：
#   GITEE_TOKEN=xxx GITEE_REPO=someone/count-day TAG=v1.5 ./tools/publish-gitee.sh
#
# 如果本机挂了代理导致超时，可以只让 gitee 走直连：
#   no_proxy=gitee.com GITEE_TOKEN=xxx ... ./tools/publish-gitee.sh
#
set -euo pipefail

API="https://gitee.com/api/v5"
TIMEOUT="${GITEE_TIMEOUT:-30}"
CURL=(curl -sS --max-time "$TIMEOUT" --retry 3 --retry-delay 2 --retry-all-errors)

if [ "${DRY_RUN:-0}" = "1" ]; then
  GITEE_TOKEN="${GITEE_TOKEN:-dry-run}"
else
  : "${GITEE_TOKEN:?缺少 GITEE_TOKEN}"
fi
: "${GITEE_REPO:?缺少 GITEE_REPO（owner/repo）}"
: "${TAG:?缺少 TAG（形如 v1.5）}"

APK="${APK:-$(ls -t dist/*.apk 2>/dev/null | head -1 || true)}"
if [ -z "${APK:-}" ] || [ ! -f "$APK" ]; then
  echo "错误：找不到 APK，请先运行 ./build.sh" >&2
  exit 1
fi

NOTES_FILE="${NOTES_FILE:-}"
TITLE="纪念日 ${VERSION_NAME:-${TAG#v}}"
NOTES="${TITLE}

下载 APK 直接安装；已装旧版本可直接覆盖安装。"

# 从 stdin 的 JSON 里取一个字段值；不依赖 jq。
# 注意 Gitee 在"资源不存在"时会返回 HTTP 200 + body `null`，这里必须容错。
json_get() {
  python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print(""); raise SystemExit
print(d.get(sys.argv[1], "") or "" if isinstance(d, dict) else "")
' "$1"
}

# 统一的请求封装：成功时把响应体打到 stdout；失败时把原因打到 stderr 并返回非 0
api() {
  local method="$1" url="$2"; shift 2
  local out err http rc
  out="$(mktemp)"; err="$(mktemp)"
  set +e
  http="$("${CURL[@]}" -X "$method" "$url" -o "$out" -w '%{http_code}' "$@" 2>"$err")"
  rc=$?
  set -e

  if [ "$rc" -ne 0 ] || [ -z "$http" ] || [ "$http" = "000" ]; then
    echo "    网络错误：连不上 gitee.com（curl 退出码 $rc）" >&2
    sed 's/^/    /' "$err" >&2 || true
    echo "    -> 已自动重试 3 次仍失败。如果本机挂了代理，试试：no_proxy=gitee.com ..." >&2
    rm -f "$out" "$err"
    return 1
  fi

  if [ "$http" -lt 200 ] || [ "$http" -ge 300 ]; then
    echo "    HTTP $http" >&2
    head -c 600 "$out" >&2 || true
    echo >&2
    case "$http" in
      401) echo "    -> 令牌无效/过期，重新生成 GITEE_TOKEN" >&2 ;;
      403) echo "    -> 令牌权限不够，生成时勾上 projects" >&2 ;;
      404) echo "    -> 检查 GITEE_REPO 是否写成 owner/repo；tag $TAG 是否已 push 到 Gitee" >&2 ;;
    esac
    rm -f "$out" "$err"
    return 1
  fi

  cat "$out"
  rm -f "$out" "$err"
}

# 0) 自检令牌：能拿到自己的用户名，说明令牌有效；这一步也顺便验证网络通不通
if [ "${DRY_RUN:-0}" != "1" ]; then
  echo "==> 校验 Gitee 令牌"
  ME_OUT="$(mktemp)"; ME_ERR="$(mktemp)"
  if ! "${CURL[@]}" "$API/user?access_token=$GITEE_TOKEN" -o "$ME_OUT" 2>"$ME_ERR"; then
    echo "    网络错误：连不上 gitee.com" >&2
    sed 's/^/    /' "$ME_ERR" >&2 || true
    echo "    这不是令牌的问题。常见原因：本机代理（http_proxy/https_proxy/ALL_PROXY）对 gitee 不稳定。" >&2
    echo "    可以只让 gitee 直连再试： no_proxy=gitee.com GITEE_TOKEN=... $0" >&2
    rm -f "$ME_OUT" "$ME_ERR"
    exit 1
  fi
  USER_NAME="$(json_get login < "$ME_OUT")"
  if [ -z "$USER_NAME" ]; then
    echo "    令牌无效或已过期，接口返回：" >&2
    head -c 300 "$ME_OUT" >&2 || true
    echo >&2
    rm -f "$ME_OUT" "$ME_ERR"
    echo "    去 Gitee → 设置 → 私人令牌 重新生成一个（勾 projects）" >&2
    exit 1
  fi
  echo "    令牌有效，身份：$USER_NAME"
fi

echo "==> Gitee 发布：$GITEE_REPO  $TAG"
echo "    APK : $APK ($(du -h "$APK" | cut -f1))"
echo "    说明: $TITLE"

# 1) 这个 tag 是不是已经有 Release 了（重跑时复用）
echo "==> 查询已有 Release"
RELEASE_ID=""
if EXISTING="$(api GET "$API/repos/$GITEE_REPO/releases/tags/$TAG?access_token=$GITEE_TOKEN" 2>/dev/null)"; then
  # Gitee 对"没有这个 Release"返回 200 + null，所以要看取到的 id 是否为空
  RELEASE_ID="$(printf '%s' "$EXISTING" | json_get id)"
  if [ -n "$RELEASE_ID" ]; then
    echo "    已存在，release id = $RELEASE_ID"
  else
    echo "    还没有，接着创建"
  fi
fi

# 2) 没有就创建
if [ -z "$RELEASE_ID" ]; then
  if [ "${DRY_RUN:-0}" = "1" ]; then
    echo "    [dry-run] POST $API/repos/$GITEE_REPO/releases (tag=$TAG)"
    RELEASE_ID="dry-run"
  else
    echo "==> 创建 Release"
    BODY_ARGS=(
      --data-urlencode "access_token=$GITEE_TOKEN"
      --data-urlencode "tag_name=$TAG"
      --data-urlencode "name=$TITLE"
      --data-urlencode "target_commitish=${GITEE_BRANCH:-master}"
    )
    if [ -n "$NOTES_FILE" ] && [ -f "$NOTES_FILE" ]; then
      BODY_ARGS+=(--data-urlencode "body@$NOTES_FILE")
    else
      BODY_ARGS+=(--data-urlencode "body=$NOTES")
    fi
    CREATED="$(api POST "$API/repos/$GITEE_REPO/releases" "${BODY_ARGS[@]}")"
    RELEASE_ID="$(printf '%s' "$CREATED" | json_get id)"
    if [ -z "$RELEASE_ID" ]; then
      echo "错误：创建 Release 失败，响应里没有 id" >&2
      exit 1
    fi
    echo "    release id = $RELEASE_ID"
  fi
fi

# 3) 上传 APK（同名附件已存在就跳过）
ASSET_NAME="$(basename "$APK")"
if [ "${DRY_RUN:-0}" = "1" ]; then
  echo "    [dry-run] POST $API/repos/$GITEE_REPO/releases/{release_id}/attach_files  file=$ASSET_NAME"
  exit 0
fi

echo "==> 检查附件是否已存在"
if ASSETS="$(api GET "$API/repos/$GITEE_REPO/releases/$RELEASE_ID?access_token=$GITEE_TOKEN" 2>/dev/null)"; then
  if printf '%s' "$ASSETS" | grep -qF "\"$ASSET_NAME\""; then
    echo "    已存在同名附件，跳过上传（如需替换请先在网页上删掉）"
    echo "完成：https://gitee.com/$GITEE_REPO/releases/tag/$TAG"
    exit 0
  fi
fi

echo "==> 上传 APK"
# token 放 query：curl 不允许 -F 与 --data-urlencode 混用
api POST "$API/repos/$GITEE_REPO/releases/$RELEASE_ID/attach_files?access_token=$GITEE_TOKEN" \
  -F "file=@$APK" >/dev/null
echo "完成：https://gitee.com/$GITEE_REPO/releases/tag/$TAG"
