#!/usr/bin/env bash
# Regenerates res/values{,-night}/colors_theme_presets.xml: the colour presets on the Themes &
# icons screen. Uses Material's TonalSpot, Fidelity and Monochrome schemes straight from the
# material AAR in the Gradle cache. Seeds live in Gen.java; readable contrast is checked there.
set -euo pipefail
cd "$(dirname "$0")"
RES=../../V2rayNG/app/src/main/res
J="${JAVA_HOME:-$(ls -d "$HOME"/.jdks/jdk-17*/ | head -1)}/bin"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
AAR="$(ls ~/.gradle/caches/modules-2/files-2.1/com.google.android.material/material/1.14.0/*/material-1.14.0.aar | head -1)"
(cd "$TMP" && unzip -oq "$AAR" classes.jar)
ANN="$(ls ~/.gradle/caches/modules-2/files-2.1/androidx.annotation/annotation-jvm/*/*/annotation-jvm-*.jar | head -1)"
CP="$TMP/classes.jar:$ANN"
ROLES="$(grep -o 'name="md_theme_[A-Za-z]*"' "$RES/values/colors.xml" | sed 's/name="md_theme_//;s/"//' | paste -sd,)"
"$J/javac" -nowarn -cp "$CP" -d "$TMP/out" Gen.java 2>/dev/null
"$J/java" -cp "$TMP/out:$CP" Gen "$RES" "$ROLES"
echo "written: $RES/values{,-night}/colors_theme_presets.xml"
