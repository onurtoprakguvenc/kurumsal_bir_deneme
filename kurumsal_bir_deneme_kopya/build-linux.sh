#!/usr/bin/env bash
#
#  Document Workbench - self-contained Linux x64 packages (.deb + portable .tar.gz) built only with the JDK 21 tools
#  (jdeps -> jlink -> jpackage). The user's machine needs no Java installation. Linux counterpart of build-exe.ps1.
#
#    bash build-linux.sh                 .deb and .tar.gz
#    bash build-linux.sh --skip-build    reuse build/jpackage/input
#    bash build-linux.sh --no-deb        only the portable .tar.gz (no dpkg-deb / fakeroot needed)
#
#  Must run on Linux x64 (Linux Mint / Ubuntu / Debian): jpackage cannot cross-build, and Gradle picks the JavaFX
#  native jars (-linux classifier) for the machine it runs on.
#
#  Pipeline
#    1. gradlew clean jar jpackageInput   application jar + runtime libraries (JavaFX linux jars, PDFBox)
#    2. jdeps --print-module-deps         the JDK modules the code actually uses, plus the ones jdeps cannot see
#    3. jlink                             minimal, compressed runtime image (build/installer-linux/runtime)
#    4. jpackage --type app-image         portable folder -> DocumentWorkbench-<ver>-linux-x64.tar.gz
#    5. jpackage --type deb               /opt/documentworkbench, application menu entry, fixed JVM options
#
#  Build machine prerequisites (Linux Mint 21/22):
#    JDK 21 with jmods   sudo apt install openjdk-21-jdk openjdk-21-jmods   (or any JDK 21 tarball; set JDK21_HOME)
#    .deb backend        sudo apt install fakeroot binutils                  (dpkg-deb is always present)
#
set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")"
ROOT=$PWD

APP_NAME='DocumentWorkbench'
APP_VERSION='1.0.0'
PKG_NAME='documentworkbench'            # Debian package names must be lowercase
MAIN_CLASS='org.example.ui.Launcher'
RUNTIME_LIMIT_MB=60                     # libjvm.so and the Linux native libraries are larger than their Windows DLLs

# Same memory and GC settings as the Windows build. JavaFX is forced onto the OpenGL pipeline (Prism-ES2, the Linux
# counterpart of Direct3D): no software fallback, GPU blacklist ignored.
JAVA_OPTIONS=(
    -Xmx300m -Xms32m
    -XX:+UseG1GC -XX:MaxGCPauseMillis=20
    -Dprism.order=es2 -Dprism.forceGPU=true
    -Dfile.encoding=UTF-8 "-Ddwb.version=$APP_VERSION"
)

# Modules jdeps cannot find because they are reached through services or locale lookup:
#   jdk.crypto.ec    SunEC provider on JDK 21: X25519 pairing, ECDHE for TLS (Gemini / web search over HTTPS)
#   jdk.localedata   Turkish number and date formats (trimmed to tr with --include-locales)
SERVICE_MODULES=(jdk.crypto.ec jdk.localedata)

# Shared libraries JavaFX loads at run time from its own jars (so jpackage cannot detect them): GTK 3 for the glass
# windowing layer, libGL for Prism-ES2. Ubuntu 24.04 / Mint 22 renamed GTK to libgtk-3-0t64, hence the alternative.
DEB_DEPENDS='libgtk-3-0t64 | libgtk-3-0, libgl1, libx11-6, libxtst6, fontconfig'

SKIP_BUILD=0
BUILD_DEB=1
for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=1 ;;
        --no-deb)     BUILD_DEB=0 ;;
        -h|--help)    sed -n '3,20p' "$0" | sed 's/^#  \{0,1\}//'; exit 0 ;;
        *) echo "Unknown argument: $arg" >&2; exit 2 ;;
    esac
done

