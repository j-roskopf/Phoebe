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
# Apply Phoebe overlays so every host gets the target-framebuffer hook and
# ios-sim / ios-device can build static OpenGLES libs.
apply_patches() {
  local common_dir="${ROOT}/native/projectm/patches/common"
  local ios_dir="${ROOT}/native/projectm/patches/ios"
  if [[ ! -d "$common_dir" ]]; then
    echo "ERROR: missing projectM patches at ${common_dir}"
    exit 1
  fi

  # Reset patched files so re-runs stay idempotent when patches change.
  git -C "${SRC}" checkout -f -- \
    CMakeLists.txt \
    src/libprojectM/ProjectM.cpp \
    src/libprojectM/projectM-opengl.h \
    vendor/SOIL2/SOIL2.c

  local patch
  for patch in "${common_dir}"/*.patch; do
    [[ -f "$patch" ]] || continue
    echo "Applying projectM common patch: $(basename "$patch")"
    git -C "${SRC}" apply "$patch"
  done

  case "$TARGET" in
    ios-*) ;;
    *) return 0 ;;
  esac
  if [[ ! -d "$ios_dir" ]]; then
    echo "ERROR: missing iOS projectM patches at ${ios_dir}"
    exit 1
  fi
  for patch in "${ios_dir}"/*.patch; do
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
    case "$TARGET" in
      macos-*|linux-*|windows-*)
        echo "ERROR: JAVA_HOME with jni.h not found; PhoebeProjectM is required for desktop."
        exit 1
        ;;
      *)
        echo "WARN: JAVA_HOME with jni.h not found; skipping PhoebeProjectM JNI shim"
        return 0
        ;;
    esac
  fi
  local out_dir="${INSTALL}/lib"
  mkdir -p "${out_dir}"
  local src="${ROOT}/native/jni/projectm_jni.c"
  # The source file must precede -lprojectM-4 on every link line. GNU ld resolves inputs
  # left to right, so with the source last there are no pending projectM references when
  # it reaches the library, and Ubuntu's default --as-needed drops it: the shim then loads
  # fine but leaves every projectm_* symbol undefined and dies on the first render call.
  case "$TARGET" in
    macos-*)
      clang -shared -fPIC \
        -I"${java_home}/include" -I"${java_home}/include/darwin" \
        -I"${INSTALL}/include" \
        -Wl,-rpath,@loader_path \
        -o "${out_dir}/libPhoebeProjectM.dylib" \
        "${src}" \
        -L"${INSTALL}/lib" -lprojectM-4
      ;;
    linux-*)
      gcc -shared -fPIC \
        -I"${java_home}/include" -I"${java_home}/include/linux" \
        -I"${INSTALL}/include" \
        -Wl,-rpath,'$ORIGIN' \
        -o "${out_dir}/libPhoebeProjectM.so" \
        "${src}" \
        -L"${INSTALL}/lib" -lprojectM-4
      ;;
    windows-*)
      # PhoebeProjectM calls glewInit on Windows (see projectm_jni.c); link GLEW
      # explicitly and pass its headers/libs from vcpkg.
      local vcpkg_root="${VCPKG_INSTALLATION_ROOT:-}"
      local vcpkg_triplet="${VCPKG_TARGET_TRIPLET:-x64-windows}"
      local glew_cflags=()
      local glew_ldflags=()
      if [[ -n "$vcpkg_root" ]]; then
        glew_cflags+=(-I"${vcpkg_root}/installed/${vcpkg_triplet}/include")
        glew_ldflags+=(-L"${vcpkg_root}/installed/${vcpkg_triplet}/lib" -lglew32)
      fi
      # glewInit comes from GLEW, but glGetError still resolves to the system
      # OpenGL import (opengl32) under clang; without it the shim fails to link
      # with LNK2019 on __imp_glGetError.
      glew_ldflags+=(-lopengl32)
      local jni_out="${out_dir}/PhoebeProjectM.dll"
      # Drop any shim from a previous install first so a failed compile cannot
      # leave a stale DLL that satisfies the required-file check while lacking
      # newer JNI entry points (e.g. nativeInitGlLoader).
      rm -f "${jni_out}"
      if ! clang -shared \
        -I"${java_home}/include" -I"${java_home}/include/win32" \
        -I"${INSTALL}/include" \
        "${glew_cflags[@]}" \
        -o "${jni_out}" \
        "${src}" \
        -L"${INSTALL}/lib" -lprojectM-4 \
        "${glew_ldflags[@]}"; then
        echo "ERROR: Windows JNI compile failed; PhoebeProjectM.dll is required for the visualizer."
        exit 1
      fi
      # projectM's OpenGL Core Windows build links GLEW dynamically; ship its
      # runtime DLL beside the others so packaged apps can load projectM-4.dll.
      if [[ -n "$vcpkg_root" && -d "${vcpkg_root}/installed/${vcpkg_triplet}/bin" ]]; then
        for dll in glew32.dll; do
          local src_dll="${vcpkg_root}/installed/${vcpkg_triplet}/bin/${dll}"
          [[ -f "$src_dll" ]] || continue
          cp -f "$src_dll" "${out_dir}/"
        done
      fi
      if [[ ! -f "${out_dir}/glew32.dll" ]]; then
        echo "ERROR: glew32.dll missing beside PhoebeProjectM.dll; set VCPKG_INSTALLATION_ROOT."
        exit 1
      fi
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
apply_patches

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
    # projectM's Windows OpenGL Core build requires GLEW. CI installs it with
    # vcpkg and hands CMake the toolchain so find_package(GLEW) resolves.
    if [[ -n "${VCPKG_INSTALLATION_ROOT:-}" && -f "${VCPKG_INSTALLATION_ROOT}/scripts/buildsystems/vcpkg.cmake" ]]; then
      CMAKE_ARGS+=(-DCMAKE_TOOLCHAIN_FILE="${VCPKG_INSTALLATION_ROOT}/scripts/buildsystems/vcpkg.cmake")
    fi
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

# On Windows CMake splits the shared library: the import lib goes to lib/ but the runtime
# DLL goes to bin/ (projectM's PROJECTM_RUNTIME_DIR). Everything downstream — the runtime
# loader, syncProjectMResources, the release packaging — resolves a single directory, so
# flatten the DLLs into lib/ and keep that the one place to look.
case "$TARGET" in
  windows-*)
    if [[ -d "${INSTALL}/bin" ]]; then
      mkdir -p "${INSTALL}/lib"
      find "${INSTALL}/bin" -maxdepth 1 -name '*.dll' -exec cp -f {} "${INSTALL}/lib/" \;
    fi
    ;;
esac

case "$TARGET" in
  macos-*|linux-*|windows-*|android-*) compile_jni ;;
esac

# The JNI shim silently skips on a compiler error, and a lib/ without projectM-4 loads
# nothing at runtime. Fail here instead of shipping a visualizer that cannot start.
case "$TARGET" in
  macos-*|linux-*|windows-*)
    for required in PhoebeProjectM projectM-4; do
      if ! ls "${INSTALL}/lib/"*"${required}"* >/dev/null 2>&1; then
        echo "ERROR: ${INSTALL}/lib is missing ${required}; the visualizer would fail to load."
        exit 1
      fi
    done
    ;;
esac

echo "Installed projectM → ${INSTALL}"
