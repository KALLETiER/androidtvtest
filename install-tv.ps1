param(
    [Parameter(Mandatory = $true)]
    [string]$TvAddress,
    [string]$ApkPath = (Join-Path $PSScriptRoot 'dist\Andres-Heimspiel-0.5.0.apk')
)

$ErrorActionPreference = 'Stop'
$parsedAddress = $null
if (-not [System.Net.IPAddress]::TryParse($TvAddress, [ref]$parsedAddress)) {
    throw 'Bitte die IP-Adresse des Fernsehers ohne Port angeben.'
}
if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    throw "APK nicht gefunden: $ApkPath"
}

$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
if ($adbCommand) {
    $adbExecutable = $adbCommand.Source
} else {
    $adbExecutable = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    if (-not (Test-Path -LiteralPath $adbExecutable -PathType Leaf)) {
        throw 'Android Platform Tools (adb) wurden auf diesem PC nicht gefunden.'
    }
}

$deviceAddress = "${TvAddress}:5555"
& $adbExecutable connect $deviceAddress
if ($LASTEXITCODE -ne 0) { throw 'Verbindung fehlgeschlagen. ADB-Debugging und Netzwerk prüfen.' }
& $adbExecutable -s $deviceAddress get-state
if ($LASTEXITCODE -ne 0) { throw 'Die ADB-Verbindung muss auf dem Fernseher freigegeben werden.' }
& $adbExecutable -s $deviceAddress install -r (Resolve-Path -LiteralPath $ApkPath).Path
if ($LASTEXITCODE -ne 0) { throw 'Die APK konnte nicht installiert werden.' }
& $adbExecutable -s $deviceAddress shell am start -n 'de.andre.heimspiel/.MainActivity'
if ($LASTEXITCODE -ne 0) { throw 'Die App ist installiert, konnte aber nicht geöffnet werden.' }
Write-Output 'Andrés Heimspiel wurde installiert und geöffnet.'
