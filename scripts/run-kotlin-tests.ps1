# Запуск JVM-тестов оболочки без Gradle.
#
# Зачем: на некоторых машинах сетевой фильтр (VPN-драйвер) ломает петлю,
# и тогда Gradle не может подключиться к своему тестовому процессу
# (`:app:testDebugUnitTest` падает с `Connection refused` к 127.0.0.1).
# Этот скрипт компилирует нужные Kotlin-файлы напрямую и запускает JUnit
# в одном процессе — петля не нужна вовсе.
#
# Требуется: JDK 17, Android SDK (platform-34), прогретый кэш Gradle
# (нужные библиотеки берутся оттуда).
#
# Запуск:
#   powershell -ExecutionPolicy Bypass -File scripts\run-kotlin-tests.ps1

$ErrorActionPreference = "Stop"
$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot" }
$java = Join-Path $env:JAVA_HOME "bin\java.exe"
$cache = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1"
$repo = Split-Path -Parent $PSScriptRoot
$out = Join-Path $repo "target-kotlin-tests"

function Find-Jar([string]$pattern) {
    (Get-ChildItem -Path $cache -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Select-Object -First 1).FullName
}

# Библиотеки для компиляции и запуска тестов.
$libs = @(
    (Find-Jar "kotlin-stdlib-2.0.20.jar"),
    (Find-Jar "okhttp-4.12.0.jar"),
    (Find-Jar "okio-jvm-3.6.0.jar"),
    (Find-Jar "json-20240303.jar"),
    (Find-Jar "junit-4.13.2.jar"),
    (Find-Jar "hamcrest-core-1.3.jar"),
    (Join-Path "C:\android-sdk" "platforms\android-34\android.jar")
)
$missing = $libs | Where-Object { -not $_ -or -not (Test-Path $_) }
if ($missing) { throw "не найдены библиотеки: $($missing -join ', ')" }

# Компилятор Kotlin и его зависимости — целиком из кэша Gradle.
$compilerJars = @()
foreach ($group in @("org.jetbrains.kotlin", "org.jetbrains.kotlinx", "org.jetbrains.intellij.deps", "org.jetbrains")) {
    $path = Join-Path $cache $group
    if (Test-Path $path) {
        $compilerJars += Get-ChildItem -Path $path -Recurse -Filter "*.jar" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources|javadoc|-tests' } |
            Select-Object -ExpandProperty FullName
    }
}
$compilerJars = $compilerJars | Select-Object -Unique

$sources = @(
    (Join-Path $repo "android\app\src\main\java\app\obereg\player\YouTubeExtractor.kt"),
    (Join-Path $repo "android\app\src\main\java\app\obereg\player\CoreHolder.kt"),
    (Join-Path $repo "android\app\src\main\java\app\obereg\player\DiagLog.kt"),
    (Join-Path $repo "android\app\src\main\java\app\obereg\player\Library.kt"),
    (Join-Path $repo "android\app\src\main\java\app\obereg\player\AppSettings.kt"),
    (Join-Path $repo "android\app\src\main\java\app\obereg\player\Feed.kt"),
    (Join-Path $repo "android\app\src\main\java\app\obereg\player\TokenProbe.kt"),
    (Join-Path $repo "android\app\src\main\java\app\obereg\core\CoreBridge.kt"),
    (Join-Path $repo "android\app\src\test\java\app\obereg\player\YouTubeExtractorTest.kt")
)

New-Item -ItemType Directory -Force -Path $out | Out-Null
$classpath = $libs -join ";"

Write-Output "Компиляция тестов оболочки..."
& $java -cp ($compilerJars -join ";") org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -jvm-target 17 -classpath $classpath -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "компиляция не удалась" }

Write-Output "Запуск тестов..."
& $java -cp "$out;$classpath" org.junit.runner.JUnitCore app.obereg.player.YouTubeExtractorTest
if ($LASTEXITCODE -ne 0) { throw "тесты оболочки не прошли" }
Write-Output "Тесты оболочки пройдены."
