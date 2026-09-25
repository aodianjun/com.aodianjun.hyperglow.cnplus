# Release contract validator (issue #68 #7, Bridge 同型)。
# 结构性校验在 CI 每个 PR/main push 上运行(不校验版本新鲜度,避免发版流程外
# 的版本号提交把 CI 弄红);-ApkPath 提供时额外扫描 release APK 的禁止字符串。
# 发版前人工严格校验: pwsh ./scripts/validate-release-contract.ps1 -RepoRoot . -ExpectedVersion 0.3.114 -ExpectedVersionCode 141
param(
    [string] $RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [string] $ApkPath = '',
    [string] $ExpectedVersion = '',
    [string] $ExpectedVersionCode = ''
)

$ErrorActionPreference = 'Stop'

function Assert-Contract {
    param([bool] $Condition, [string] $Message)
    if (-not $Condition) {
        throw "Release contract violation: $Message"
    }
}

$contractPath = Join-Path $RepoRoot 'release/cnplus-release-contract.json'
$buildFilePath = Join-Path $RepoRoot 'app/build.gradle.kts'
$contract = Get-Content -LiteralPath $contractPath -Raw | ConvertFrom-Json
$buildFile = Get-Content -LiteralPath $buildFilePath -Raw

# 1. schema 与契约自身一致性
Assert-Contract ($contract.schema -eq 1) 'unsupported contract schema'
Assert-Contract ($contract.forbiddenReleaseApkStrings.Count -ge 1) 'forbidden APK string set must not be empty'
Assert-Contract (($contract.forbiddenReleaseApkStrings | Select-Object -Unique).Count -eq $contract.forbiddenReleaseApkStrings.Count) 'forbidden APK strings contain duplicates'

# 2. applicationId 与 gradle 一致
Assert-Contract ($buildFile -match ('applicationId\s*=\s*"' + [regex]::Escape($contract.applicationId) + '"')) 'applicationId differs from contract'

# 3. 版本号格式与(可选的)显式期望一致
$versionName = [regex]::Match($buildFile, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$versionCode = [regex]::Match($buildFile, 'versionCode\s*=\s*(\d+)').Groups[1].Value
Assert-Contract ($versionName -match '^\d+\.\d+\.\d+$') "versionName '$versionName' must be semver"
Assert-Contract ($versionCode -match '^\d+$') "versionCode '$versionCode' must be numeric"
if ($ExpectedVersion -ne '') {
    Assert-Contract ($versionName -eq $ExpectedVersion) "versionName '$versionName' differs from expected '$ExpectedVersion'"
}
if ($ExpectedVersionCode -ne '') {
    Assert-Contract ($versionCode -eq $ExpectedVersionCode) "versionCode '$versionCode' differs from expected '$ExpectedVersionCode'"
}

# 4. 发布 tag 约定(<code>-<version>,LSPosed 仓库规范)
$releaseTag = "$versionCode-$versionName"
Assert-Contract ($releaseTag -match $contract.releaseTagPattern) "release tag '$releaseTag' does not match contract pattern"

# 5. 资产命名模板可由当前版本号实例化(占位符 <version>/<code> 替换后必须精确命中)
$releaseApkName = $contract.assetNameTemplates.releaseApk.Replace('<version>', $versionName).Replace('<code>', $versionCode)
$debugApkName = $contract.assetNameTemplates.debugApk.Replace('<version>', $versionName).Replace('<code>', $versionCode)
Assert-Contract ($releaseApkName -eq "hyperglow-cnplus-release-v$versionName-$versionCode.apk") "release asset name '$releaseApkName' does not follow the contract template"
Assert-Contract ($debugApkName -eq "hyperglow-cnplus-debug-v$versionName-$versionCode.apk") "debug asset name '$debugApkName' does not follow the contract template"

# 6. 必含文档/契约文件存在
foreach ($required in @($contract.requiredFiles)) {
    $p = Join-Path $RepoRoot $required
    Assert-Contract (Test-Path -LiteralPath $p) "required file missing: $required"
}

# 7. 可选:扫描 release APK 的禁止字符串(默认 UTF-8/Latin 双编码字节查找)
if ($ApkPath -ne '') {
    Assert-Contract (Test-Path -LiteralPath $ApkPath) "APK not found: $ApkPath"
    $bytes = [System.IO.File]::ReadAllBytes($ApkPath)
    $latin = [System.Text.Encoding]::GetEncoding(28591).GetString($bytes)
    foreach ($forbidden in @($contract.forbiddenReleaseApkStrings)) {
        Assert-Contract (-not $latin.Contains($forbidden)) "release APK contains forbidden string '$forbidden'"
    }
    Write-Host "APK scan passed: $ApkPath ($($bytes.Length) bytes, $($contract.forbiddenReleaseApkStrings.Count) forbidden strings checked)"
}

Write-Host 'Release contract OK'
