# Живая проверка извлечения потока прямо с этой машины.
#
# Как работает (так же, как приложение):
#  1) запрашивает страницу просмотра видео обычным браузерным агентом;
#  2) вынимает из страницы готовый ответ плеера (ytInitialPlayerResponse) — ключ не нужен;
#  3) если ответа нет — берёт со страницы ключ интерфейса и версию клиента и опрашивает
#     программный интерфейс по очереди от лица нескольких клиентов;
#  4) прогоняет полученный ответ через логику ядра и печатает результат.
#
# Запуск по сети:
#   powershell -ExecutionPolicy Bypass -File scripts\probe-extract.ps1 -VideoId dQw4w9WgXcQ
#   powershell -ExecutionPolicy Bypass -File scripts\probe-extract.ps1 -VideoId <id> -Proxy "socks5://127.0.0.1:1080"
#
# Запуск без сети (проверка на заранее сохранённой странице):
#   powershell -ExecutionPolicy Bypass -File scripts\probe-extract.ps1 -VideoId <id> -Fixture <путь к html>

param(
    [Parameter(Mandatory = $true)][string]$VideoId,
    [string]$Proxy = "",
    [string]$OutFile = "",
    [string]$Fixture = ""
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$ws = Split-Path -Parent $repo
if (-not $OutFile) { $OutFile = Join-Path $ws ".openclaw\tmp\player-response.json" }

$watchUrl = "https://www.youtube.com/watch?v=$VideoId&hl=ru"
$apiUrl = "https://www.youtube.com/youtubei/v1/player"
$browserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"

# Те же профили клиентов, что и в приложении (YouTubeExtractor.kt → PlayerApi).
$profiles = @(
    @{ Label = "TV_EMBEDDED"; ClientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER"; ClientVersion = "2.0"; ClientHeader = "85"; UserAgent = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 7.0) AppleWebKit/537.36 (KHTML, like Gecko) 94.0.4606.31/7.0 TV Safari/537.36"; ExtraContext = @{}; EmbedUrl = "https://www.youtube.com/" },
    @{ Label = "WEB_EMBEDDED"; ClientName = "WEB_EMBEDDED_PLAYER"; ClientVersion = "1.20240723.01.00"; ClientHeader = "56"; UserAgent = $browserAgent; ExtraContext = @{}; EmbedUrl = "https://www.youtube.com/" },    @{ Label = "ANDROID"; ClientName = "ANDROID"; ClientVersion = "19.09.37"; ClientHeader = "3"
       UserAgent = "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip"
       ExtraContext = @{ androidSdkVersion = 30 } },
    @{ Label = "IOS"; ClientName = "IOS"; ClientVersion = "19.09.3"; ClientHeader = "5"
       UserAgent = "com.google.ios.youtube/19.09.3 (iPhone16,2; U; CPU iOS 17_4_1 like Mac OS X)"
       ExtraContext = @{ deviceModel = "iPhone16,2"; osName = "iPhone"; osVersion = "17.4.1.21E236" } },
    @{ Label = "WEB"; ClientName = "WEB"; ClientVersion = "2.20240926.01.00"; ClientHeader = "1"
       UserAgent = $browserAgent; ExtraContext = @{} }
)

function Get-BalancedObject([string]$text, [int]$start) {
    if ($start -lt 0 -or $start -ge $text.Length -or $text[$start] -ne '{') { return $null }
    $depth = 0; $inString = $false; $escaped = $false; $i = $start
    while ($i -lt $text.Length) {
        $c = $text[$i]
        if ($escaped) { $escaped = $false }
        elseif ($inString -and $c -eq '\') { $escaped = $true }
        elseif ($c -eq '"') { $inString = -not $inString }
        elseif (-not $inString -and $c -eq '{') { $depth++ }
        elseif (-not $inString -and $c -eq '}') {
            $depth--
            if ($depth -eq 0) { return $text.Substring($start, $i - $start + 1) }
        }
        $i++
    }
    return $null
}

function Get-PlayerResponse([string]$html) {
    $marker = "ytInitialPlayerResponse"
    $from = $html.IndexOf($marker)
    while ($from -ge 0) {
        $brace = $html.IndexOf('{', $from)
        if ($brace -lt 0) { return $null }
        $candidate = Get-BalancedObject $html $brace
        if ($candidate -and ($candidate.Contains('"streamingData"') -or $candidate.Contains('"playabilityStatus"'))) {
            return $candidate
        }
        $from = $html.IndexOf($marker, $from + $marker.Length)
    }
    return $null
}

function Invoke-Page([string]$url) {
    $params = @{ Uri = $url; TimeoutSec = 25; UseBasicParsing = $true
                 Headers = @{ "User-Agent" = $browserAgent; "Accept-Language" = "ru-RU,ru;q=0.9" } }
    if ($Proxy) { $params["Proxy"] = $Proxy }
    return Invoke-WebRequest @params
}

function New-Body([hashtable]$profile, [string]$id, [string]$key) {
    $client = [ordered]@{ clientName = $profile.ClientName; clientVersion = $profile.ClientVersion; hl = "ru"; gl = "US" }
    foreach ($name in $profile.ExtraContext.Keys) { $client[$name] = $profile.ExtraContext[$name] }
    $body = ([ordered]@{ context = @{ client = $client }; videoId = $id; contentCheckOk = $true; racyCheckOk = $true } |
        ConvertTo-Json -Depth 8 -Compress)
    return @{ Url = "$apiUrl?key=$key&prettyPrint=false"; Body = $body }
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutFile) | Out-Null
$playerJson = $null
$pageHtml = $null

if ($Fixture) {
    Write-Output "Разбор заранее сохранённой страницы: $Fixture"
    $pageHtml = [System.IO.File]::ReadAllText($Fixture)
}
else {
    Write-Output "Запрос страницы просмотра $watchUrl ..."
    try {
        $response = Invoke-Page $watchUrl
        if ($response.RawContentStream) {
            $stream = $response.RawContentStream; $stream.Position = 0
            $pageHtml = (New-Object System.IO.StreamReader($stream)).ReadToEnd()
        } else { $pageHtml = $response.Content }
        Write-Output "  страница получена: $($pageHtml.Length) символов"
    }
    catch {
        Write-Output "СЕТЬ: видеосервис недоступен с этой машины — $($_.Exception.Message)"
        Write-Output "Нужен рабочий маршрут: VPN со split tunnel или наш туннель. Проверять нечего."
        exit 2
    }
}

$playerJson = Get-PlayerResponse $pageHtml
if ($playerJson) {
    Write-Output "  ответ плеера найден на странице: $($playerJson.Length) символов"
}
else {
    Write-Output "  ответа плеера на странице нет — пробуем программный интерфейс"
    $keyMatch = [regex]::Match($pageHtml, '"INNERTUBE_API_KEY":"([^"]+)"')
    $verMatch = [regex]::Match($pageHtml, '"INNERTUBE_CLIENT_VERSION":"([^"]+)"')
    if (-not $keyMatch.Success) {
        Write-Output "Ключ интерфейса на странице не найден — проверять нечего."
        exit 4
    }
    $key = $keyMatch.Groups[1].Value
    $pageVersion = if ($verMatch.Success) { $verMatch.Groups[1].Value } else { $null }
    Write-Output "  ключ интерфейса получен со страницы (длина $($key.Length))"
    foreach ($profile in $profiles) {
        if ($profile.ClientName -eq 'WEB' -and $pageVersion) { $profile.ClientVersion = $pageVersion }
        Write-Output "  профиль $($profile.Label) ..."
        $spec = New-Body $profile $VideoId $key
        $params = @{ Uri = $spec.Url; Method = "Post"; Body = $spec.Body; TimeoutSec = 25; UseBasicParsing = $true
                     Headers = @{ "User-Agent" = $profile.UserAgent; "Content-Type" = "application/json"
                                  "X-Youtube-Client-Name" = $profile.ClientHeader
                                  "X-Youtube-Client-Version" = $profile.ClientVersion
                                  "Accept-Language" = "ru-RU,ru;q=0.9" } }
        if ($Proxy) { $params["Proxy"] = $Proxy }
        try {
            $apiResponse = Invoke-WebRequest @params
            if ($apiResponse.RawContentStream) {
                $stream = $apiResponse.RawContentStream; $stream.Position = 0
                $playerJson = (New-Object System.IO.StreamReader($stream)).ReadToEnd()
            } else { $playerJson = $apiResponse.Content }
            Write-Output "  HTTP 200"
            break
        }
        catch [System.Net.WebException] {
            $status = $null
            if ($_.Exception.Response) {
                $status = [int]$_.Exception.Response.StatusCode
                try {
                    $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                    $flat = ($reader.ReadToEnd() -replace "\s+", " ").Trim()
                    if ($flat.Length -gt 240) { $flat = $flat.Substring(0, 240) + "..." }
                    Write-Output "  HTTP $status : $flat"
                }
                catch { Write-Output "  HTTP $status (тело прочитать не удалось)" }
            }
            else {
                Write-Output "СЕТЬ: сервис недоступен — $($_.Exception.Message)"
                exit 2
            }
        }
    }
}

if (-not $playerJson) {
    Write-Output "Ответ плеера получить не удалось (ни со страницы, ни через интерфейс)."
    exit 4
}

[System.IO.File]::WriteAllText($OutFile, $playerJson, (New-Object System.Text.UTF8Encoding($false)))
Write-Output "Ответ сохранён: $OutFile ($($playerJson.Length) символов)"

Write-Output "=== Разбор ответа логикой ядра ==="
$target = Join-Path $repo "target-verify3\debug\obereg-cli.exe"
if (-not (Test-Path $target)) { $target = Join-Path $repo "target\debug\obereg-cli.exe" }
if (-not (Test-Path $target)) {
    Write-Output "Сначала соберите ядро: cargo build -p obereg-cli"
    exit 3
}
& $target extract-json --file $OutFile
