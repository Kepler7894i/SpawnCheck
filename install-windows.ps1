<#
.SYNOPSIS
  Installs Spawn Check (and, on Fabric, Fabric API if missing) into a Minecraft mods folder. Windows version;
  see install-linux.sh (Linux) and install-macos.sh (macOS).

.DESCRIPTION
  Where the Spawn Check jar comes from:
    - run from a repository checkout (gradlew next to this script): the mod is compiled first;
    - run from a release download (spawncheck-<loader>-*.jar next to this script): that jar is used.

  Dependencies installed automatically (skip with -NoDeps):
    - Fabric API (Fabric only), only if the mods folder has no fabric-api jar yet (an existing one is never replaced).
  Fabric Loader / NeoForge themselves are assumed to be installed already and are never touched.

  Older copies of Spawn Check for the same loader in the mods folder are replaced, so two versions never load together.
  The mod must be installed on the server too (point -ModsDir at the server's mods folder); clients and servers use the same jar.

.PARAMETER ModsDir
  Target mods folder. Defaults to %APPDATA%\.minecraft\mods. Use this to install into a server or another launcher's instance.

.PARAMETER Loader
  fabric (default) or neoforge.

.PARAMETER SkipBuild
  Use the jar already in <loader>\build\libs instead of rebuilding.

.PARAMETER NoDeps
  Do not install dependencies (Fabric API); only Spawn Check itself is installed.

.EXAMPLE
  .\install-windows.ps1
  .\install-windows.ps1 -Loader neoforge -ModsDir "D:\games\mc\mods" -SkipBuild
  .\install-windows.ps1 -ModsDir "D:\mc\server\mods" -NoDeps
#>
param(
    [string]$ModsDir = (Join-Path $env:APPDATA ".minecraft\mods"),
    [ValidateSet("fabric", "neoforge")][string]$Loader = "fabric",
    [switch]$SkipBuild,
    [switch]$NoDeps
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

# Versions: from gradle.properties in a checkout, otherwise derived from the jar name / the Fabric Maven.
$props = @{}
$propsFile = Join-Path $root "gradle.properties"
if (Test-Path $propsFile) {
    Get-Content $propsFile | ForEach-Object {
        if ($_ -match '^\s*([^#=\s]+)\s*=\s*(.*)$') { $props[$Matches[1]] = $Matches[2].Trim() }
    }
}

if (-not (Test-Path $ModsDir)) { New-Item -ItemType Directory -Path $ModsDir | Out-Null }

# 1. Get the jar
if ((Test-Path (Join-Path $root "gradlew.bat")) -and -not $SkipBuild) {
    Write-Host "Building Spawn Check ($Loader)..."
    Push-Location $root
    try {
        & .\gradlew.bat ":${Loader}:build" --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
    } finally { Pop-Location }
}
$searchDirs = @((Join-Path $root "$Loader\build\libs"), $root) | Where-Object { Test-Path $_ }
$modJar = $searchDirs | ForEach-Object { Get-ChildItem $_ -Filter "spawncheck-$Loader-*.jar" -ErrorAction SilentlyContinue } |
    Where-Object { $_.Name -notlike "*-sources.jar" } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $modJar) { throw "No spawncheck-$Loader-*.jar found. Run this from a repository checkout, or put the release jar next to this script." }

$mc = if ($props["minecraftVersion"]) { $props["minecraftVersion"] } elseif ($modJar.Name -match "^spawncheck-$Loader-([^-]+)-") { $Matches[1] } else { throw "Cannot determine the Minecraft version" }

# 2. Work in a temp folder so a failure never leaves the mods folder half-updated.
$tmp = Join-Path ([IO.Path]::GetTempPath()) "spawncheck-install"
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
New-Item -ItemType Directory -Path $tmp | Out-Null

# 3. Dependencies (unless -NoDeps)
$fabricApiName = $null
if (-not $NoDeps -and $Loader -eq "fabric") {
    $existingApi = Get-ChildItem $ModsDir -Filter "fabric-api-*.jar" -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike "*-sources.jar" }
    if ($existingApi) {
        Write-Host "Fabric API already present, leaving it alone."
    } else {
        $fabricRepo = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api"
        if ($props["fabricApiVersion"]) {
            $fapi = "$($props['fabricApiVersion'])+$mc"
        } else {
            $meta = Invoke-WebRequest -UseBasicParsing "$fabricRepo/maven-metadata.xml"
            $fapi = ([xml]$meta.Content).metadata.versioning.versions.version | Where-Object { $_ -like "*+$mc" } | Select-Object -Last 1
            if (-not $fapi) { throw "Could not find a Fabric API version for Minecraft $mc" }
        }
        $fabricApiName = "fabric-api-$fapi.jar"
        Write-Host "Downloading $fabricApiName..."
        Invoke-WebRequest -UseBasicParsing -OutFile (Join-Path $tmp $fabricApiName) -Uri "$fabricRepo/$fapi/$fabricApiName"
    }
}

# 4. Remove old versions, then copy the new ones in. (spawncheck-<digit>* are the jars from before the loader was part of the name.)
$stagedMod = Join-Path $tmp $modJar.Name
Copy-Item $modJar.FullName $stagedMod
$toInstall = @(@{ Name = $modJar.Name; Patterns = @("spawncheck-$Loader-*", "spawncheck-[0-9]*"); Path = $stagedMod })
if ($fabricApiName) { $toInstall += @{ Name = $fabricApiName; Patterns = @(); Path = (Join-Path $tmp $fabricApiName) } }
foreach ($item in $toInstall) {
    foreach ($pattern in $item.Patterns) {
        # -like (not -Filter) so that [0-9] character classes work.
        Get-ChildItem $ModsDir -Filter "*.jar" | Where-Object { $_.Name -like "$pattern.jar" } | ForEach-Object {
            $old = $_
            Write-Host "Removing $($old.Name)"
            try { Remove-Item $old.FullName -Force -ErrorAction Stop }
            catch { throw "Cannot replace $($old.Name): is Minecraft (or a server) running with it loaded? Close it and re-run." }
        }
    }
    Copy-Item $item.Path (Join-Path $ModsDir $item.Name) -Force
    Write-Host "Installed $($item.Name)"
}

Remove-Item $tmp -Recurse -Force
Write-Host "`nDone. Mods folder: $ModsDir"
