#!/usr/bin/env bash
set -euo pipefail

CONFIGURATION="${1:-Release}"
SDK_NAME="${2:-iphoneos}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ -z "${JAVA_HOME:-}" || ! -d "${JAVA_HOME}" ]]; then
  if [[ -d "${HOME}/.sdkman/candidates/java/current" ]]; then
    export JAVA_HOME="${HOME}/.sdkman/candidates/java/current"
  elif /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  fi
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

export CONFIGURATION
export SDK_NAME
export ARCHS="${ARCHS:-arm64}"
export PLATFORM_NAME="${PLATFORM_NAME:-iphoneos}"
export TARGET_BUILD_DIR="${TARGET_BUILD_DIR:-${ROOT_DIR}/iosApp/build/xcode}"
export BUILT_PRODUCTS_DIR="${BUILT_PRODUCTS_DIR:-${TARGET_BUILD_DIR}}"
export UNLOCALIZED_RESOURCES_FOLDER_PATH="${UNLOCALIZED_RESOURCES_FOLDER_PATH:-Phoebe.app}"
export FRAMEWORKS_FOLDER_PATH="${FRAMEWORKS_FOLDER_PATH:-Phoebe.app/Frameworks}"

mkdir -p "${TARGET_BUILD_DIR}"

echo "Prebuilding ComposeApp framework (${CONFIGURATION}/${SDK_NAME})..."
./gradlew :composeApp:embedAndSignAppleFrameworkForXcode --no-daemon

framework_path="${ROOT_DIR}/composeApp/build/xcode-frameworks/${CONFIGURATION}/${SDK_NAME}/ComposeApp.framework"
if [[ ! -d "${framework_path}" ]]; then
  echo "::error::Expected framework was not produced at ${framework_path}" >&2
  exit 1
fi

echo "ComposeApp framework ready at ${framework_path}"
