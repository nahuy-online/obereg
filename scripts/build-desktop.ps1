# Сборка десктопных артефактов ядра Obereg под Windows.
# Результат: target\release\obereg_ffi.dll и target\release\obereg-cli.exe
$ErrorActionPreference = "Stop"

function Resolve-Cargo {
    if (Get-Command cargo -ErrorAction SilentlyContinue) { return }
    $cargoBin = Join-Path $env:USERPROFILE ".cargo\bin"
    if (Test-Path (Join-Path $cargoBin "cargo.exe")) {
        $env:Path = "$cargoBin;$env:Path"
        return
    }
    Write-Error "Rust не найден. Установите тулчейн: https://rustup.rs (rustup-init.exe -y --profile minimal)"
}

Resolve-Cargo
Write-Output "cargo: $(cargo --version)"
Write-Output "rustc: $(rustc --version)"

cargo build --release -p obereg-ffi -p obereg-cli
if ($LASTEXITCODE -ne 0) { Write-Error "cargo build failed with exit code $LASTEXITCODE" }

$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path
Write-Output ""
Write-Output "=== артефакты ==="
Write-Output "  $repoRoot\target\release\obereg_ffi.dll   (C ABI для десктопа)"
Write-Output "  $repoRoot\target\release\obereg-cli.exe  (харнесс: demo, bench, probe)"
Write-Output ""
Write-Output "проверка: .\target\release\obereg-cli.exe demo"
