#!/usr/bin/env bash
# fetch-deps.sh - 一键拉取 Minecraft 26.2 + Fabric 依赖，并（可选）直接构建
#
#   ./scripts/fetch-deps.sh              # 只拉依赖到 .mcserver/
#   ./scripts/fetch-deps.sh --build      # 拉完直接编译出 build/plogin-1.0.0.jar
#   ./scripts/fetch-deps.sh --force      # 忽略已有文件，全部重新下载
#
# 依赖全部落在 .mcserver/（已在 .gitignore 中排除），不会提交进仓库。
# 环境要求：bash、curl、python3（3.6+）。
set -euo pipefail

SELF="$0"
ROOT="$(cd "$(dirname "$SELF")/.." && pwd)"
DIR="$ROOT/.mcserver"
MC_VERSION="26.2"
LOADER_VERSION=""
API_VERSION=""
FORCE=0
DO_BUILD=0

MOJANG="https://piston-meta.mojang.com"
FABRIC_META="https://meta.fabricmc.net"
FABRIC_MAVEN="https://maven.fabricmc.net"

usage() {
    sed -n '2,9p' "$SELF" | sed 's/^# \{0,1\}//'
    exit 0
}

while [ $# -gt 0 ]; do
    case "$1" in
        --dir)    DIR="$2"; shift 2 ;;
        --mc)     MC_VERSION="$2"; shift 2 ;;
        --loader) LOADER_VERSION="$2"; shift 2 ;;
        --api)    API_VERSION="$2"; shift 2 ;;
        --force)  FORCE=1; shift ;;
        --build)  DO_BUILD=1; shift ;;
        -h|--help) usage ;;
        *) echo "未知参数: $1" >&2; exit 1 ;;
    esac
done

for cmd in curl python3; do
    command -v "$cmd" >/dev/null 2>&1 || { echo "缺少依赖: $cmd" >&2; exit 1; }
done

step() { printf '\033[36m==> %s\033[0m\n' "$*"; }
mkdir -p "$DIR"
DIR="$(cd "$DIR" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

step "目标目录: $DIR"

# ---------------------------------------------------------------- 1. 版本元数据
step '解析 Minecraft 版本元数据'
VERSION_URL="$(curl -fsSL "$MOJANG/mc/game/version_manifest_v2.json" | python3 -c '
import json, sys
mc = sys.argv[1]
for v in json.load(sys.stdin)["versions"]:
    if v["id"] == mc:
        print(v["url"])
        break
' "$MC_VERSION")"

if [ -z "$VERSION_URL" ]; then
    echo "Mojang 版本列表里没有 $MC_VERSION" >&2
    exit 1
fi
curl -fsSL "$VERSION_URL" -o "$TMP/version.json"

python3 -c '
import json, sys
d = json.load(open(sys.argv[1]))
print("    Minecraft %s (Java %s), %d 个库" % (d["id"], d["javaVersion"]["majorVersion"], len(d["libraries"])))
' "$TMP/version.json"

# ---------------------------------------------------------------- 2. 服务端 jar
SERVER_JAR="$DIR/versions/$MC_VERSION/server-$MC_VERSION.jar"
if [ -f "$SERVER_JAR" ] && [ "$FORCE" -eq 0 ]; then
    step "服务端 jar 已存在，跳过: $SERVER_JAR"
else
    step '下载并解出 Minecraft 服务端 jar'
    BUNDLER="$TMP/server-bundler.jar"
    SERVER_URL="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["downloads"]["server"]["url"])' "$TMP/version.json")"
    curl -fsSL --retry 5 --retry-delay 2 --connect-timeout 20 -o "$BUNDLER" "$SERVER_URL"
    mkdir -p "$(dirname "$SERVER_JAR")"
    # Mojang 发的是 bundler（内含 libraries 与真正的 server jar），需要把内层 jar 解出来
    if python3 - "$BUNDLER" "$SERVER_JAR" <<'EOPY'
