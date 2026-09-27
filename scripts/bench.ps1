# Бенчмарк инициализации ядра Obereg (cold start: create + start + stop).
# Результат: JSON-отчёт с метриками и сохранённым baseline.
$ErrorActionPreference = "Stop"

function Resolve-Cargo {
    if (Get-Command cargo -ErrorAction SilentlyContinue) { return }
    $cargoBin = Join-Path $env:USERPROFILE ".cargo\bin"
    if (Test-Path (Join-Path $cargoBin "cargo.exe")) {
        $env:Path = "$cargoBin;$env:Path"
        return
    }
    Write-Error "Rust не найден. Установите тулчейн: https://rustup.rs"
}

Resolve-Cargo

$iterations = 30
$out = "bench\baseline.windows.json"
$paramIndex = [Array]::IndexOf($args, "--iterations")
if ($paramIndex -ge 0 -and $args.Count -gt ($paramIndex + 1)) { $iterations = [int]$args[$paramIndex + 1] }
$outIndex = [Array]::IndexOf($args, "--out")
if ($outIndex -ge 0 -and $args.Count -gt ($outIndex + 1)) { $out = $args[$outIndex + 1] }

cargo run --release -p obereg-cli -- bench --iterations $iterations --out $out
if ($LASTEXITCODE -ne 0) { Write-Error "bench failed with exit code $LASTEXITCODE" }

$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path
Write-Output ""
Write-Output "baseline сохранён: $repoRoot\$out"
Write-Output "сверяйте новые замеры с этой базой: изменения более чем на 20% требуют анализа (см. docs/bench/README.md)"
