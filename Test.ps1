$ErrorActionPreference = 'Stop'
$handoffRoot = $PSScriptRoot
$jdkDirectory = Get-ChildItem -LiteralPath (Join-Path $handoffRoot 'toolchain\jdk') -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe') } | Select-Object -First 1
if (-not $jdkDirectory) { throw 'Chybí přibalené JDK.' }
$pythonCommand = Get-Command python -ErrorAction Stop
& $pythonCommand.Source (Join-Path $handoffRoot 'project\test.py') --java-home $jdkDirectory.FullName
if ($LASTEXITCODE -ne 0) { throw "Testy selhaly: $LASTEXITCODE" }
