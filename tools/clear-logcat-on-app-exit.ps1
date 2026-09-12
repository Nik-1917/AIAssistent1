[CmdletBinding()]
param(
    [string]$Serial = "",
    [string]$PackageName = "com.example.aiassistent1",
    [string]$ActivityName = "com.example.aiassistent1.MainActivity",
    [int]$PollMilliseconds = 500
)

$ErrorActionPreference = "Stop"

$adb = $null
foreach ($sdkRoot in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
    if ($sdkRoot) {
        $candidate = Join-Path $sdkRoot "platform-tools\adb.exe"
        if (Test-Path -LiteralPath $candidate) {
            $adb = $candidate
            break
        }
    }
}
if (-not $adb) {
    $adbCommand = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($adbCommand) { $adb = $adbCommand.Source }
}
if (-not (Test-Path -LiteralPath $adb)) {
    throw "adb.exe не найден. Укажите ANDROID_HOME или ANDROID_SDK_ROOT."
}

$adbArgs = @()
if ($Serial.Trim().Length -gt 0) {
    $adbArgs += @("-s", $Serial)
}

function Invoke-Adb {
    param([string[]]$Arguments)
    & $adb @adbArgs @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Команда adb завершилась с кодом $LASTEXITCODE: adb $($Arguments -join ' ')"
    }
}

Invoke-Adb @("shell", "am", "start", "-n", "$PackageName/$ActivityName") | Out-Null

do {
    Start-Sleep -Milliseconds $PollMilliseconds
    $pidOutput = (& $adb @adbArgs "shell" "pidof" $PackageName).Trim()
} while ($pidOutput.Length -eq 0)

while ($true) {
    Start-Sleep -Milliseconds $PollMilliseconds
    $pidOutput = (& $adb @adbArgs "shell" "pidof" $PackageName).Trim()
    if ($pidOutput.Length -eq 0) {
        Invoke-Adb @("logcat", "-c")
        Write-Host "logcat очищен после завершения $PackageName."
        break
    }
}
