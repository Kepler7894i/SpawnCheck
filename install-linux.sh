#!/usr/bin/env bash
# Installs Spawn Check (and, on Fabric, Fabric API if missing) into a Minecraft mods folder. Linux version;
# see install-windows.ps1 (Windows) and install-macos.sh (macOS).
#
# Usage: ./install-linux.sh [--mods-dir DIR] [--loader fabric|neoforge] [--skip-build] [--no-deps]
#
#   --mods-dir DIR  target mods folder (default ~/.minecraft/mods); use it for a server or another launcher's instance
#   --loader NAME   fabric (default) or neoforge
#   --skip-build    use the jar already in <loader>/build/libs instead of rebuilding
#   --no-deps       do not install dependencies (Fabric API); only Spawn Check itself
#
# Where the Spawn Check jar comes from:
#   - run from a repository checkout (gradlew next to this script): the mod is compiled first;
#   - run from a release download (spawncheck-<loader>-*.jar next to this script): that jar is used.
#
# On Fabric, Fabric API is installed only if the mods folder has no fabric-api jar yet (an existing one is never replaced).
# Fabric Loader / NeoForge themselves are assumed to be installed already. Older copies of Spawn Check for the same loader are replaced.
# The mod must be installed on the server too; clients and servers use the same jar.
# Needs: bash and curl (the build downloads the Java it needs).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mods_dir="$HOME/.minecraft/mods"
loader=fabric
skip_build=0
no_deps=0

while [ $# -gt 0 ]; do
  case "$1" in
    --mods-dir)   mods_dir="$2"; shift 2 ;;
    --loader)     loader="$2"; shift 2 ;;
    --skip-build) skip_build=1; shift ;;
    --no-deps)    no_deps=1; shift ;;
    -h|--help)    sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done
case "$loader" in fabric|neoforge) ;; *) echo "--loader must be fabric or neoforge" >&2; exit 2 ;; esac

prop() { [ -f "$root/gradle.properties" ] && grep -E "^$1=" "$root/gradle.properties" | head -n1 | cut -d= -f2- | tr -d '\r ' || true; }

mkdir -p "$mods_dir"

# 1. Get the jar
if [ -f "$root/gradlew" ] && [ "$skip_build" -eq 0 ]; then
  echo "Building Spawn Check ($loader)..."
  (cd "$root" && sh ./gradlew ":$loader:build" --console=plain)
fi
mod_jar=""
for dir in "$root/$loader/build/libs" "$root"; do
  [ -d "$dir" ] || continue
  # newest matching jar that is not a sources jar
  found="$(ls -t "$dir"/spawncheck-"$loader"-*.jar 2>/dev/null | grep -v -- '-sources\.jar$' | head -n1 || true)"
  if [ -n "$found" ]; then mod_jar="$found"; break; fi
done
[ -n "$mod_jar" ] || { echo "No spawncheck-$loader-*.jar found. Run this from a repository checkout, or put the release jar next to this script." >&2; exit 1; }

mc="$(prop minecraftVersion)"
if [ -z "$mc" ]; then mc="$(basename "$mod_jar" | sed -E "s/^spawncheck-$loader-([^-]+)-.*/\1/")"; fi

# 2. Work in a temp folder so a failure never leaves the mods folder half-updated.
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp:?}"' EXIT

# 3. Dependencies (unless --no-deps)
fabric_api_name=""
if [ "$no_deps" -eq 0 ] && [ "$loader" = fabric ]; then
  if ls "$mods_dir"/fabric-api-*.jar 2>/dev/null | grep -qv -- '-sources\.jar$'; then
    echo "Fabric API already present, leaving it alone."
  else
    fabric_repo="https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api"
    fapi="$(prop fabricApiVersion)"
    if [ -n "$fapi" ]; then fapi="$fapi+$mc"; else
      fapi="$(curl -fsSL "$fabric_repo/maven-metadata.xml" | grep -o "<version>[^<]*+$mc</version>" | sed 's:</\{0,1\}version>::g' | tail -n1)"
    fi
    [ -n "$fapi" ] || { echo "Could not find a Fabric API version for Minecraft $mc" >&2; exit 1; }
    fabric_api_name="fabric-api-$fapi.jar"
    echo "Downloading $fabric_api_name..."
    curl -fsSL -o "$tmp/$fabric_api_name" "$fabric_repo/$fapi/$fabric_api_name"
  fi
fi

# 4. Remove old versions, then copy the new ones in. (spawncheck-<digit>* are the jars from before the loader was part of the name.)
mod_name="$(basename "$mod_jar")"
cp "$mod_jar" "$tmp/$mod_name"
for old in "$mods_dir"/spawncheck-"$loader"-*.jar "$mods_dir"/spawncheck-[0-9]*.jar; do
  [ -e "$old" ] || continue
  echo "Removing $(basename "$old")"
  rm -f "$old" || { echo "Cannot replace $(basename "$old"): is Minecraft (or a server) running with it loaded, or is the folder read-only? Close it and re-run." >&2; exit 1; }
done
for name in "$mod_name" "$fabric_api_name"; do
  [ -n "$name" ] || continue
  cp "$tmp/$name" "$mods_dir/$name" || { echo "Cannot write to $mods_dir" >&2; exit 1; }
  echo "Installed $name"
done

echo
echo "Done. Mods folder: $mods_dir"