import sys, zipfile
src, dest = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as z:
    names = [n for n in z.namelist() if n.startswith("META-INF/versions/") and n.endswith(".jar")]
    if not names:
        sys.exit(2)
    with z.open(names[0]) as f, open(dest, "wb") as out:
        out.write(f.read())
    print("    已从 bundler 解出: %s" % names[0])
EOPY
    then
        :
    else
        cp "$BUNDLER" "$SERVER_JAR"
        echo '    该版本服务端 jar 不是 bundler，直接使用'
    fi
fi

# ---------------------------------------------------------------- 3. 原版库
step '下载 Mojang 库'
LIB_DIR="$DIR/libraries"
mkdir -p "$LIB_DIR"
python3 -c '
import json, sys
d = json.load(open(sys.argv[1]))
for lib in d["libraries"]:
    art = lib.get("downloads", {}).get("artifact")
    if art and art.get("path"):
        sys.stdout.buffer.write((art["url"] + "\0" + art["path"] + "\0").encode())
' "$TMP/version.json" > "$TMP/libs.bin"

# 单个库下载失败不打断整轮，最后统一汇总
FAILED="$TMP/failed.txt"
: > "$FAILED"
cat > "$TMP/dl.sh" <<'EOFX'
#!/usr/bin/env bash
url="$1"; dest="$2"
mkdir -p "$(dirname "$dest")"
attempt=1
while [ "$attempt" -le 4 ]; do
    if curl -fsSL --connect-timeout 20 --max-time 300 -o "$dest" "$url"; then
        exit 0
    fi
    attempt=$((attempt + 1))
    sleep 2
done
echo "$url" >> "$FAILED_LIST"
EOFX
chmod +x "$TMP/dl.sh"

# 只下载缺失的文件（NUL 分隔的 url/路径对）
python3 - "$TMP/libs.bin" "$LIB_DIR" "$FORCE" > "$TMP/todo.bin" <<'EOPY'
import os, sys
bin_path, lib_dir, force = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
data = [x.decode() for x in open(bin_path, "rb").read().split(b"\0")[:-1]]
pairs = [(data[i], data[i + 1]) for i in range(0, len(data), 2)]
missing = [p for p in pairs if force or not os.path.exists(os.path.join(lib_dir, p[1]))]
print("    %d 个库，需下载 %d 个" % (len(pairs), len(missing)), file=sys.stderr)
for url, path in missing:
    sys.stdout.buffer.write((url + "\0" + os.path.join(lib_dir, path) + "\0").encode())
EOPY
if [ -s "$TMP/todo.bin" ]; then
    FAILED_LIST="$FAILED" xargs -0 -n2 -P8 -a "$TMP/todo.bin" "$TMP/dl.sh"
fi
if [ -s "$FAILED" ]; then
    echo "以下库下载失败，请检查网络后重跑（已下载的会自动跳过）：" >&2
    sed 's/^/    /' "$FAILED" >&2
    exit 1
fi

# ---------------------------------------------------------------- 4. Fabric Loader
step '解析 Fabric Loader'
if [ -z "$LOADER_VERSION" ]; then
    LOADER_VERSION="$(curl -fsSL "$FABRIC_META/v2/versions/loader/$MC_VERSION" | python3 -c '
import json, sys
data = json.load(sys.stdin)
if not data:
    sys.exit(1)
for entry in data:
    if entry["loader"]["stable"]:
        print(entry["loader"]["version"])
        break
else:
    print(data[0]["loader"]["version"])
')"
fi
[ -n "$LOADER_VERSION" ] || { echo "Fabric 还不支持 Minecraft $MC_VERSION" >&2; exit 1; }
echo "    Loader $LOADER_VERSION"

curl -fsSL "$FABRIC_META/v2/versions/loader/$MC_VERSION/$LOADER_VERSION/profile/json" -o "$TMP/loader.json"
python3 -c '
import json, sys
d = json.load(open(sys.argv[1]))
for lib in d["libraries"]:
    base = lib.get("url") or "https://maven.fabricmc.net/"
    group, artifact, version = lib["name"].split(":")[:3]
    path = "%s/%s/%s/%s-%s.jar" % (group.replace(".", "/"), artifact, version, artifact, version)
    sys.stdout.buffer.write((base.rstrip("/") + "/" + path + "\0" + path + "\0").encode())
