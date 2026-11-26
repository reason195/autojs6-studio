#!/usr/bin/env pwsh
# 注意：本文件必须保存为 **UTF-8 with BOM**。Windows PowerShell 5.1 会把无 BOM 的 .ps1 当
# ANSI（简中系统是 GBK）读，中文注释里的多字节序列会把后面的 `param(...)` 一起吃掉，
# 于是 -Required 变成普通参数、校验静默失效（实测踩过）。pwsh 7 默认 UTF-8，不受影响。
# 给 Gradle 准备 Android SDK：写 local.properties，缺的 platform/build-tools 用镜像里的
# cmdline-tools 补上。
#
# 托管 runner（windows-latest）自带 SDK，用 -Required：拿不到 SDK 就直接失败。
# self-hosted runner 上可能只在本目录放了 local.properties，不加 -Required 时容忍这种情况。
#
# 背景：这里原来用 android-actions/setup-android@v3，它内部执行 `sdkmanager tools`——
# 这个包早已下架，CI 每次都在 20 秒内死在这一步（`Warning: Failed to find package 'tools'`），
# 20 多次提交没编译过一行。
param([switch]$Required)

$ErrorActionPreference = 'Stop'

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }

if (-not $sdk) {
    if ($Required) { throw 'Neither ANDROID_HOME nor ANDROID_SDK_ROOT is set on this runner' }
    if (Test-Path local.properties) {
        Write-Host 'ANDROID_HOME / ANDROID_SDK_ROOT 未设置，沿用仓库里已有的 local.properties'
        return
    }
    throw '需要 Android SDK：设置 ANDROID_HOME，或在本目录放 local.properties'
}

Write-Host "Android SDK: $sdk"
"sdk.dir=$($sdk -replace '\\','/')" | Out-File -Encoding ascii -NoNewline local.properties

$missing = @('platforms;android-35', 'build-tools;35.0.0' | Where-Object {
    -not (Test-Path (Join-Path $sdk ($_.Replace(';', '/'))))
})
Write-Host "Missing: $($missing -join ', ')"
if ($missing.Count -gt 0) {
    $sdkmanager = Get-ChildItem (Join-Path $sdk 'cmdline-tools') -Recurse -Filter sdkmanager.bat -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $sdkmanager) { throw "sdkmanager.bat not found, cannot install: $($missing -join ', ')" }
    & $sdkmanager.FullName "--sdk_root=$sdk" @missing
    if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed with exit code $LASTEXITCODE" }
}
