# Живая проверка извлечения потока прямо с этой машины.
#
# Зачем: обычно запрос делает приложение на телефоне, и проверить извлечение
# можно только вручную. Этот скрипт делает то же самое с компьютера:
#  1) отправляет запрос к видеосервису так же, как приложение;
#  2) сохраняет ответ;
#  3) прогоняет ответ через логику ядра и печатает результат.
#
# Если видеосервис недоступен напрямую (нужен VPN или туннель), скрипт
# честно скажет об этом и ничего не выдумает.
#
# Запуск:
#   powershell -ExecutionPolicy Bypass -File scripts\probe-extract.ps1 -VideoId dQw4w9WgXcQ
#   powershell -ExecutionPolicy Bypass -File scripts\probe-extract.ps1 -VideoId <id> -Proxy "socks5://127.0.0.1:1080"

param(
    [Parameter(Mandatory = $true)][string]$VideoId,
    [string]$Proxy = "",
    [string]$OutFile = ""
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$ws = Split-Path -Parent $repo
if (-not $OutFile) { $OutFile = Join-Path $ws ".openclaw\tmp\player-response.json" }

# Тело запроса — то же, что отправляет приложение.
$body = @{
    context = @{
        client = @{
            clientName    = "ANDROID"
            clientVersion = "19.09.37"
            androidSdkVersion = 30
            hl            = "ru"
            gl            = "US"
        }
    }
    videoId = $VideoId
    contentCheckOk = $true
    racyCheckOk    = $true
} | ConvertTo-Json -Depth 8 -Compress

$headers = @{
    "User-Agent"   = "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip"
    "Content-Type" = "application/json"
    "X-Youtube-Client-Name"    = "3"
    "X-Youtube-Client-Version" = "19.09.37"
}

$endpoint = "https://www.youtube.com/youtubei/v1/player"
Write-Output "Запрос к видеосервису за видео $VideoId ..."

$params = @{
    Uri             = $endpoint
    Method          = "Post"
    Body            = $body
    Headers         = $headers
    TimeoutSec      = 25
    UseBasicParsing = $true
}
if ($Proxy) {
    Write-Output "Через прокси: $Proxy"
    $params["Proxy"] = $Proxy
}

try {
    $response = Invoke-WebRequest @params
    $bytes = if ($response.RawContentStream) {
        $stream = $response.RawContentStream
        $stream.Position = 0
        $reader = New-Object System.IO.StreamReader($stream)
        $reader.ReadToEnd()
    } else {
        $response.Content
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutFile) | Out-Null
    [System.IO.File]::WriteAllText($OutFile, $bytes, (New-Object System.Text.UTF8Encoding($false)))
    Write-Output "Ответ сохранён: $OutFile ($($bytes.Length) символов)"
}
catch {
    Write-Output "СЕТЬ: видеосервис недоступен с этой машины — $($_.Exception.Message)"
    Write-Output "Дальше проверять нечего: включите туннель или VPN с маршрутом на видеосервис и повторите."
    exit 2
}

Write-Output "=== Разбор ответа логикой ядра ==="
$target = Join-Path $repo "target-verify3\debug\obereg-cli.exe"
if (-not (Test-Path $target)) { $target = Join-Path $repo "target\debug\obereg-cli.exe" }
if (-not (Test-Path $target)) {
    Write-Output "Сначала соберите ядро: cargo build -p obereg-cli"
    exit 3
}
& $target extract-json --file $OutFile
