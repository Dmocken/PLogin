# fetch-deps.ps1 - 一键拉取 Minecraft 26.2 + Fabric 依赖，并（可选）直接构建
#
#   .\scripts\fetch-deps.ps1            # 只拉依赖到 .mcserver/
#   .\scripts\fetch-deps.ps1 -Build     # 拉完直接编译出 build/plogin-1.0.1.jar
#   .\scripts\fetch-deps.ps1 -Force     # 忽略已有文件，全部重新下载
#
# 依赖全部落在 .mcserver/（已在 .gitignore 中排除），不会提交进仓库。
[CmdletBinding()]
param(
    [string]$Dir = '',
    [string]$McVersion = '26.2',
    [string]$LoaderVersion = '',
    [string]$ApiVersion = '',
    [switch]$Force,
    [switch]$Build
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root = Split-Path -Parent $PSScriptRoot
if (-not $Dir) { $Dir = Join-Path $root '.mcserver' }
$Dir = [System.IO.Path]::GetFullPath($Dir)

$mojangMeta = 'https://piston-meta.mojang.com'
$fabricMeta = 'https://meta.fabricmc.net'
$fabricMaven = 'https://maven.fabricmc.net'

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }

function Get-Json($url) {
    Invoke-RestMethod -Uri $url -TimeoutSec 60
}

function Save-Remote($url, $dest) {
    $parent = Split-Path $dest -Parent
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    if ((Test-Path $dest) -and -not $Force) { return $false }
    Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing -TimeoutSec 300
    return $true
}

function Expand-ZipEntry($zipPath, $entry, $dest) {
    $parent = Split-Path $dest -Parent
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        $target = $zip.Entries | Where-Object { $_.FullName -eq $entry } | Select-Object -First 1
        if (-not $target) { throw "jar 里没有 $entry" }
        $stream = $target.Open()
        $file = [System.IO.File]::Create($dest)
        try { $stream.CopyTo($file) } finally { $file.Dispose(); $stream.Dispose() }
    } finally { $zip.Dispose() }
}

Write-Step "目标目录: $Dir"

# ---------------------------------------------------------------- 1. 版本元数据
Write-Step '解析 Minecraft 版本元数据'
$manifest = Get-Json "$mojangMeta/mc/game/version_manifest_v2.json"
$versionEntry = $manifest.versions | Where-Object { $_.id -eq $McVersion } | Select-Object -First 1
if (-not $versionEntry) {
    throw "Mojang 版本列表里没有 $McVersion"
}
$versionJson = Get-Json $versionEntry.url
Write-Host "    Minecraft $McVersion (Java $($versionJson.javaVersion.majorVersion)), $($versionJson.libraries.Count) 个库"

# ---------------------------------------------------------------- 2. 服务端 jar
$serverDir = Join-Path $Dir "versions/$McVersion"
$serverJar = Join-Path $serverDir "server-$McVersion.jar"
if ((Test-Path $serverJar) -and -not $Force) {
    Write-Step "服务端 jar 已存在，跳过: $serverJar"
} else {
    Write-Step '下载并解出 Minecraft 服务端 jar'
    $bundler = Join-Path $Dir 'server-bundler.jar'
    Save-Remote $versionJson.downloads.server.url $bundler | Out-Null
    # Mojang 发的是 bundler（内含 libraries 与真正的 server jar），需要把内层 jar 解出来
    $zip = [System.IO.Compression.ZipFile]::OpenRead($bundler)
    $innerEntry = $zip.Entries | Where-Object { $_.FullName -like 'META-INF/versions/*.jar' } | Select-Object -First 1
    $innerName = if ($innerEntry) { $innerEntry.FullName } else { $null }
    $zip.Dispose()
    if ($innerName) {
        Expand-ZipEntry $bundler $innerName $serverJar
        Write-Host "    已从 bundler 解出: $innerName"
    } else {
        Copy-Item $bundler $serverJar -Force
        Write-Host '    该版本服务端 jar 不是 bundler，直接使用'
    }
    Remove-Item $bundler -Force -ErrorAction SilentlyContinue
}

# ---------------------------------------------------------------- 3. 原版库
Write-Step '下载 Mojang 库'
$libDir = Join-Path $Dir 'libraries'
$items = New-Object System.Collections.Generic.List[object]
foreach ($lib in $versionJson.libraries) {
    $artifact = $lib.downloads.artifact
    if ($artifact -and $artifact.path) {
        $items.Add([pscustomobject]@{ url = $artifact.url; path = $artifact.path })
    }
}
$todo = @($items | Where-Object { $Force -or -not (Test-Path (Join-Path $libDir $_.path)) })
Write-Host "    $($items.Count) 个库，需下载 $($todo.Count) 个"
$todo | ForEach-Object -Parallel {
    $dest = Join-Path $using:libDir $_.path
    $parent = Split-Path $dest -Parent
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        try {
            Invoke-WebRequest -Uri $_.url -OutFile $dest -UseBasicParsing -TimeoutSec 300
            break
        } catch {
            if ($attempt -eq 4) { Write-Host "    下载失败: $($_.path)" -ForegroundColor Yellow }
            else { Start-Sleep -Seconds 2 }
        }
    }
} -ThrottleLimit 8

