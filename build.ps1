# build.ps1 - Windows 版构建脚本，与 build.sh 等价
# 直接使用服务端 jar + Fabric API 模块编译（不需要 Gradle/Loom，运行环境是 Mojang mappings）
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $MyInvocation.MyCommand.Path)

function Resolve-McServer {
    if ($env:MC_SERVER) { return $env:MC_SERVER }
    $candidates = @(
        (Join-Path $PWD '.mcserver'),
        (Join-Path (Split-Path $PWD -Parent) 'minecraft-bingo'),
        (Join-Path $HOME 'mc/minecraft-bingo')
    )
    foreach ($c in $candidates) {
        if (Test-Path (Join-Path $c 'versions/26.2/server-26.2.jar')) { return $c }
    }
    return (Join-Path $PWD '.mcserver')
}

$mc = Resolve-McServer
$serverJar = Join-Path $mc 'versions/26.2/server-26.2.jar'
if (-not (Test-Path $serverJar)) {
    Write-Error "找不到 $serverJar"
    Write-Error "请用 MC_SERVER 环境变量指定一个已安装 Fabric + Fabric API 的服务端目录（仓库不附带 Minecraft 服务端 jar）"
}

Write-Host "[1/4] 依赖目录: $mc"

$outDir = Join-Path $PWD 'build/classes'
$jarPath = Join-Path $PWD 'build/plogin-1.0.0.jar'
Remove-Item $outDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $outDir, (Join-Path $PWD 'build') | Out-Null

$cp = New-Object System.Collections.Generic.List[string]
$cp.Add($serverJar)
$modsDir = Join-Path $mc 'mods'
if (Test-Path $modsDir) {
    Get-ChildItem $modsDir -Filter 'fabric-api-*.jar' -File -ErrorAction SilentlyContinue |
        ForEach-Object { $cp.Add($_.FullName) }
}
$processed = Join-Path $mc '.fabric/processedMods'
if (Test-Path $processed) {
    Get-ChildItem $processed -Filter '*.jar' -File -ErrorAction SilentlyContinue | ForEach-Object { $cp.Add($_.FullName) }
}
$libDir = Join-Path $mc 'libraries'
if (Test-Path $libDir) {
    Get-ChildItem $libDir -Recurse -Filter '*.jar' -File -ErrorAction SilentlyContinue | ForEach-Object { $cp.Add($_.FullName) }
}
Write-Host "[2/4] classpath: $($cp.Count) 个 jar"

$sources = Get-ChildItem 'src' -Recurse -Filter '*.java' -File | ForEach-Object { $_.FullName }
if (-not $sources) { Write-Error 'src 下没有找到 .java 文件' }

$argFile = Join-Path $PWD 'build/javac.args'
$lines = @(
    '-proc:none',
    '--release', '21',
    '-nowarn',
    '-encoding', 'UTF-8',
    '-cp', ('"' + (($cp -join ';') -replace '\\', '/') + '"'),
    '-d', ('"' + ($outDir -replace '\\', '/') + '"')
)
# javac 的 @argfile 会把反斜杠当转义符，路径统一用正斜杠
$lines += $sources | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' }
Set-Content -Path $argFile -Value $lines -Encoding UTF8

Write-Host '[3/4] javac 编译中...'
& javac "@$argFile"
if ($LASTEXITCODE -ne 0) { Write-Error "javac 失败（exit $LASTEXITCODE）" }

Copy-Item 'src/fabric.mod.json', 'src/plogin.mixins.json' -Destination $outDir -Force
if (Test-Path 'src/data') { Copy-Item 'src/data' -Destination $outDir -Recurse -Force }

Write-Host '[4/4] 打包 jar...'
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }
& jar --create --file $jarPath -C $outDir .
if ($LASTEXITCODE -ne 0) { Write-Error "jar 打包失败（exit $LASTEXITCODE）" }

$size = (Get-Item $jarPath).Length
Write-Host "built $jarPath ($size bytes)"