' "$TMP/loader.json" > "$TMP/loader.bin"

while IFS= read -r -d '' url; do
    IFS= read -r -d '' rel || break
    dest="$LIB_DIR/$rel"
    if [ ! -f "$dest" ] || [ "$FORCE" -eq 1 ]; then
        mkdir -p "$(dirname "$dest")"
        curl -fsSL --retry 5 --retry-delay 2 --connect-timeout 20 -o "$dest" "$url"
    fi
done < "$TMP/loader.bin"

# ---------------------------------------------------------------- 5. Fabric API
step '解析 Fabric API'
if [ -z "$API_VERSION" ]; then
    API_VERSION="$(curl -fsSL "$FABRIC_MAVEN/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml" | python3 -c '
import sys, xml.etree.ElementTree as ET
mc = sys.argv[1]
root = ET.fromstring(sys.stdin.read())
versions = [v.text for v in root.iter("version") if v.text and v.text.endswith("+" + mc)]
print(versions[-1] if versions else "")
' "$MC_VERSION")"
fi
[ -n "$API_VERSION" ] || { echo "找不到适配 $MC_VERSION 的 Fabric API，请用 --api 指定" >&2; exit 1; }
echo "    Fabric API $API_VERSION"

API_JAR="$DIR/mods/fabric-api-$API_VERSION.jar"
if [ ! -f "$API_JAR" ] || [ "$FORCE" -eq 1 ]; then
    mkdir -p "$(dirname "$API_JAR")"
    curl -fsSL --retry 5 --retry-delay 2 --connect-timeout 20 -o "$API_JAR" \
        "$FABRIC_MAVEN/net/fabricmc/fabric-api/fabric-api/$API_VERSION/fabric-api-$API_VERSION.jar"
fi

# ---------------------------------------------------------------- 6. 拆 bundle
# Fabric API 的发布 jar 是 bundle，模块在 META-INF/jars/ 里；javac 看不到嵌套 jar
PROCESSED="$DIR/.fabric/processedMods"
COUNT=0
if [ -d "$PROCESSED" ]; then
    COUNT="$(find "$PROCESSED" -maxdepth 1 -name '*.jar' | wc -l)"
fi
if [ "$COUNT" -gt 0 ] && [ "$FORCE" -eq 0 ]; then
    step "Fabric API 模块已拆分过（$COUNT 个），跳过"
else
    step '拆分 Fabric API 模块'
    mkdir -p "$PROCESSED"
    python3 - "$API_JAR" "$PROCESSED" <<'EOPY'
import os, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
n = 0
with zipfile.ZipFile(src) as z:
    for name in z.namelist():
        if name.startswith("META-INF/jars/") and name.endswith(".jar"):
            data = z.read(name)
            with open(os.path.join(out, os.path.basename(name)), "wb") as f:
                f.write(data)
            n += 1
if n == 0:
    sys.exit("Fabric API 包里没有嵌套模块，可能不是 bundle")
print("    拆出 %d 个模块" % n)
EOPY
fi

# ---------------------------------------------------------------- 7. 汇总
step '依赖就绪'
if [ -f "$SERVER_JAR" ]; then SERVER_OK=True; else SERVER_OK=False; fi
echo "    服务端 jar : $SERVER_OK"
echo "    libraries  : $(find "$LIB_DIR" -name '*.jar' | wc -l) 个 jar"
echo "    fabric-api : $API_JAR"
echo "    loader     : $LOADER_VERSION"

if [ "$DO_BUILD" -eq 1 ]; then
    step '开始构建'
    MC_SERVER="$DIR" "$ROOT/build.sh"
else
    echo
    echo "下一步: MC_SERVER=$DIR ./build.sh"
    echo '或直接用 ./scripts/fetch-deps.sh --build'
fi
