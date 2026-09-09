#!/usr/bin/env bash
set -euo pipefail

MEGA_SDK_TAG="${MEGA_SDK_TAG:-v10.19.0}"
ANDROID_NDK_VERSION="${ANDROID_NDK_VERSION:-27.1.12297006}"
MEGA_ANDROID_API="${MEGA_ANDROID_API:-28}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT is required}/ndk/${ANDROID_NDK_VERSION}}"
VCPKG_ROOT="${VCPKG_ROOT:-$PWD/.mega-vcpkg}"
SDK_SOURCE_DIR="${SDK_SOURCE_DIR:-$PWD/.mega-sdk-src}"
AAR_PROJECT_DIR="${AAR_PROJECT_DIR:-$PWD/.mega-aar}"
AAR_CHECK_DIR="${AAR_CHECK_DIR:-$PWD/.mega-aar-check}"

fail() {
  echo "::error::$*" >&2
  exit 1
}

is_android_system_lib() {
  case "$1" in
    libc.so|libdl.so|liblog.so|libm.so|libz.so|libandroid.so|\
    libatomic.so|libEGL.so|libGLESv2.so|libGLESv3.so|libjnigraphics.so|\
    libmediandk.so|libOpenSLES.so|libstdc++.so|libvulkan.so)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

clone_sources() {
  rm -rf "$SDK_SOURCE_DIR" "$VCPKG_ROOT"
  git clone --depth 1 --branch "$MEGA_SDK_TAG" \
    https://github.com/meganz/sdk.git "$SDK_SOURCE_DIR"
  # MEGA's manifest pins a vcpkg baseline, so do not shallow-clone vcpkg.
  git clone https://github.com/microsoft/vcpkg.git "$VCPKG_ROOT"
  "$VCPKG_ROOT/bootstrap-vcpkg.sh" -disableMetrics
}

build_abi() {
  local abi="$1"
  local build_dir="$PWD/.mega-build-${abi}"
  rm -rf "$build_dir"

  cmake -S "$SDK_SOURCE_DIR" --preset mega-android \
    -B "$build_dir" \
    -DVCPKG_ROOT="$VCPKG_ROOT" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="$MEGA_ANDROID_API" \
    -DCMAKE_BUILD_TYPE=Release \
    -DENABLE_CHAT=ON \
    -DENABLE_SYNC=ON \
    -DENABLE_ISOLATED_GFX=OFF \
    -DENABLE_MEDIA_FILE_METADATA=OFF \
    -DENABLE_SDKLIB_EXAMPLES=OFF \
    -DENABLE_SDKLIB_TESTS=OFF \
    -DUSE_PDFIUM=OFF \
    -DUSE_FREEIMAGE=OFF \
    -DUSE_FFMPEG=OFF \
    -DENABLE_JAVA_BINDINGS=ON \
    -DENABLE_SDKLIB_ANDROID_DYNAMIC_LIBRARY=ON

  cmake --build "$build_dir" --target SDKJavaBindings -j2
  [[ -s "$build_dir/bindings/java/libmega.so" ]] \
    || fail "SDKJavaBindings did not produce libmega.so for ${abi}"
  [[ -d "$build_dir/bindings/java/nz/mega/sdk" ]] \
    || fail "SDKJavaBindings did not produce Java sources for ${abi}"
}

resolve_dependency() {
  local build_dir="$1"
  local triple="$2"
  local needed="$3"
  local source=""

  source="$(find "$build_dir" -type f -name "$needed" -print -quit)"
  if [[ -z "$source" ]]; then
    source="$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" \
      -type f -path "*/sysroot/usr/lib/${triple}/${needed}" -print -quit)"
  fi
  if [[ -z "$source" ]]; then
    source="$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" \
      -type f -path "*/sysroot/usr/lib/${triple}/*/${needed}" -print -quit)"
  fi
  printf '%s' "$source"
}

