param([switch]$Execute)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# One-time, exact allowlist. Bytes and file counts are the 2026-10-17 inventory.
# Voice, current V12.67 work, source locks, model sources and both linked Python
# environments are intentionally absent from this list.
$rawTargets = @'
33289760194|2223|calendar_v12_66_qwen3_4b
22952916224|2298|calendar_small_models_20261003_v2
17092113056|1898|calendar_v12_66_qwen35
16953034492|2176|calendar_v12_66_qwen3_17b/qwen3_1_7b
9181503826|2109|calendar_v12_66_qwen3_06b/qwen3_0_6b
8061119521|37|calendar_sft_v12_3_q4_work_20260909
8061041370|23|calendar_sft_v12_53_q4_work_20260910
8061029250|23|calendar_sft_v12_63_q4_work_20261001
8061028714|23|calendar_sft_v12_57_q4_work_20260914
8061028635|23|calendar_sft_v12_61_q4_work_20260928
8061028628|23|calendar_sft_v12_62_q4_work_20260929
8061028588|23|calendar_sft_v12_6_q4_work_20260914
8061028552|23|calendar_sft_v12_55_q4_work_20260912
8061028551|23|calendar_sft_v12_54_q4_work_20260911
8061028548|23|calendar_sft_v12_56_q4_work_20260913
8060911665|12|calendar_sft_models/Qwen3-4B-Instruct-2507
8060897829|12|calendar_sft_qwen3_v12_merged_bf16_20260907
4571273746|25|calendar_sft_models/Qwen3.5-2B
1536251466|346|calendar_sft_qwen3_v12_63_epoch1_20261001
582280370|40|calendar_sft_qwen3_v12_61_resume_20260929
579792891|44|calendar_sft_qwen3_v12_54_epoch1_20260911
579668548|40|calendar_sft_qwen3_v12_62_epoch1_20260929
579664957|40|calendar_sft_qwen3_v12_6_epoch1_20260914
579664616|40|calendar_sft_qwen3_v12_57_epoch1_20260914
579664595|40|calendar_sft_qwen3_v12_56_epoch1_20260913
579664423|40|calendar_sft_qwen3_v12_53_epoch1_20260910
579664259|40|calendar_sft_qwen3_v12_55_epoch1_20260912
579659713|40|calendar_sft_v6_epoch1_20260827
579659691|40|calendar_sft_local_epoch1_model
579659684|40|calendar_sft_local_epoch2_model
579659290|40|calendar_sft_v5_epoch1_20260827
431581658|30|calendar_sft_qwen3_v12_61_epoch1_20260928
363869259|25|calendar_sft_local_pilot
'@

