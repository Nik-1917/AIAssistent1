[CmdletBinding()]
param([switch]$VerifyOnly)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$modelDirectory = Join-Path $repoRoot 'app/src/main/assets/voice/speaker'
$modelPath = Join-Path $modelDirectory 'model.onnx'
$expectedHash = '357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b'
$modelUrl = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx'

function Assert-ModelHash([string]$Path) {
    $actualHash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if ($actualHash -ne $expectedHash) {
        throw "Speaker model SHA-256 mismatch: $Path. Existing files have not been replaced."
    }
}

if (Test-Path -LiteralPath $modelPath) {
    Assert-ModelHash $modelPath
    Write-Output 'Speaker model verified; no download needed.'
    return
}
if ($VerifyOnly) { throw "Missing speaker model: $modelPath" }

[IO.Directory]::CreateDirectory($modelDirectory) | Out-Null
$downloadPath = Join-Path $modelDirectory ('.speaker-' + [Guid]::NewGuid().ToString('N') + '.part')
try {
    Invoke-WebRequest -Uri $modelUrl -OutFile $downloadPath
    Assert-ModelHash $downloadPath
    # Move within the same directory after validation; never overwrite an existing model.
    [IO.File]::Move($downloadPath, $modelPath)
    Write-Output 'Speaker model downloaded and verified.'
} finally {
    if (Test-Path -LiteralPath $downloadPath) { Remove-Item -LiteralPath $downloadPath }
}
