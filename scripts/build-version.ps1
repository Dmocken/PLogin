# build-version.ps1 - 按 Minecraft 版本构建 PLogin（Windows）
#
#   .\scripts\build-version.ps1 -McVersion 26.1
#   .\scripts\build-version.ps1 -McVersion 26.3 -ModVersion 1.1.1
#
# 依赖目录默认 .mcserver-<McVersion>（用 scripts/fetch-deps.ps1 -McVersion <ver> -Dir .mcserver-<ver> 拉取），
# 产物：build/PLogin-<McVersion>-v<ModVersion>.jar
#
# 各版本差异以「源码文本替换」的形式集中在本文件里，方便审阅与维护：
#   - 26.1 / 26.2：源码不变，只改 fabric.mod.json 的版本号
#   - 26.3：authlib 10（MinecraftSessionService -> SessionService、yggdrasil.ProfileResult -> services.ProfileResult）
#           以及 Entity#setInvulnerable -> setPermanentlyInvulnerable
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$McVersion,
    [string]$ModVersion = '1.1.1',
    [string]$DepsDir = '',
    [switch]$KeepSources
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

# 1.21.x 的服务端 jar 是混淆的、Fabric 运行时是 intermediary，javac 直编出来的 jar 无法被加载，
# 必须走 build.gradle.kts（Loom）这条管线：反混淆编译 + remapJar 到 intermediary。
if ($McVersion -match '^1\.21\.') {
    Write-Error "Minecraft $McVersion 不能用 javac 直编，请改用：.\gradlew build -Pmc_version=$McVersion -Pfabric_api_version=<对应版本>"
}

if (-not $DepsDir) { $DepsDir = Join-Path $PWD ".mcserver-$McVersion" }
$DepsDir = [System.IO.Path]::GetFullPath($DepsDir)

$serverJar = Join-Path $DepsDir "versions/$McVersion/server-$McVersion.jar"
if (-not (Test-Path $serverJar)) {
    Write-Error "找不到 $serverJar"
    Write-Error "请先运行：.\scripts\fetch-deps.ps1 -McVersion $McVersion -Dir $DepsDir"
}

Write-Host "[1/5] 依赖目录: $DepsDir"

# ---------------------------------------------------------------- 2. 复制源码并打版本补丁
$workSrc = Join-Path $PWD "build/src-$McVersion"
Remove-Item $workSrc -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $workSrc | Out-Null
Copy-Item 'src/*' $workSrc -Recurse -Force

function Set-SourceText([string]$Path, [string]$Old, [string]$New) {
    $text = Get-Content $Path -Raw
    $updated = $text.Replace($Old, $New)
    if ($updated -ne $text) { Set-Content -Path $Path -Value $updated -Encoding UTF8 -NoNewline }
}

$patchedFiles = 0
switch ($McVersion) {
    '26.3' {
        # authlib 10：包名/类型重构 + Entity#setInvulnerable 改名
        foreach ($file in Get-ChildItem $workSrc -Recurse -Filter '*.java' -File) {
            $before = Get-Content $file.FullName -Raw
            $text = $before
            # 点号形式（import / 类型名）与斜杠形式（Mixin 注解里的描述符）都要替换
            $text = $text -replace 'com\.mojang\.authlib\.minecraft\.MinecraftSessionService', 'com.mojang.authlib.minecraft.SessionService'
            $text = $text -replace 'com/mojang/authlib/minecraft/MinecraftSessionService', 'com/mojang/authlib/minecraft/SessionService'
            $text = $text -replace 'com\.mojang\.authlib\.yggdrasil\.ProfileResult', 'com.mojang.authlib.services.ProfileResult'
            $text = $text -replace 'com/mojang/authlib/yggdrasil/ProfileResult', 'com/mojang/authlib/services/ProfileResult'
            $text = $text -replace '\bMinecraftSessionService\b', 'SessionService'
            $text = $text -replace '\.setInvulnerable\(', '.setPermanentlyInvulnerable('
            if ($text -ne $before) {
                Set-Content -Path $file.FullName -Value $text -Encoding UTF8 -NoNewline
                $patchedFiles++
            }
        }
        # 防回归：26.3 上不允许残留旧类型名
        $leftover = @(Get-ChildItem $workSrc -Recurse -Filter '*.java' -File |
            Select-String -Pattern 'MinecraftSessionService|authlib/yggdrasil/ProfileResult|setInvulnerable\(')
        if ($leftover.Count -gt 0) {
            Write-Error "26.3 补丁未完全应用：$($leftover[0].Path):$($leftover[0].LineNumber)"
        }
    }
    default {
        # 26.1 / 26.2：源码无需改动
    }
}
Write-Host "[2/5] 源码副本: $workSrc（版本补丁命中 $patchedFiles 个文件）"

