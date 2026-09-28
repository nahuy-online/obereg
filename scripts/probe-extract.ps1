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

$endpoint = "https://www.youtube.com/youtubei/v1/player"

# Те же профили клиентов, что и в приложении (см. YouTubeExtractor.kt → PlayerApi).
$profiles = @(
    @{
        Label = "ANDROID"
        ClientName = "ANDROID"
        ClientVersion = "19.09.37"
        Key = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
        ClientHeader = "3"
        UserAgent = "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip"
        ExtraContext = @{ androidSdkVersion = 30 }
    },
    @{
        Label = "IOS"
        ClientName = "IOS"
        ClientVersion = "19.09.3"
        Key = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc"
        ClientHeader = "5"
        UserAgent = "com.google.ios.youtube/19.09.3 (iPhone16,2; U; CPU iOS 17_4_1 like Mac OS X)"
        ExtraContext = @{ deviceModel = "iPhone16,2"; osName = "iPhone"; osVersion = "17.4.1.21E236" }
    },
    @{
        Label = "WEB"
        ClientName = "WEB"
        ClientVersion = "2.20240926.01.00"
        Key = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        ClientHeader = "1"
        UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
        ExtraContext = @{}
    }
)

function New-Body([hashtable]$profile, [string]$id) {
    $client = [ordered]@{
        clientName    = $profile.ClientName
        clientVersion = $profile.ClientVersion
        hl            = "ru"
        gl            = "US"
    }
    foreach ($key in $profile.ExtraContext.Keys) { $client[$key] = $profile.ExtraContext[$key] }
    return ([ordered]@{
        context        = @{ client = $client }
        videoId        = $id
        contentCheckOk = $true
        racyCheckOk    = $true
    } | ConvertTo-Json -Depth 8 -Compress)
}

function Get-ResponseText($response) {
    if ($response.RawContentStream) {
        $stream = $response.RawContentStream
        $stream.Position = 0
        $reader = New-Object System.IO.StreamReader($stream)
        return $reader.ReadToEnd()
    }
    return $response.Content
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutFile) | Out-Null
$saved = $false

foreach ($profile in $profiles) {
    Write-Output "Пробуем профиль $($profile.Label) для видео $VideoId ..."
    $params = @{
        Uri             = "$endpoint?key=$($profile.Key)&prettyPrint=false"
        Method          = "Post"
        Body            = (New-Body $profile $VideoId)
        TimeoutSec      = 25
        UseBasicParsing = $true
        Headers         = @{
            "User-Agent"               = $profile.UserAgent
            "Content-Type"             = "application/json"
            "X-Youtube-Client-Name"    = $profile.ClientHeader
            "X-Youtube-Client-Version" = $profile.ClientVersion
            "Accept-Language"          = "ru-RU,ru;q=0.9"
        }
    }
    if ($Proxy) {
        Write-Output "  через прокси: $Proxy"
        $params["Proxy"] = $Proxy
    }

    try {
        $response = Invoke-WebRequest @params
        $text = Get-ResponseText $response
        [System.IO.File]::WriteAllText($OutFile, $text, (New-Object System.Text.UTF8Encoding($false)))
        Write-Output "  HTTP 200, ответ сохранён: $OutFile ($($text.Length) символов)"
        $saved = $true
        break
    }
    catch [System.Net.WebException] {
        $status = $null
        if ($_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode
            try {
                $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                $body = $reader.ReadToEnd()
                $flat = ($body -replace "\s+", " ").Trim()
                if ($flat.Length -gt 300) { $flat = $flat.Substring(0, 300) + "..." }
                Write-Output "  HTTP $status : $flat"
            }
            catch {
                Write-Output "  HTTP $status (тело ответа прочитать не удалось)"
            }
        }
        else {
            Write-Output "  СЕТЬ: сервис недоступен с этой машины — $($_.Exception.Message)"
            Write-Output "  Нужен рабочий маршрут: VPN со split tunnel или наш туннель. Проверять нечего."
            exit 2
        }
    }
}

if (-not $saved) {
    Write-Output "Ни один профиль не получил ответа 200 — смотрите коды и тексты выше."
    exit 4
}

Write-Output "=== Разбор ответа логикой ядра ==="
$target = Join-Path $repo "target-verify3\debug\obereg-cli.exe"
if (-not (Test-Path $target)) { $target = Join-Path $repo "target\debug\obereg-cli.exe" }
if (-not (Test-Path $target)) {
    Write-Output "Сначала соберите ядро: cargo build -p obereg-cli"
    exit 3
}
& $target extract-json --file $OutFile
