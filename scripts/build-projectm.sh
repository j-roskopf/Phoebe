#!/usr/bin/env bash
# Build libprojectM (+ Phoebe JNI on JVM hosts) into native/projectm/<target>.
# Usage: scripts/build-projectm.sh [target]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${ROOT}/third_party/projectm"
PROJECTM_REF="${PROJECTM_REF:-v4.1.7}"
JOBS="${CMAKE_BUILD_PARALLEL_LEVEL:-$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)}"

TARGET="${1:-host}"
case "$TARGET" in
  host)
    OS="$(uname -s)"
    ARCH="$(uname -m)"
    case "$OS-$ARCH" in
      Darwin-arm64) TARGET=macos-arm64 ;;
      Darwin-x86_64) TARGET=macos-x64 ;;
      Linux-x86_64|Linux-amd64) TARGET=linux-x64 ;;
      Linux-aarch64|Linux-arm64) TARGET=linux-arm64 ;;
      MINGW*|MSYS*|CYGWIN*) TARGET=windows-x64 ;;
      *) echo "Unsupported host: $OS-$ARCH"; exit 1 ;;
    esac
    ;;
esac

ensure_sources() {
  if [[ ! -f "${SRC}/CMakeLists.txt" ]]; then
    mkdir -p "$(dirname "$SRC")"
    git clone --depth 1 --branch "${PROJECTM_REF}" \
      https://github.com/projectM-visualizer/projectm.git "${SRC}"
  fi
  if [[ -f "${SRC}/.gitmodules" ]]; then
    git -C "${SRC}" submodule update --init --recursive
  fi
}

# v4.1.7 rejects iOS GLES at configure time and assumes Linux GLES headers.
# Apply Phoebe overlays so ios-sim / ios-device can build static OpenGLES libs.
apply_ios_patches() {
  case "$TARGET" in
    ios-*) ;;
    *) return 0 ;;
  esac
  local patch_dir="${ROOT}/native/projectm/patches/ios"
  if [[ ! -d "$patch_dir" ]]; then
    echo "ERROR: missing iOS projectM patches at ${patch_dir}"
    exit 1
  fi
  # Reset patched files so re-runs stay idempotent when patches change.
  git -C "${SRC}" checkout -f -- \
    CMakeLists.txt \
    src/libprojectM/ProjectM.cpp \
    src/libprojectM/projectM-opengl.h \
    vendor/SOIL2/SOIL2.c
  local patch
  for patch in "${patch_dir}"/*.patch; do
    [[ -f "$patch" ]] || continue
    echo "Applying iOS GLES patch: $(basename "$patch")"
    git -C "${SRC}" apply "$patch"
  done
}

compile_jni() {
  local java_home="${JAVA_HOME:-}"
  if [[ -z "$java_home" && -x /usr/libexec/java_home ]]; then
    java_home="$(/usr/libexec/java_home 2>/dev/null || true)"
  fi
  if [[ -z "$java_home" ]]; then
    local candidate
    candidate="$(ls -d "${HOME}"/.gradle/jdks/eclipse_adoptium-22-*/jdk-*/Contents/Home 2>/dev/null | head -1 || true)"
    java_home="${candidate}"
  fi
  if [[ -z "$java_home" || ! -f "${java_home}/include/jni.h" ]]; then
    echo "WARN: JAVA_HOME with jni.h not found; skipping PhoebeProjectM JNI shim"
    return 0
  fi
  local out_dir="${INSTALL}/lib"
  mkdir -p "${out_dir}"
  local src="${ROOT}/native/jni/projectm_jni.c"
  case "$TARGET" in
    macos-*)
      clang -shared -fPIC \
        -I"${java_home}/include" -I"${java_home}/include/darwin" \
        -I"${INSTALL}/include" \
        -L"${INSTALL}/lib" -lprojectM-4 \
        -Wl,-rpath,@loader_path \
        -o "${out_dir}/libPhoebeProjectM.dylib" \
        "${src}"
      ;;
    linux-*)
      gcc -shared -fPIC \
        -I"${java_home}/include" -I"${java_home}/include/linux" \
        -I"${INSTALL}/include" \
        -L"${INSTALL}/lib" -lprojectM-4 \
        -Wl,-rpath,'$ORIGIN' \
        -o "${out_dir}/libPhoebeProjectM.so" \
        "${src}"
      ;;
    windows-*)
      clang -shared \
        -I"${java_home}/include" -I"${java_home}/include/win32" \
        -I"${INSTALL}/include" \
        -L"${INSTALL}/lib" -lprojectM-4 \
        -o "${out_dir}/PhoebeProjectM.dll" \
        "${src}" || echo "WARN: Windows JNI compile skipped"
      ;;
    android-*)
      compile_android_jni "${TARGET#android-}" "${out_dir}" "${src}" "${java_home}"
      ;;
  esac
}

