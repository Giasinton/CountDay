#!/usr/bin/env bash
#
# 把已经构建好的 APK 发布（或更新）到 Gitee 的 Release。
#
# Gitee 没有 GitHub Actions 那种免费的原生 CI，所以流程是：
#   GitHub Actions 里构建一次 -> 发 GitHub Release -> 用 Gitee OpenAPI 同步一份过去。
#
# 需要：
#   GITEE_TOKEN   Gitee 私人令牌（设置 -> 私人令牌，勾 projects 即可）
#   GITEE_REPO    owner/repo，例如 someone/count-day
#   TAG           形如 v1.5
# 可选：
#   APK           要上传的 APK；默认取 dist/ 下最新的那个
#   VERSION_NAME  仅用于日志
#   NOTES_FILE    发布说明（markdown 文件）
#   DRY_RUN=1     只打印将要发送的请求，不真的调用接口
#
# 用法（本地手动发布时）：
#   GITEE_TOKEN=xxx GITEE_REPO=someone/count-day TAG=v1.5 ./tools/publish-gitee.sh
#
set -euo pipefail

API="https://gitee.com/api/v5"

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

# 0) 先自检令牌：能拿到自己的用户名，说明令牌本身是有效的
if [ "${DRY_RUN:-0}" != "1" ]; then
  echo "==> 校验 Gitee 令牌"
  ME="$(curl -sS --max-time 20 "$API/user?access_token=$GITEE_TOKEN" || echo '')"
  USER_NAME="$(printf '%s' "$ME" | python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("login",""))
except Exception: print("")' 2>/dev/null || true)"
  if [ -z "$USER_NAME" ]; then
    echo "    令牌无效或已过期。接口返回：" >&2
    printf '%s\n' "$ME" | head -c 300 >&2; echo >&2
    echo "    去 Gitee → 设置 → 私人令牌 重新生成一个（勾 projects）" >&2
    exit 1
  fi
  echo "    令牌有效，身份：$USER_NAME"
fi

echo "==> Gitee 发布：$GITEE_REPO  $TAG"
echo "    APK : $APK ($(du -h "$APK" | cut -f1))"
echo "    说明: $TITLE"

# 统一的请求封装：失败时把响应体打出来，方便定位
api() {
  local method="$1" url="$2"; shift 2
  local out http
  out="$(mktemp)"
  http="$(curl -sS -X "$method" "$url" -o "$out" -w '%{http_code}' "$@" || echo 000)"
  if [ "$http" -lt 200 ] || [ "$http" -ge 300 ]; then
    echo "    HTTP $http" >&2
    head -c 600 "$out" >&2 || true
    echo >&2
    case "$http" in
      401) echo "    -> 令牌无效/过期，重新生成 GITEE_TOKEN" >&2 ;;
      403) echo "    -> 令牌权限不够，生成时勾上 projects" >&2 ;;
      404) echo "    -> 检查 GITEE_REPO 是否写成 owner/repo；tag $TAG 是否已 push 到 Gitee" >&2 ;;
    esac
    rm -f "$out"
    return 1
  fi
  cat "$out"
  rm -f "$out"
}

json_get() {  # 从 stdin 的 JSON 里取一个字符串字段（不依赖 jq）
  python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get(sys.argv[1], ""))' "$1"
}

# 1) 先看这个 tag 是不是已经有 Release 了（重跑时复用，避免报"已存在"）
echo "==> 查询已有 Release"
RELEASE_ID=""
if EXISTING="$(api GET "$API/repos/$GITEE_REPO/releases/tags/$TAG?access_token=$GITEE_TOKEN" 2>/dev/null)"; then
  RELEASE_ID="$(printf '%s' "$EXISTING" | json_get id)"
  echo "    已存在，release id = $RELEASE_ID"
fi

# 2) 没有就创建
if [ -z "$RELEASE_ID" ]; then
  if [ "${DRY_RUN:-0}" = "1" ]; then
    echo "    [dry-run] POST $API/repos/$GITEE_REPO/releases (tag=$TAG)"
  else
    echo "==> 创建 Release"
    BODY_ARGS=(
      --data-urlencode "access_token=$GITEE_TOKEN"
      --data-urlencode "tag_name=$TAG"
      --data-urlencode "name=$TITLE"
      --data-urlencode "target_commitish=${GITEE_BRANCH:-master}"
      --data-urlencode "body=$NOTES"
    )
    if [ -n "$NOTES_FILE" ] && [ -f "$NOTES_FILE" ]; then
      BODY_ARGS=(
        --data-urlencode "access_token=$GITEE_TOKEN"
        --data-urlencode "tag_name=$TAG"
        --data-urlencode "name=$TITLE"
        --data-urlencode "target_commitish=${GITEE_BRANCH:-master}"
        --data-urlencode "body@$NOTES_FILE"
      )
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

# 3) 上传 APK（如果同名附件已经在，就跳过）
ASSET_NAME="$(basename "$APK")"
if [ "${DRY_RUN:-0}" = "1" ]; then
  echo "    [dry-run] POST $API/repos/$GITEE_REPO/releases/{release_id}/attach_files  file=$ASSET_NAME"
  exit 0
fi

if [ -n "$RELEASE_ID" ]; then
  echo "==> 检查附件是否已存在"
  if ASSETS="$(api GET "$API/repos/$GITEE_REPO/releases/$RELEASE_ID?access_token=$GITEE_TOKEN" 2>/dev/null)"; then
    if printf '%s' "$ASSETS" | grep -q "\"$ASSET_NAME\""; then
      echo "    已存在同名附件，跳过上传（如需替换请先在网页上删掉）"
      echo "完成：https://gitee.com/$GITEE_REPO/releases/tag/$TAG"
      exit 0
    fi
  fi

  echo "==> 上传 APK"
  api POST "$API/repos/$GITEE_REPO/releases/$RELEASE_ID/attach_files" \
    --data-urlencode "access_token=$GITEE_TOKEN" \
    -F "file=@$APK" >/dev/null
  echo "完成：https://gitee.com/$GITEE_REPO/releases/tag/$TAG"
fi