step() { printf '\n\033[36m==> %s\033[0m\n' "$*"; }
die()  { printf '\033[31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }
size_mb() { du -sb "$1" | awk '{ printf "%.1f", $1 / 1048576 }'; }

[ "$(uname -s)" = Linux ] || die 'This script builds Linux packages and must run on Linux (jpackage cannot cross-build).'
[ "$(uname -m)" = x86_64 ] || die "Linux x64 target: this machine is $(uname -m); the JavaFX linux jars are x64 only."
if grep -q $'\r' gradlew "$0"; then
    die "Windows line endings (CRLF) in gradlew / build-linux.sh. Fix once with:  sed -i 's/\r\$//' gradlew build-linux.sh"
fi

# ---------------------------------------------------------------------------------------------------- JDK 21
is_jdk21() {
    local c=$1
    [ -n "$c" ] && [ -x "$c/bin/jpackage" ] && [ -x "$c/bin/jlink" ] && [ -f "$c/jmods/java.base.jmod" ] &&
        grep -Eq '^JAVA_VERSION="21[".]' "$c/release" 2>/dev/null
}
find_jdk21() {
    local c
    for c in "${JDK21_HOME:-}" "${JAVA_HOME:-}" "$HOME"/.gradle/jdks/*21* /usr/lib/jvm/*21* /opt/*jdk*21*; do
        if is_jdk21 "$c"; then (cd "$c" && pwd -P); return 0; fi
    done
    return 1
}
JDK=$(find_jdk21) || die 'JDK 21 with jmods not found. Install openjdk-21-jdk and openjdk-21-jmods, or set JDK21_HOME.'
export JAVA_HOME=$JDK
echo "JDK 21: $JDK"

# ---------------------------------------------------------------------------------------------------- 1. build
INPUT_DIR="$ROOT/build/jpackage/input"
OUT="$ROOT/build/installer-linux"
JAR='kurumsal_bir_deneme-1.0-SNAPSHOT.jar'

if [ "$SKIP_BUILD" = 0 ]; then
    step 'gradlew clean jar jpackageInput'
    # The toolchain in build.gradle asks for Java 21; point Gradle at the JDK found above instead of provisioning one.
    bash ./gradlew clean jar jpackageInput --console=plain -q \
        "-Porg.gradle.java.installations.paths=$JDK" -Porg.gradle.java.installations.auto-download=false
fi
[ -f "$INPUT_DIR/$JAR" ] || die "Application jar missing: $INPUT_DIR/$JAR"
for f in "$INPUT_DIR"/*.jar; do printf '  %-40s %8s KB\n' "$(basename "$f")" "$(( $(stat -c %s "$f") / 1024 ))"; done
# Nothing web-related may ship: no WebView, no JavaFX media, no Swing bridge.
if ls "$INPUT_DIR" | grep -Eq 'javafx-(web|media|swing)'; then
    die "Forbidden JavaFX modules in the input: $(ls "$INPUT_DIR" | grep -E 'javafx-(web|media|swing)' | tr '\n' ' ')"
fi
# A --skip-build after a Windows build would package the -win native jars.
if ls "$INPUT_DIR" | grep -Eq 'javafx-.*-(win|mac)'; then
    die 'build/jpackage/input holds Windows/macOS JavaFX jars; run without --skip-build on this machine.'
fi
ls "$INPUT_DIR" | grep -q 'javafx-graphics-.*-linux\.jar' || die 'JavaFX linux jars missing from build/jpackage/input.'

# ---------------------------------------------------------------------------------------------------- 2. jdeps
step 'jdeps: JDK modules used by the application and its libraries'
JARS=()
for f in "$INPUT_DIR"/*.jar; do if [ "$(stat -c %s "$f")" -gt 1024 ]; then JARS+=("$f"); fi; done
DEPS=$("$JDK/bin/jdeps" --multi-release 21 --ignore-missing-deps --print-module-deps \
        --class-path "$INPUT_DIR/*" "${JARS[@]}" 2>/dev/null | tail -n 1) || die 'jdeps failed'
[ -n "$DEPS" ] || die 'jdeps failed'
echo "  jdeps:    $DEPS"
MODULES=$(printf '%s\n' ${DEPS//,/ } "${SERVICE_MODULES[@]}" | sed '/^$/d' | sort -u | paste -sd, -)
echo "  runtime:  $MODULES"

# ---------------------------------------------------------------------------------------------------- 3. jlink
step 'jlink: minimal runtime image'
# On Linux --strip-debug also strips the native symbols out of libjvm.so & co. and runs objcopy for that.
command -v objcopy >/dev/null 2>&1 || die 'objcopy not found (jlink --strip-debug needs it): sudo apt install binutils'
rm -rf "$OUT"
mkdir -p "$OUT"
RUNTIME="$OUT/runtime"
JLINK_ARGS=(--add-modules "$MODULES"
            --include-locales=tr
            --strip-debug --no-header-files --no-man-pages
            --compress=zip-9
            --output "$RUNTIME")
"$JDK/bin/jlink" "${JLINK_ARGS[@]}"
# Files a packaged app never loads: the -splash library.
rm -f "$RUNTIME/lib/libsplashscreen.so"
RUNTIME_MB=$(size_mb "$RUNTIME")
echo "  runtime size: $RUNTIME_MB MB (limit $RUNTIME_LIMIT_MB MB)"
if awk -v s="$RUNTIME_MB" -v l="$RUNTIME_LIMIT_MB" 'BEGIN { exit !(s > l) }'; then
    die "Runtime image is $RUNTIME_MB MB, above $RUNTIME_LIMIT_MB MB"
fi

# Smoke test: the trimmed runtime starts and still has the Turkish locale data.
"$RUNTIME/bin/java" -version >/dev/null 2>&1 || die "The runtime image does not start: $("$RUNTIME/bin/java" -version 2>&1)"
"$RUNTIME/bin/java" --list-modules | grep -q '^jdk.localedata' || die 'jdk.localedata missing from the runtime image'

# ---------------------------------------------------------------------------------------------------- 4. jpackage
CLI_PROPS="$OUT/dwb-cli.properties"
printf 'main-class=org.example.App\njava-options=%s\nlinux-shortcut=false\n' "${JAVA_OPTIONS[*]}" > "$CLI_PROPS"

COMMON=(--name "$APP_NAME"
        --app-version "$APP_VERSION"
        --vendor 'Document Workbench'
        --description 'Belge Tezgahi - yerel belge arama'
        --input "$INPUT_DIR"
        --main-jar "$JAR"
        --main-class "$MAIN_CLASS"
        --runtime-image "$RUNTIME"
        --add-launcher "dwb-cli=$CLI_PROPS")
for o in "${JAVA_OPTIONS[@]}"; do COMMON+=(--java-options "$o"); done
ICON="$ROOT/src/main/resources/icon.png"
if [ -f "$ICON" ]; then COMMON+=(--icon "$ICON"); else echo '  (no src/main/resources/icon.png - default icon)'; fi

step 'jpackage --type app-image -> portable .tar.gz'
IMAGE_DIR="$OUT/image"
"$JDK/bin/jpackage" --type app-image "${COMMON[@]}" --dest "$IMAGE_DIR"
TARBALL="$OUT/$APP_NAME-$APP_VERSION-linux-x64.tar.gz"
tar -C "$IMAGE_DIR" --owner=0 --group=0 -czf "$TARBALL" "$APP_NAME"

DEB=''
if [ "$BUILD_DEB" = 1 ]; then
    step 'jpackage --type deb'
    command -v dpkg-deb >/dev/null 2>&1 || die 'dpkg-deb not found (a Debian-based system is required for .deb).'
    command -v fakeroot >/dev/null 2>&1 || die 'fakeroot not found: sudo apt install fakeroot   (or use --no-deb)'
    "$JDK/bin/jpackage" --type deb "${COMMON[@]}" \
        --linux-package-name "$PKG_NAME" \
        --linux-app-release 1 \
        --linux-app-category misc \
        --linux-menu-group 'Office' \
        --linux-shortcut \
        --linux-package-deps "$DEB_DEPENDS" \
        --dest "$OUT"
    DEB=$(ls "$OUT"/*.deb 2>/dev/null | head -n 1)
    [ -n "$DEB" ] || die 'jpackage produced no .deb file'
fi

# ---------------------------------------------------------------------------------------------------- report
step 'Done'
echo "  portable:  $TARBALL ($(size_mb "$TARBALL") MB)"
echo "             tar -xzf $(basename "$TARBALL") && ./$APP_NAME/bin/$APP_NAME"
if [ -n "$DEB" ]; then
    echo "  deb:       $DEB ($(size_mb "$DEB") MB)"
    echo "             sudo apt install ./$(basename "$DEB")   ->  /opt/$PKG_NAME/bin/$APP_NAME"
fi
echo "  runtime:   $RUNTIME_MB MB  [$MODULES]"
