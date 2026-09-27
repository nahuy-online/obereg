# Полная локальная проверка ядра Obereg: форматирование, линтер, тесты.
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
$failed = @()

Write-Output "== fmt =="
cargo fmt --all -- --check
if ($LASTEXITCODE -ne 0) { $failed += "fmt" }

Write-Output "== clippy =="
cargo clippy --workspace --all-targets -- -D warnings
if ($LASTEXITCODE -ne 0) { $failed += "clippy" }

Write-Output "== test =="
cargo test --workspace
if ($LASTEXITCODE -ne 0) { $failed += "test" }

Write-Output ""
if ($failed.Count -eq 0) {
    Write-Output "OK: fmt + clippy + test пройдены"
} else {
    Write-Error "упавшие проверки: $($failed -join ', ')"
}