collect_native_closure() {
  local abi="$1"
  local triple="$2"
  local build_dir="$PWD/.mega-build-${abi}"
  local dest="$AAR_PROJECT_DIR/sdk/src/main/jniLibs/${abi}"
  mkdir -p "$dest"
  install -m 0644 "$build_dir/bindings/java/libmega.so" "$dest/libmega.so"

  local -a queue=("$dest/libmega.so")
  local index=0
  while (( index < ${#queue[@]} )); do
    local lib="${queue[$index]}"
    index=$((index + 1))

    while IFS= read -r needed; do
      [[ -n "$needed" ]] || continue
      is_android_system_lib "$needed" && continue
      [[ -s "$dest/$needed" ]] && continue

      local source
      source="$(resolve_dependency "$build_dir" "$triple" "$needed")"
      [[ -n "$source" ]] \
        || fail "Unable to resolve ${needed}, required by ${lib} for ${abi}"
      cp -L "$source" "$dest/$needed"
      queue+=("$dest/$needed")
    done < <(readelf -d "$lib" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p')
  done
}

prepare_aar_project() {
  rm -rf "$AAR_PROJECT_DIR"
  mkdir -p "$AAR_PROJECT_DIR/sdk/src/main/java/nz/mega/sdk"

  # MegaApiJava/MegaApiAndroid are the full public Android facade. Keep the native/SWIG
  # feature set aligned with that facade (notably ENABLE_CHAT=ON), otherwise javac sees
  # wrapper methods whose generated MegaApi counterparts do not exist.
  find "$SDK_SOURCE_DIR/bindings/java/nz/mega/sdk" -maxdepth 1 -type f \
    \( -name '*.java' -o -name '*.kt' \) -print0 \
    | xargs -0 -r -I{} cp "{}" "$AAR_PROJECT_DIR/sdk/src/main/java/nz/mega/sdk/"
  find "$PWD/.mega-build-arm64-v8a/bindings/java/nz/mega/sdk" -maxdepth 1 -type f \
    -name '*.java' -print0 \
    | xargs -0 -r -I{} cp "{}" "$AAR_PROJECT_DIR/sdk/src/main/java/nz/mega/sdk/"
  rm -f "$AAR_PROJECT_DIR/sdk/src/main/java/nz/mega/sdk/MegaApiSwing.java"

  collect_native_closure arm64-v8a aarch64-linux-android
  collect_native_closure x86_64 x86_64-linux-android

  cat > "$AAR_PROJECT_DIR/settings.gradle.kts" <<'EOF'
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "nittc-mega-sdk"
include(":sdk")
EOF

  cat > "$AAR_PROJECT_DIR/build.gradle.kts" <<'EOF'
plugins {
    id("com.android.library") version "9.1.1" apply false
}
EOF

  cat > "$AAR_PROJECT_DIR/sdk/build.gradle.kts" <<'EOF'
plugins {
    id("com.android.library")
}
android {
    namespace = "nz.mega.sdk"
    compileSdk = 36
    defaultConfig {
        // Native MEGA is API 28+, while the app remains installable on API 26/27.
        // CloudFileSyncManager prevents loading this library below Android 9.
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains:annotations:24.1.0")
}
EOF

  cat > "$AAR_PROJECT_DIR/sdk/consumer-rules.pro" <<'EOF'
-keep class nz.mega.sdk.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
EOF

  cat > "$AAR_PROJECT_DIR/sdk/src/main/AndroidManifest.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
EOF
}

build_aar() {
  chmod +x ./gradlew
  # A developer-specific java.home must not override CI's JDK.
  sed -i '/^org\.gradle\.java\.home=/d' gradle.properties
  ./gradlew -p "$AAR_PROJECT_DIR" :sdk:assembleRelease --stacktrace

  mkdir -p app/libs third_party
  cp "$AAR_PROJECT_DIR/sdk/build/outputs/aar/sdk-release.aar" app/libs/mega-sdk.aar
  cp "$SDK_SOURCE_DIR/LICENSE" third_party/mega-sdk-LICENSE.txt
}

verify_aar() {
  [[ -s app/libs/mega-sdk.aar ]] || fail "mega-sdk.aar was not created"
  rm -rf "$AAR_CHECK_DIR"
  mkdir -p "$AAR_CHECK_DIR"
  unzip -q app/libs/mega-sdk.aar -d "$AAR_CHECK_DIR"
  [[ -s "$AAR_CHECK_DIR/classes.jar" ]] || fail "AAR has no classes.jar"

  local class
  for class in \
    nz/mega/sdk/MegaApiAndroid.class \
    nz/mega/sdk/MegaApi.class \
    nz/mega/sdk/MegaApiJNI.class
  do
    jar tf "$AAR_CHECK_DIR/classes.jar" \
      | awk -v expected="$class" '$0 == expected { found=1 } END { exit !found }' \
      || fail "Missing MEGA class in AAR: ${class}"
  done

  local abi lib_dir root_lib lib needed
  for abi in arm64-v8a x86_64; do
    lib_dir="$AAR_CHECK_DIR/jni/${abi}"
    root_lib="$lib_dir/libmega.so"
    [[ -s "$root_lib" ]] || fail "Missing libmega.so for ${abi}"

    readelf -Ws "$root_lib" \
      | awk '/Java_nz_mega_sdk_|JNI_OnLoad/ { found=1 } END { exit !found }' \
      || fail "${root_lib} has no MEGA JNI entry points"

    while IFS= read -r lib; do
      while IFS= read -r needed; do
        [[ -n "$needed" ]] || continue
        is_android_system_lib "$needed" && continue
        [[ -s "$lib_dir/$needed" ]] \
          || fail "${lib} requires missing native dependency ${needed}"
      done < <(readelf -d "$lib" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p')
    done < <(find "$lib_dir" -maxdepth 1 -type f -name '*.so' -print)
  done
}

export ANDROID_NDK_HOME
clone_sources
build_abi arm64-v8a
build_abi x86_64
prepare_aar_project
build_aar
verify_aar

echo "MEGA SDK AAR built and verified successfully."
