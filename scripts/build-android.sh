# Сборка JNI-cdylib ядра Obereg под Android (arm64-v8a, armeabi-v7a, x86_64).
# Результат копируется в android/app/src/main/jniLibs/<abi>/libobereg_jni.so
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if ! command -v cargo >/dev/null 2>&1; then
  echo "Rust не найден. Установите тулчейн: https://rustup.rs" >&2
  exit 1
fi
if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -z "${ANDROID_NDK_ROOT:-}" ]; then
  echo "ANDROID_NDK_HOME не задан. Установите NDK r26+ и укажите путь." >&2
  exit 1
fi
cargo --version
rustc --version

rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

if ! command -v cargo-ndk >/dev/null 2>&1; then
  cargo install cargo-ndk --locked
fi

cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -o android/app/src/main/jniLibs \
  build --release -p obereg-jni

echo ""
echo "=== артефакты ==="
find android/app/src/main/jniLibs -name "libobereg_jni.so" -print
echo ""
echo "дальше: cd android && gradle wrapper --gradle-version 8.7 && ./gradlew :app:assembleDebug"