# fabric.mod.json：目标版本、模组版本、描述里的版本号
$modJson = Join-Path $workSrc 'fabric.mod.json'
Set-SourceText $modJson '"minecraft": "26.2"' ('"minecraft": "' + $McVersion + '"')
Set-SourceText $modJson '"version": "1.1.1"' ('"version": "' + $ModVersion + '"')
Set-SourceText $modJson 'Minecraft 26.2 servers' ("Minecraft " + $McVersion + " servers")

# ---------------------------------------------------------------- 3. 组装 classpath
$outDir = Join-Path $PWD "build/classes-$McVersion"
Remove-Item $outDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $outDir, (Join-Path $PWD 'build') | Out-Null

$cp = New-Object System.Collections.Generic.List[string]
$cp.Add($serverJar)
$modsDir = Join-Path $DepsDir 'mods'
if (Test-Path $modsDir) {
    Get-ChildItem $modsDir -Filter 'fabric-api-*.jar' -File -ErrorAction SilentlyContinue |
        ForEach-Object { $cp.Add($_.FullName) }
}
$processed = Join-Path $DepsDir '.fabric/processedMods'
if (Test-Path $processed) {
    Get-ChildItem $processed -Filter '*.jar' -File -ErrorAction SilentlyContinue | ForEach-Object { $cp.Add($_.FullName) }
}
$libDir = Join-Path $DepsDir 'libraries'
if (Test-Path $libDir) {
    Get-ChildItem $libDir -Recurse -Filter '*.jar' -File -ErrorAction SilentlyContinue | ForEach-Object { $cp.Add($_.FullName) }
}
Write-Host "[3/5] classpath: $($cp.Count) 个 jar"

# ---------------------------------------------------------------- 4. 编译
$sources = Get-ChildItem $workSrc -Recurse -Filter '*.java' -File | ForEach-Object { $_.FullName }
if (-not $sources) { Write-Error "$workSrc 下没有找到 .java 文件" }

$argFile = Join-Path $PWD "build/javac-$McVersion.args"
$lines = @(
    '-proc:none',
    '--release', '21',
    '-nowarn',
    '-encoding', 'UTF-8',
    '-cp', ('"' + (($cp -join ';') -replace '\\', '/') + '"'),
    '-d', ('"' + ($outDir -replace '\\', '/') + '"')
)
$lines += $sources | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' }
Set-Content -Path $argFile -Value $lines -Encoding UTF8

Write-Host '[4/5] javac 编译中...'
& javac "@$argFile"
if ($LASTEXITCODE -ne 0) { Write-Error "javac 失败（exit $LASTEXITCODE）" }

Copy-Item (Join-Path $workSrc 'fabric.mod.json'), (Join-Path $workSrc 'plogin.mixins.json') -Destination $outDir -Force
if (Test-Path (Join-Path $workSrc 'data')) { Copy-Item (Join-Path $workSrc 'data') -Destination $outDir -Recurse -Force }

# ---------------------------------------------------------------- 5. 打包
$jarPath = Join-Path $PWD "build/PLogin-$McVersion-v$ModVersion.jar"
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }
& jar --create --file $jarPath -C $outDir .
if ($LASTEXITCODE -ne 0) { Write-Error "jar 打包失败（exit $LASTEXITCODE）" }

if (-not $KeepSources) {
    Remove-Item $workSrc -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $outDir -Recurse -Force -ErrorAction SilentlyContinue
}

$hash = (Get-FileHash $jarPath -Algorithm SHA256).Hash.ToLower()
Write-Host "[5/5] built $jarPath ($((Get-Item $jarPath).Length) bytes)"
Write-Host "      SHA256: $hash"
