<#
  Document Workbench - self-contained Windows installer (.exe) built only with the JDK 21 tools
  (jdeps -> jlink -> jpackage). The user's machine needs no Java installation.

    powershell -ExecutionPolicy Bypass -File build-exe.ps1            (or simply: build-exe.bat)
    build-exe.ps1 -Type msi                                            Windows Installer .msi instead of .exe
    build-exe.ps1 -SkipBuild                                           reuse build\jpackage\input

  Pipeline
    1. gradlew clean jar jpackageInput   application jar + runtime libraries (JavaFX win jars, PDFBox)
    2. jdeps --print-module-deps         the JDK modules the code actually uses, plus the ones jdeps cannot see
    3. jlink                             minimal, compressed runtime image (build\installer\runtime)
    4. jpackage --type exe               per-user installer, Start menu + desktop shortcut, fixed JVM options

  jpackage builds .exe/.msi files with the WiX Toolset (its own backend, not a separate packager). When candle.exe /
  light.exe (WiX 3) or wix.exe (WiX 4+) is not on PATH, the official WiX 3.14 binaries zip is unpacked to .tools\wix314
  (no installation, no administrator rights).
#>
param(
    [ValidateSet('exe', 'msi')] [string] $Type = 'exe',
    [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-Location -LiteralPath $PSScriptRoot

$AppName     = 'DocumentWorkbench'
$AppVersion  = '1.0.0'
$MainClass   = 'org.example.ui.Launcher'
$UpgradeUuid = '6f1d3c2a-5b7e-4a61-9c1f-2d8e4b7a9c30'   # same as gradlew packageInstaller: one product, one upgrade line
$RuntimeLimitMB = 45

# Heap cap 300 MB, small initial heap, G1 tuned for short pauses; JavaFX forced onto Direct3D (no software pipeline).
$JavaOptions = @(
    '-Xmx300m', '-Xms32m',
    '-XX:+UseG1GC', '-XX:MaxGCPauseMillis=20',
    '-Dprism.order=d3d', '-Dprism.forceGPU=true',
    '-Dfile.encoding=UTF-8', "-Ddwb.version=$AppVersion"
)

# Modules jdeps cannot find because they are reached through services or locale lookup:
#   jdk.crypto.ec    SunEC provider on JDK 21: X25519 pairing, ECDHE for TLS (Gemini / web search over HTTPS)
#   jdk.localedata   Turkish number and date formats (trimmed to tr with --include-locales)
$ServiceModules = @('jdk.crypto.ec', 'jdk.localedata')

function Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
# Native tools report through exit codes; their stderr (warnings) must not abort the script under 'Stop'.
function Invoke-Tool($exe, [string[]] $toolArgs) {
    $ErrorActionPreference = 'Continue'
    & $exe @toolArgs
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($exe)) failed with exit code $LASTEXITCODE" }
}
function SizeMB($path) { (Get-ChildItem -LiteralPath $path -Recurse -File | Measure-Object Length -Sum).Sum / 1MB }

# ---------------------------------------------------------------------------------------------------- JDK 21
function Find-Jdk21 {
    $candidates = @($env:JDK21_HOME, $env:JAVA_HOME) +
        @(Get-ChildItem "$env:USERPROFILE\.gradle\jdks" -Directory -Filter '*21*' -ErrorAction SilentlyContinue |
          ForEach-Object FullName)
    foreach ($c in $candidates) {
        if (-not $c) { continue }
        $release = Join-Path $c 'release'
        if ((Test-Path "$c\bin\jpackage.exe") -and (Test-Path "$c\jmods\java.base.jmod") -and (Test-Path $release) -and
            (Select-String -LiteralPath $release -Pattern '^JAVA_VERSION="21[".]' -Quiet)) { return $c }
    }
    throw 'JDK 21 with jmods not found. Set JDK21_HOME, or run "gradlew build" once so Gradle provisions it.'
}
$Jdk = Find-Jdk21
$env:JAVA_HOME = $Jdk
Write-Host "JDK 21: $Jdk"

# ---------------------------------------------------------------------------------------------------- WiX
function Ensure-Wix {
    $onPath = { param($exe) [bool](Get-Command $exe -ErrorAction SilentlyContinue) }
    if ((& $onPath 'wix.exe') -or ((& $onPath 'candle.exe') -and (& $onPath 'light.exe'))) { return }
    $wixDir = Join-Path $PSScriptRoot '.tools\wix314'
    if (-not (Test-Path "$wixDir\candle.exe")) {
        Step 'WiX Toolset 3.14 binaries -> .tools\wix314 (jpackage installer backend)'
        New-Item -ItemType Directory -Force $wixDir | Out-Null
        $zip = Join-Path $wixDir 'wix314-binaries.zip'
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -UseBasicParsing -OutFile $zip `
            -Uri 'https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip'
        Expand-Archive -LiteralPath $zip -DestinationPath $wixDir -Force
        Remove-Item -LiteralPath $zip
    }
    $env:PATH = "$wixDir;$env:PATH"
}

# ---------------------------------------------------------------------------------------------------- 1. build
$InputDir = Join-Path $PSScriptRoot 'build\jpackage\input'
$Out   = Join-Path $PSScriptRoot 'build\installer'
$Jar   = 'kurumsal_bir_deneme-1.0-SNAPSHOT.jar'

if (-not $SkipBuild) {
    Step 'gradlew clean jar jpackageInput'
    Invoke-Tool (Join-Path $PSScriptRoot 'gradlew.bat') @('clean', 'jar', 'jpackageInput', '--console=plain', '-q')
}
if (-not (Test-Path "$InputDir\$Jar")) { throw "Application jar missing: $InputDir\$Jar" }
Get-ChildItem $InputDir -Filter *.jar | ForEach-Object { '  {0,-40} {1,8:N0} KB' -f $_.Name, ($_.Length / 1KB) }
# Nothing web-related may ship: no WebView, no JavaFX media, no Swing bridge.
$forbidden = Get-ChildItem $InputDir -Filter *.jar | Where-Object { $_.Name -match 'javafx-(web|media|swing)' }
if ($forbidden) { throw "Forbidden JavaFX modules in the input: $($forbidden.Name -join ', ')" }

# ---------------------------------------------------------------------------------------------------- 2. jdeps
Step 'jdeps: JDK modules used by the application and its libraries'
$jars = @(Get-ChildItem $InputDir -Filter *.jar | Where-Object Length -gt 1KB | ForEach-Object FullName)
$ErrorActionPreference = 'Continue'
$deps = & "$Jdk\bin\jdeps.exe" --multi-release 21 --ignore-missing-deps --print-module-deps `
        --class-path "$InputDir\*" @jars 2>$null | Select-Object -Last 1
$jdepsExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($jdepsExit -ne 0 -or -not $deps) { throw 'jdeps failed' }
Write-Host "  jdeps:    $deps"
$Modules = (@($deps.Trim() -split ',') + $ServiceModules | Where-Object { $_ } | Sort-Object -Unique) -join ','
Write-Host "  runtime:  $Modules"

# ---------------------------------------------------------------------------------------------------- 3. jlink
Step 'jlink: minimal runtime image'
if (Test-Path $Out) { Remove-Item -Recurse -Force $Out }
New-Item -ItemType Directory -Force $Out | Out-Null
$Runtime = Join-Path $Out 'runtime'
Invoke-Tool "$Jdk\bin\jlink.exe" @(
    '--add-modules', $Modules,
    '--include-locales=tr',
    '--strip-debug', '--no-header-files', '--no-man-pages',
    '--compress=zip-9',
    '--output', $Runtime)
# Files a packaged app never loads: the jvm.lib link library, the -splash DLL, and the app-local Universal CRT
# (Windows 10/11 always use the system UCRT in System32 and ignore app-local copies). vcruntime/msvcp140 stay.
Remove-Item -Force "$Runtime\lib\jvm.lib", "$Runtime\bin\splashscreen.dll" -ErrorAction SilentlyContinue
Get-ChildItem "$Runtime\bin" -File | Where-Object { $_.Name -like 'api-ms-win-*.dll' -or $_.Name -eq 'ucrtbase.dll' } |
    Remove-Item -Force
$runtimeMB = SizeMB $Runtime
Write-Host ('  runtime size: {0:N1} MB (limit {1} MB)' -f $runtimeMB, $RuntimeLimitMB)
if ($runtimeMB -gt $RuntimeLimitMB) { throw ('Runtime image is {0:N1} MB, above {1} MB' -f $runtimeMB, $RuntimeLimitMB) }

# Smoke test: the trimmed runtime starts.
$ErrorActionPreference = 'Continue'
$probe = & "$Runtime\bin\java.exe" -version 2>&1 | Out-String
$probeExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($probeExit -ne 0) { throw "The runtime image does not start:`n$probe" }

# ---------------------------------------------------------------------------------------------------- 4. jpackage
Ensure-Wix
Step "jpackage --type $Type"
$cliProps = Join-Path $Out 'dwb-cli.properties'
[IO.File]::WriteAllText($cliProps,
    "main-class=org.example.App`r`njava-options=$($JavaOptions -join ' ')`r`nwin-console=true`r`n",
    (New-Object Text.UTF8Encoding $false))

$jp = @(
    '--type', $Type,
    '--name', $AppName,
    '--app-version', $AppVersion,
    '--vendor', 'Document Workbench',
    '--description', 'Belge Tezgahi - yerel belge arama',
    '--input', $InputDir,
    '--main-jar', $Jar,
    '--main-class', $MainClass,
    '--runtime-image', $Runtime,
    '--add-launcher', "dwb-cli=$cliProps",
    '--win-per-user-install',                # %LOCALAPPDATA%\DocumentWorkbench, no administrator rights
    '--win-menu', '--win-menu-group', 'Document Workbench',   # Start menu shortcut
    '--win-shortcut',                        # desktop shortcut
    '--win-upgrade-uuid', $UpgradeUuid,
    '--dest', $Out)
foreach ($o in $JavaOptions) { $jp += @('--java-options', $o) }
$icon = Join-Path $PSScriptRoot 'src\main\resources\icon.ico'
if (Test-Path $icon) { $jp += @('--icon', $icon) } else { Write-Host '  (no src\main\resources\icon.ico - default icon)' }

Invoke-Tool "$Jdk\bin\jpackage.exe" $jp

# ---------------------------------------------------------------------------------------------------- report
$installer = Get-ChildItem $Out -Filter "*.$Type" | Select-Object -First 1
if (-not $installer) { throw "jpackage produced no .$Type file" }
Step 'Done'
Write-Host ('  installer: {0}' -f $installer.FullName)
Write-Host ('  size:      {0:N1} MB ({1:N0} bytes)' -f ($installer.Length / 1MB), $installer.Length)
Write-Host ('  runtime:   {0:N1} MB  [{1}]' -f $runtimeMB, $Modules)
