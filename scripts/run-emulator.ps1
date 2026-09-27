# Запуск эмулятора Android (headless) для приёмки Android-оболочки Obereg.
#
# Требуется: пакет "emulator" и системный образ в SDK, созданный AVD.
# Требуется работающий гипервизор (Windows Hypervisor Platform) — эмулятор x86_64
# без него не стартует.
#
# Пример:
#   ./scripts/run-emulator.ps1
#   ./scripts/run-emulator.ps1 -Avd obereg -BootTimeoutSeconds 420
param(
    [string]$Avd = "obereg",
    [string]$SdkRoot = "C:\android-sdk",
    [int]$BootTimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"

$emulator = Join-Path $SdkRoot "emulator\emulator.exe"
$adb = Join-Path $SdkRoot "platform-tools\adb.exe"

if (-not (Test-Path $emulator)) {
    throw "Эмулятор не найден: $emulator. Установите пакет 'emulator' в SDK (см. android/README.md)."
}
if (-not (Test-Path $adb)) {
    throw "adb не найден: $adb. Установите пакет 'platform-tools'."
}

Write-Output "ADV: $Avd"
if (-not (Test-Path "$env:USERPROFILE\.android\avd\$Avd.ini")) {
    throw "AVD '$Avd' не найден. Создайте его (см. android/README.md, раздел про эмулятор)."
}

Write-Output "запуск эмулятора (headless)…"
$process = Start-Process -FilePath $emulator -PassThru -ArgumentList @(
    "-avd", $Avd,
    "-no-window", "-no-audio", "-no-boot-anim",
    "-gpu", "swiftshader_indirect",
    "-no-snapshot"
)

Write-Output "ожидание устройства…"
& $adb wait-for-device

$deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
$booted = ""
while ((Get-Date) -lt $deadline) {
    $booted = (& $adb shell getprop sys.boot_completed 2>$null) -join ""
    if ($booted -match "1") { break }
    Start-Sleep -Seconds 5
}

if ($booted -notmatch "1") {
    Write-Output "Эмулятор не загрузился за $BootTimeoutSeconds с (процесс $($process.Id))."
    & $adb devices
    exit 1
}

Write-Output "эмулятор загружен; устройство готово."
Write-Output "дальше: .\scripts\acceptance-android.ps1"
exit 0