compile_android_jni() {
  local abi="$1"
  local out_dir="$2"
  local src="$3"
  local java_home="$4"
  : "${ANDROID_NDK:?ANDROID_NDK required for android JNI}"
  local api=26
  local triple host_tag
  case "$(uname -s)" in
    Darwin) host_tag=darwin-x86_64 ;;
    Linux) host_tag=linux-x86_64 ;;
    *) echo "WARN: unsupported host for Android NDK clang"; return 0 ;;
  esac
  case "$abi" in
    arm64-v8a) triple=aarch64-linux-android ;;
    armeabi-v7a) triple=armv7a-linux-androideabi ;;
    x86_64) triple=x86_64-linux-android ;;
    x86) triple=i686-linux-android ;;
    *) echo "WARN: unknown ABI $abi"; return 0 ;;
  esac
  local clang="${ANDROID_NDK}/toolchains/llvm/prebuilt/${host_tag}/bin/${triple}${api}-clang"
  if [[ ! -x "$clang" ]]; then
    # Apple Silicon NDK host tag
    host_tag=darwin-arm64
    clang="${ANDROID_NDK}/toolchains/llvm/prebuilt/${host_tag}/bin/${triple}${api}-clang"
  fi
  if [[ ! -x "$clang" ]]; then
    echo "WARN: NDK clang not found for $abi ($clang)"
    return 0
  fi
  local jni_md=linux
  case "$(uname -s)" in
    Darwin) jni_md=darwin ;;
    Linux) jni_md=linux ;;
  esac
  "$clang" -shared -fPIC \
    -I"${java_home}/include" -I"${java_home}/include/${jni_md}" \
    -I"${INSTALL}/include" \
    -L"${INSTALL}/lib" -lprojectM-4 \
    -Wl,-soname,libPhoebeProjectM.so \
    -Wl,-z,max-page-size=16384 \
    -o "${out_dir}/libPhoebeProjectM.so" \
    "${src}"
  # projectM links against the NDK shared C++ runtime — ship it beside the .so files.
  local cxx_shared
  cxx_shared="$(find "${ANDROID_NDK}/toolchains/llvm/prebuilt" -path "*/${triple}/libc++_shared.so" | head -1 || true)"
  if [[ -n "$cxx_shared" && -f "$cxx_shared" ]]; then
    cp -f "$cxx_shared" "${out_dir}/libc++_shared.so"
  fi
  echo "Built Android JNI → ${out_dir}/libPhoebeProjectM.so"
}

INSTALL="${ROOT}/native/projectm/${TARGET}"
BUILD="${SRC}/build-${TARGET}"

ensure_sources
apply_ios_patches

CMAKE_ARGS=(
  -S "${SRC}"
  -B "${BUILD}"
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_INSTALL_PREFIX="${INSTALL}"
  -DBUILD_SHARED_LIBS=ON
  -DENABLE_SDL_UI=OFF
  -DENABLE_PLAYLIST=OFF
  -DBUILD_TESTING=OFF
)

case "$TARGET" in
  android-*)
    : "${ANDROID_NDK:?ANDROID_NDK required for android builds}"
    ABI="${TARGET#android-}"
    CMAKE_ARGS+=(
      -G Ninja
      -DENABLE_GLES=ON
      -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK}/build/cmake/android.toolchain.cmake"
      -DANDROID_ABI="${ABI}"
      -DANDROID_PLATFORM=android-26
      -DANDROID_STL=c++_shared
      -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
      -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384
    )
    ;;
  ios-device)
    # Ninja + sysroot is more reliable than the Xcode generator for a static lib.
    CMAKE_ARGS+=(
      -G Ninja
      -DENABLE_GLES=ON
      -DCMAKE_SYSTEM_NAME=iOS
      -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0
      -DCMAKE_OSX_SYSROOT=iphoneos
      -DCMAKE_OSX_ARCHITECTURES=arm64
      -DBUILD_SHARED_LIBS=OFF
      -DCMAKE_CXX_FLAGS=-stdlib=libc++
    )
    ;;
  ios-sim)
    CMAKE_ARGS+=(
      -G Ninja
      -DENABLE_GLES=ON
      -DCMAKE_SYSTEM_NAME=iOS
      -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0
      -DCMAKE_OSX_SYSROOT=iphonesimulator
      -DCMAKE_OSX_ARCHITECTURES=arm64
      -DBUILD_SHARED_LIBS=OFF
      -DCMAKE_CXX_FLAGS=-stdlib=libc++
    )
    ;;
  windows-x64)
    CMAKE_ARGS+=(-DENABLE_GLES=OFF)
    ;;
  *)
    CMAKE_ARGS+=(-DENABLE_GLES=OFF)
    ;;
esac

# Fresh configure for iOS when switching generators / patches.
if [[ "$TARGET" == ios-* && -d "${BUILD}" ]]; then
  rm -rf "${BUILD}"
fi

cmake "${CMAKE_ARGS[@]}"
if [[ "$TARGET" == ios-* ]]; then
  cmake --build "${BUILD}" --config Release -j"${JOBS}"
  cmake --install "${BUILD}" --config Release
else
  cmake --build "${BUILD}" --target install --config Release -j"${JOBS}"
fi

case "$TARGET" in
  macos-*|linux-*|windows-*|android-*) compile_jni ;;
esac

echo "Installed projectM → ${INSTALL}"