function Measure-ExactTree([string]$LiteralPath) {
    $fileCount = 0L
    $byteCount = 0L
    $rootItem = Get-Item -LiteralPath $LiteralPath -Force -ErrorAction Stop
    if (($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Reparse point at target root: $LiteralPath"
    }
    Get-ChildItem -LiteralPath $LiteralPath -Recurse -Force -ErrorAction Stop | ForEach-Object {
        if (($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Reparse point inside target: $($_.FullName)"
        }
        if (-not $_.PSIsContainer) {
            $fileCount++
            $byteCount += [long]$_.Length
        }
    }
    return [pscustomobject]@{ Files = $fileCount; Bytes = $byteCount }
}

$workspaceRoot = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath '.').ProviderPath).TrimEnd('\')
$buildRoot = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath 'build').ProviderPath).TrimEnd('\')
$expectedBuildRoot = [IO.Path]::GetFullPath((Join-Path $workspaceRoot 'build')).TrimEnd('\')
if (-not $buildRoot.Equals($expectedBuildRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Unexpected build root: $buildRoot"
}
if (((Get-Item -LiteralPath $buildRoot -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw "Build root is a reparse point: $buildRoot"
}

$protectedRelative = @(
    'calendar_sft_models/Qwen3-1.7B',
    'calendar_sft_models/Qwen3-0.6B',
    'calendar_v12_66_qwen3_17b/Qwen3-1.7B_source_lock.json',
    'calendar_v12_66_qwen3_06b/Qwen3-0.6B_source_lock.json',
    'calendar_sft_local_gpu_venv',
    'calendar_sft_qwen35_venv',
    'llama.cpp-v0.3.0',
    'llama-b10621-bin-win-cpu-x64',
    'calendar_sft_qwen3_v12_gguf_20260907',
    'metric_kws_ru',
    'metric_kws_reference',
    'audio-avd'
)
$protectedRelative += Get-ChildItem -LiteralPath $buildRoot -Directory -Force |
    Where-Object { $_.Name -match 'v12_67|^(audio|voice|speech|sherpa|metric_kws)' } |
    ForEach-Object { $_.Name }
$protected = @($protectedRelative | Sort-Object -Unique | ForEach-Object {
    [IO.Path]::GetFullPath((Join-Path $buildRoot ($_.Replace('/', '\')))).TrimEnd('\')
})

$targets = @()
$totalBytes = 0L
$totalFiles = 0L
foreach ($line in ($rawTargets -split '\r?\n')) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $fields = $line.Split('|')
    if ($fields.Count -ne 3) { throw "Malformed target row: $line" }
    $expectedBytes = [long]::Parse($fields[0])
    $expectedFiles = [long]::Parse($fields[1])
    $relative = $fields[2]
    if ($relative -match '(^/|\.\.|\\|:)') { throw "Unsafe relative path: $relative" }
    $targetPath = [IO.Path]::GetFullPath((Join-Path $buildRoot ($relative.Replace('/', '\')))).TrimEnd('\')
    if (-not $targetPath.StartsWith($buildRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Target outside build root: $targetPath"
    }
    $cursor = $buildRoot
    foreach ($segment in $relative.Split('/')) {
        $cursor = Join-Path $cursor $segment
        $segmentItem = Get-Item -LiteralPath $cursor -Force -ErrorAction Stop
        if (($segmentItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Reparse point in target path: $cursor"
        }
    }
    $resolved = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $targetPath).ProviderPath).TrimEnd('\')
    if (-not $resolved.Equals($targetPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Resolved path differs: $targetPath => $resolved"
    }
    foreach ($guard in $protected) {
        if ($targetPath.Equals($guard, [StringComparison]::OrdinalIgnoreCase) -or
            $targetPath.StartsWith($guard + '\', [StringComparison]::OrdinalIgnoreCase) -or
            $guard.StartsWith($targetPath + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw "Target overlaps protected path: $targetPath / $guard"
        }
    }
    $actual = Measure-ExactTree $targetPath
    if ($actual.Bytes -ne $expectedBytes -or $actual.Files -ne $expectedFiles) {
        throw "Inventory changed: $relative expected $expectedBytes bytes/$expectedFiles files, found $($actual.Bytes) bytes/$($actual.Files) files"
    }
    $targets += [pscustomobject]@{
        RelativePath = $relative
        AbsolutePath = $targetPath
        ExpectedBytes = $expectedBytes
        ExpectedFiles = $expectedFiles
    }
    $totalBytes += $actual.Bytes
    $totalFiles += $actual.Files
}
if ($targets.Count -ne 33 -or $totalBytes -ne 210063206809L -or $totalFiles -ne 11882L) {
    throw "Aggregate inventory mismatch: $($targets.Count) targets, $totalBytes bytes, $totalFiles files"
}
Write-Output "PREFLIGHT_OK targets=$($targets.Count) files=$totalFiles bytes=$totalBytes"
if (-not $Execute) { return }

$manifestPath = Join-Path $workspaceRoot 'docs/BUILD_CLEANUP_2026_10_17_MANIFEST.json'
$manifest = [ordered]@{
    StartedUtc = [DateTimeOffset]::UtcNow.ToString('o')
    CompletedUtc = $null
    Status = 'PRECHECK_PASS'
    WorkspaceRoot = $workspaceRoot
    BuildRoot = $buildRoot
    VoicePreserved = $true
    ExpectedDeletedBytes = $totalBytes
    ExpectedDeletedFiles = $totalFiles
    ProtectedPaths = $protected
    Targets = $targets
    DeletionResults = @()
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding utf8
$results = @()
$index = 0
foreach ($target in $targets) {
    $index++
    try {
        Remove-Item -LiteralPath $target.AbsolutePath -Recurse -Force -ErrorAction Stop
        if (Test-Path -LiteralPath $target.AbsolutePath) {
            throw "Target still exists after Remove-Item"
        }
        $results += [pscustomobject]@{ RelativePath = $target.RelativePath; Status = 'DELETED'; Error = $null }
    } catch {
        $results += [pscustomobject]@{ RelativePath = $target.RelativePath; Status = 'FAILED'; Error = $_.Exception.Message }
        Write-Output "FAILED $($target.RelativePath): $($_.Exception.Message)"
    }
    if (($index % 5) -eq 0 -or $index -eq $targets.Count) {
        Write-Output "PROGRESS $index/$($targets.Count)"
    }
    $manifest.DeletionResults = $results
    $manifest.Status = 'IN_PROGRESS'
    $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding utf8
}
$failed = @($results | Where-Object { $_.Status -ne 'DELETED' })
$manifest.CompletedUtc = [DateTimeOffset]::UtcNow.ToString('o')
$manifest.Status = if ($failed.Count -eq 0) { 'ALL_TARGETS_DELETED' } else { 'PARTIAL_FAILURE' }
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding utf8
if ($failed.Count -gt 0) { throw "$($failed.Count) cleanup targets failed; see $manifestPath" }
Write-Output "CLEANUP_OK targets=$($targets.Count) expectedBytes=$totalBytes"
