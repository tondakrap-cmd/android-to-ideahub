param(
    [string]$Exe,
    [string]$KeyFile,
    [switch]$NewKey
)
# Podpis Windows EXE pro automatické aktualizace (RSA 3072, PKCS#1 v1.5, SHA-256).
# Vytvoří <exe>.sig (base64), který se nahrává do GitHub Release vedle EXE.
# Aplikace aktualizaci nainstaluje jen tehdy, když podpis sedí s veřejným klíčem v windows\src\UpdateCheck.cs.
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $KeyFile) { $KeyFile = Join-Path $root 'project\.signing\windows-update\tabule-link-update-key.xml' }

if ($NewKey) {
    if (Test-Path -LiteralPath $KeyFile) { throw "Klíč už existuje: $KeyFile. Nepřepisuji ho – nainstalované aplikace by přestaly přijímat aktualizace." }
    $dir = Split-Path -Parent $KeyFile
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $rsa = New-Object System.Security.Cryptography.RSACng 3072
    [IO.File]::WriteAllText($KeyFile, $rsa.ToXmlString($true), [Text.Encoding]::ASCII)
    $pub = $rsa.ToXmlString($false)
    [IO.File]::WriteAllText((Join-Path $dir 'tabule-link-update-public.xml'), $pub, [Text.Encoding]::ASCII)
    Write-Output "Soukromý klíč: $KeyFile (není v gitu – zálohujte ho mimo počítač)"
    Write-Output 'Veřejný klíč pro windows\src\UpdateCheck.cs (WindowsPublicKeyXml):'
    Write-Output $pub
    return
}

if (-not $Exe) { throw 'Zadejte -Exe cesta\TabuleLink-Windows-X.Y.Z.exe' }
if (-not (Test-Path -LiteralPath $KeyFile)) { throw "Chybí podpisový klíč aktualizací: $KeyFile" }
$exePath = (Resolve-Path -LiteralPath $Exe).Path
$rsa = New-Object System.Security.Cryptography.RSACng
$rsa.FromXmlString([IO.File]::ReadAllText($KeyFile))
$bytes = [IO.File]::ReadAllBytes($exePath)
$sig = $rsa.SignData($bytes, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)
$sigPath = $exePath + '.sig'
[IO.File]::WriteAllText($sigPath, [Convert]::ToBase64String($sig), [Text.Encoding]::ASCII)

# kontrola: podpis musí sedět s veřejným klíčem, který je zabudovaný v aplikaci
$src = [IO.File]::ReadAllText((Join-Path $root 'windows\src\UpdateCheck.cs'))
$m = [Regex]::Match($src, 'WindowsPublicKeyXml = "([^"]+)"')
if (-not $m.Success) { throw 'V UpdateCheck.cs chybí WindowsPublicKeyXml.' }
$pubRsa = New-Object System.Security.Cryptography.RSACng
$pubRsa.FromXmlString($m.Groups[1].Value)
if (-not $pubRsa.VerifyData($bytes, $sig, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)) {
    Remove-Item -LiteralPath $sigPath -Force
    throw 'Podpis nesedí s veřejným klíčem v UpdateCheck.cs – používáte jiný klíč, než se kterým byla aplikace sestavena.'
}
Write-Output "Podpis: $sigPath"
