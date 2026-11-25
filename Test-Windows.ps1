$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$csc = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $csc)) { throw "Chybí .NET Framework kompilátor: $csc" }
$buildDir = Join-Path $root 'windows\build'
if (-not (Test-Path -LiteralPath $buildDir)) { New-Item -ItemType Directory -Path $buildDir -Force | Out-Null }
$testExe = Join-Path $buildDir 'WindowsTests.exe'
& $csc /nologo /target:exe /platform:x64 /utf8output /out:$testExe `
    /r:System.dll /r:System.Drawing.dll /r:System.Numerics.dll `
    (Join-Path $root 'windows\src\Protocol.cs') `
    (Join-Path $root 'windows\src\Proto2.cs') `
    (Join-Path $root 'windows\src\DisplayMath.cs') `
    (Join-Path $root 'windows\tests\WindowsTests.cs') `
    (Join-Path $root 'windows\tests\Proto2Tests.cs') `
    (Join-Path $root 'windows\tests\DisplayMathTests.cs')
if ($LASTEXITCODE -ne 0) { throw "Překlad Windows testů selhal: $LASTEXITCODE" }
& $testExe
if ($LASTEXITCODE -ne 0) { throw "Windows testy selhaly: $LASTEXITCODE" }