$missing = @($todo | Where-Object { -not (Test-Path (Join-Path $libDir $_.path)) })
if ($missing.Count -gt 0) {
    Write-Host "以下 $($missing.Count) 个库下载失败，请检查网络后重跑（已下载的会自动跳过）：" -ForegroundColor Red
    $missing | ForEach-Object { Write-Host "    $($_.path)" }
    exit 1
}

# ---------------------------------------------------------------- 4. Fabric Loader
Write-Step '解析 Fabric Loader'
$loaderList = Get-Json "$fabricMeta/v2/versions/loader/$McVersion"
if (-not $loaderList -or $loaderList.Count -eq 0) { throw "Fabric 还不支持 Minecraft $McVersion" }
if (-not $LoaderVersion) {
    $stable = $loaderList | Where-Object { $_.loader.stable } | Select-Object -First 1
    $LoaderVersion = if ($stable) { $stable.loader.version } else { $loaderList[0].loader.version }
}
Write-Host "    Loader $LoaderVersion"
$profile = Get-Json "$fabricMeta/v2/versions/loader/$McVersion/$LoaderVersion/profile/json"
foreach ($lib in $profile.libraries) {
    $base = $lib.url
    if (-not $base) { $base = $fabricMaven }
    $p = $lib.name -split ':'
    $rel = ($p[0] -replace '\.', '/') + '/' + $p[1] + '/' + $p[2] + '/' + $p[1] + '-' + $p[2] + '.jar'
    Save-Remote ($base.TrimEnd('/') + '/' + $rel) (Join-Path $libDir $rel) | Out-Null
}

# ---------------------------------------------------------------- 5. Fabric API
Write-Step '解析 Fabric API'
if (-not $ApiVersion) {
    $xmlText = (Invoke-WebRequest "$fabricMaven/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml" -UseBasicParsing -TimeoutSec 60).Content
    $xml = [xml]$xmlText
    $ApiVersion = $xml.metadata.versioning.versions.version |
        Where-Object { $_ -like "*+$McVersion" } |
        Select-Object -Last 1
}
if (-not $ApiVersion) { throw "找不到适配 $McVersion 的 Fabric API，请用 -ApiVersion 手动指定" }
Write-Host "    Fabric API $ApiVersion"

$apiJar = Join-Path $Dir "mods/fabric-api-$ApiVersion.jar"
$apiUrl = "$fabricMaven/net/fabricmc/fabric-api/fabric-api/$ApiVersion/fabric-api-$ApiVersion.jar"
Save-Remote $apiUrl $apiJar | Out-Null

# ---------------------------------------------------------------- 6. 拆 bundle
# Fabric API 的发布 jar 是 bundle，模块在 META-INF/jars/ 里；javac 看不到嵌套 jar
$processedDir = Join-Path $Dir '.fabric/processedMods'
$existing = @(Get-ChildItem $processedDir -Filter '*.jar' -File -ErrorAction SilentlyContinue)
if ($existing.Count -gt 0 -and -not $Force) {
    Write-Step "Fabric API 模块已拆分过（$($existing.Count) 个），跳过"
} else {
    Write-Step '拆分 Fabric API 模块'
    New-Item -ItemType Directory -Force -Path $processedDir | Out-Null
    $zip = [System.IO.Compression.ZipFile]::OpenRead($apiJar)
    $inner = @($zip.Entries | Where-Object { $_.FullName -like 'META-INF/jars/*.jar' })
    foreach ($entry in $inner) {
        $name = [System.IO.Path]::GetFileName($entry.FullName)
        $dest = Join-Path $processedDir $name
        $src = $entry.Open()
        $file = [System.IO.File]::Create($dest)
        try { $src.CopyTo($file) } finally { $file.Dispose(); $src.Dispose() }
    }
    $zip.Dispose()
    Write-Host "    拆出 $($inner.Count) 个模块"
}

# ---------------------------------------------------------------- 7. 汇总
Write-Step '依赖就绪'
$libCount = @(Get-ChildItem $libDir -Recurse -Filter '*.jar' -File -ErrorAction SilentlyContinue).Count
Write-Host "    服务端 jar : $(Test-Path $serverJar)"
Write-Host "    libraries  : $libCount 个 jar"
Write-Host "    fabric-api : $apiJar"
Write-Host "    loader     : $LoaderVersion"

if ($Build) {
    Write-Step '开始构建'
    $env:MC_SERVER = $Dir
    & (Join-Path $root 'build.ps1')
} else {
    Write-Host ''
    Write-Host "下一步: 设置环境变量 MC_SERVER=$Dir 后运行 .\build.ps1" -ForegroundColor Green
    Write-Host '或直接重跑本脚本并加 -Build' -ForegroundColor Green
}
