# Builds BigView: out\bigview.jar and (unless -JarOnly) dist\BigView\BigView.exe
param([switch]$JarOnly)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

Remove-Item -Recurse -Force out, dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force out\classes, out\lib | Out-Null

$sources = Get-ChildItem -Recurse src -Filter *.java | ForEach-Object { $_.FullName }
& javac --release 21 -encoding UTF-8 -d out\classes $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

& jar --create --file out\lib\bigview.jar --main-class bigview.Main -C out\classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
Write-Host 'Built out\lib\bigview.jar'
if ($JarOnly) { return }

& jpackage --type app-image --name BigView --dest dist `
    --input out\lib --main-jar bigview.jar --main-class bigview.Main `
    --add-modules 'java.desktop,jdk.charsets' `
    --jlink-options '--strip-debug --no-header-files --no-man-pages' `
    --java-options '-XX:+UseSerialGC -Xms32m -Xmx768m -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30'
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }
Write-Host 'Built dist\BigView\BigView.exe'
