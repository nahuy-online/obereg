# Сборка статической библиотеки ядра Obereg под iOS (arm64 + x86_64, universal).
# Результат: libobereg_ffi.a в корне репозитория.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if ! command -v cargo >/dev/null 2>&1; then
  echo "Rust не найден. Установите тулчейн: https://rustup.rs" >&2
  exit 1
fi
if ! command -v xcrun >/dev/null 2>&1; then
  echo "Xcode Command Line Tools не найдены (нужны для сборки под iOS)." >&2
  exit 1
fi
cargo --version

rustup target add aarch64-apple-ios x86_64-apple-ios

cargo build --release -p obereg-ffi --target aarch64-apple-ios
cargo build --release -p obereg-ffi --target x86_64-apple-ios

lipo -create -output libobereg_ffi.a \
  target/aarch64-apple-ios/release/libobereg_ffi.a \
  target/x86_64-apple-ios/release/libobereg_ffi.a

echo ""
echo "=== артефакты ==="
echo "  $REPO_ROOT/libobereg_ffi.a"
lipo -info libobereg_ffi.a
echo ""
echo "подключение: см. ios/README.md (module.modulemap + obereg.h уже в репозитории)"
