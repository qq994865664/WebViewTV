param([Parameter(Mandatory=$true)][string]$Apk)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Apk).Path)
try {
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName.Contains('\')) { throw "APK entry uses a Windows path: $($entry.FullName)" }
    }
    $assetRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'app\src\main\assets')).Path
    foreach ($source in Get-ChildItem -LiteralPath $assetRoot -File -Recurse) {
        $relative = $source.FullName.Substring($assetRoot.Length + 1).Replace('\', '/')
        $entry = $archive.GetEntry("assets/$relative")
        if ($null -eq $entry) { throw "APK asset missing: assets/$relative" }
        $stream = $entry.Open()
        $digest = [System.Security.Cryptography.SHA256]::Create()
        try {
            $packedHash = [BitConverter]::ToString($digest.ComputeHash($stream)).Replace('-', '')
            $sourceHash = (Get-FileHash -LiteralPath $source.FullName -Algorithm SHA256).Hash
            if ($packedHash -ne $sourceHash) { throw "APK asset bytes differ: assets/$relative" }
        } finally { $digest.Dispose(); $stream.Dispose() }
    }
    Write-Output 'APK asset paths and contents verified.'
} finally { $archive.Dispose() }
