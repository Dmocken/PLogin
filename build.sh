#!/usr/bin/env bash
# 直接使用服务端 jar + Fabric API 模块编译（不需要 Gradle/Loom，运行环境是 Mojang mappings）
set -euo pipefail
cd "$(dirname "$0")"

# 依赖来源：显式指定的 MC_SERVER，或自动探测常见位置
if [ -z "${MC_SERVER:-}" ]; then
  for candidate in "$PWD/.mcserver" "$PWD/../minecraft-bingo" "$HOME/mc/minecraft-bingo"; do
    if [ -f "$candidate/versions/26.2/server-26.2.jar" ]; then
      MC_SERVER="$candidate"
      break
    fi
  done
fi
MC_SERVER="${MC_SERVER:-$PWD/.mcserver}"

OUT=build/classes
JAR=build/plogin-1.0.0.jar

if [ ! -f "$MC_SERVER/versions/26.2/server-26.2.jar" ]; then
  echo "找不到 $MC_SERVER/versions/26.2/server-26.2.jar" >&2
  echo "请用 MC_SERVER=/path/to/minecraft-server ./build.sh" >&2
  echo "指向一个已安装 Fabric + Fabric API 的服务端目录（仓库不附带 Minecraft 服务端 jar）" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"

CP="$MC_SERVER/versions/26.2/server-26.2.jar"
while IFS= read -r j; do CP="$CP:$j"; done < <(find "$MC_SERVER/mods" -name 'fabric-api-*.jar' | sort)
while IFS= read -r j; do CP="$CP:$j"; done < <(find "$MC_SERVER/.fabric/processedMods" -name '*.jar' | sort)
while IFS= read -r j; do CP="$CP:$j"; done < <(find "$MC_SERVER/libraries" -name '*.jar' | sort)

find src -name '*.java' > build/sources.txt
javac -proc:none --release 21 -nowarn -encoding UTF-8 -cp "$CP" -d "$OUT" @build/sources.txt

cp src/fabric.mod.json src/plogin.mixins.json "$OUT"/
if [ -d src/data ]; then
  cp -r src/data "$OUT"/
fi
(cd "$OUT" && jar --create --file "../../$JAR" .)
echo "built $JAR ($(stat -c%s "$JAR") bytes)"
