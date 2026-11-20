param([string]$OutputFile)
$ErrorActionPreference = 'Stop'
$handoffRoot = $PSScriptRoot
if (-not $OutputFile) { $OutputFile = Join-Path $handoffRoot 'releases\Tabule-Link-working.apk' }
$jdkDirectory = Get-ChildItem -LiteralPath (Join-Path $handoffRoot 'toolchain\jdk') -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe') } | Select-Object -First 1
$buildToolsDirectory = Get-ChildItem -LiteralPath (Join-Path $handoffRoot 'toolchain\build-tools') -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'aapt2.exe') } | Select-Object -First 1
$platformDirectory = Get-ChildItem -LiteralPath (Join-Path $handoffRoot 'toolchain\platform') -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'android.jar') } | Select-Object -First 1
if (-not $jdkDirectory -or -not $buildToolsDirectory -or -not $platformDirectory) { throw 'Chybí přibalené nástroje JDK nebo Android SDK.' }
$pythonCommand = Get-Command python -ErrorAction Stop
& $pythonCommand.Source (Join-Path $handoffRoot 'project\build.py') --java-home $jdkDirectory.FullName --build-tools $buildToolsDirectory.FullName --android-jar (Join-Path $platformDirectory.FullName 'android.jar') --output $OutputFile
if ($LASTEXITCODE -ne 0) { throw "Sestavení selhalo: $LASTEXITCODE" }
