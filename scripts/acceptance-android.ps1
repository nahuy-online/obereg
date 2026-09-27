# Приёмка Android-оболочки Obereg одной командой.
#
# Что делает:
#   1. находит adb и проверяет наличие устройства/эмулятора;
#   2. устанавливает APK;
#   3. запускает приложение и foreground-сервис туннеля;
#   4. дожидается состояния connected в журнале ядра;
#   5. выгружает журнал туннеля с устройства и печатает вердикт;
#   6. проверяет, что журнал переживает перезапуск приложения.
#
# Пример:
#   ./scripts/acceptance-android.ps1
#   ./scripts/acceptance-android.ps1 -Apk C:\path\to\app-debug.apk
param(
    [string]$Apk = "android\app\build\outputs\apk\debug\app-debug.apk",
    [string]$Package = "app.obereg.player",
    [string]$Activity = "app.obereg.player/.MainActivity",
    [string]$Service = "app.obereg.player/.TunnelService",
    [int]$StartTimeoutSeconds = 60
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path

function Resolve-Adb {
    if ($env:ANDROID_HOME -and (Test-Path "$env:ANDROID_HOME\platform-tools\adb.exe")) {
        return "$env:ANDROID_HOME\platform-tools\adb.exe"
    }
    if (Test-Path "C:\android-sdk\platform-tools\adb.exe") {
        return "C:\android-sdk\platform-tools\adb.exe"
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "adb не найден. Установите Android SDK platform-tools и задайте ANDROID_HOME (см. docs/build-guide.md)."
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & $script:Adb @Arguments
}

$script:Adb = Resolve-Adb
$apkPath = if ([System.IO.Path]::IsPathRooted($Apk)) { $Apk } else { Join-Path $repoRoot $Apk }
if (-not (Test-Path $apkPath)) {
    throw "APK не найден: $apkPath (соберите: gradle -p android :app:assembleDebug)"
}

Write-Output "adb: $script:Adb"
Write-Output "apk: $apkPath"

$devices = (Invoke-Adb devices) | Select-String -Pattern "\tdevice$"
if (-not $devices) {
    Write-Output ""
    Write-Output "УСТРОЙСТВО НЕ НАЙДЕНО."
    Write-Output "Подключите телефон по USB с включённой отладкой (Настройки → Для разработчиков → Отладка по USB)"
    Write-Output "или запустите эмулятор (см. android/README.md), затем повторите команду."
    exit 2
}
Write-Output "устройств найдено: $($devices.Count)"

Write-Output ""
Write-Output "== установка =="
Invoke-Adb install -r $apkPath

Write-Output ""
Write-Output "== запуск приложения и сервиса туннеля =="
Invoke-Adb shell am start -n $Activity | Out-Null
Start-Sleep -Seconds 3
Invoke-Adb shell am start-foreground-service -n $Service -a "$Package.action.START" | Out-Null

Write-Output ""
Write-Output "== ожидание запуска туннеля (до $StartTimeoutSeconds с) =="
$deadline = (Get-Date).AddSeconds($StartTimeoutSeconds)
$journalPath = "files/journal.jsonl"
$journal = ""
while ((Get-Date) -lt $deadline) {
    $journal = (Invoke-Adb shell run-as $Package cat $journalPath) 2>$null | Out-String
    if ($journal -match "tunnel_start") { break }
    Start-Sleep -Seconds 2
}

if (-not ($journal -match "tunnel_start")) {
    Write-Output "Туннель не поднялся за отведённое время. Текущий журнал:"
    Write-Output $journal
    Write-Output "Диагностика: adb logcat -s OberegCore; adb shell run-as $Package ls files"
    exit 1
}

Write-Output "== журнал туннеля с устройства =="
Write-Output $journal

Write-Output "== состояние сервиса =="
Invoke-Adb shell dumpsys activity services $Package | Select-String -Pattern "$Package" | Select-Object -First 5

Write-Output ""
Write-Output "== проверка переживания перезапуска =="
Invoke-Adb shell am force-stop $Package | Out-Null
Start-Sleep -Seconds 2
$journalAfter = (Invoke-Adb shell run-as $Package cat $journalPath) 2>$null | Out-String
if ($journalAfter -match "tunnel_start") {
    Write-Output "OK: журнал сохранился после перезапуска приложения"
} else {
    Write-Output "ВНИМАНИЕ: журнал после перезапуска не читается"
}

Write-Output ""
if ($journal -match "tunnel_start" -and $journalAfter -match "tunnel_start") {
    Write-Output "РЕЗУЛЬТАТ: туннель поднимается на устройстве, журнал переживает перезапуск."
    Write-Output "Финальный визуальный шаг: на экране нажмите «Проверить трафик» и сравните два адреса."
    exit 0
}
exit 1
