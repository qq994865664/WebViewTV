param(
    [string]$Sdk=$env:ANDROID_HOME,
    [string]$Jdk=$env:JAVA_HOME,
    [switch]$Release,
    [string]$KeyStore,
    [string]$KeyAlias='webviewtv',
    [string]$Lineage
)
$ErrorActionPreference='Stop'
if ([string]::IsNullOrWhiteSpace($Sdk)) { $Sdk='D:\FangteToolchains\android-sdk' }
if ([string]::IsNullOrWhiteSpace($Jdk)) { $Jdk='D:\FangteToolchains\jdk17\jdk-17.0.20.1+1' }
if ($Release) {
    if ([string]::IsNullOrWhiteSpace($KeyStore) -or !(Test-Path -LiteralPath $KeyStore)) { throw 'Release requires -KeyStore pointing to an existing private signing key.' }
    if ([string]::IsNullOrWhiteSpace($env:WVT_RELEASE_STORE_PASSWORD)) { throw 'Set WVT_RELEASE_STORE_PASSWORD in the signing process environment.' }
    if ($Lineage -and !(Test-Path -LiteralPath $Lineage)) { throw 'Signing lineage file does not exist.' }
}
Push-Location $PSScriptRoot
try {
$bt=Join-Path $Sdk 'build-tools\36.0.0'; $android=Join-Path $Sdk 'platforms\android-36\android.jar'; $env:JAVA_HOME=$Jdk; $env:PATH="$Jdk\bin;$env:PATH"
function Run([string]$exe,[string[]]$arguments){ & $exe @arguments; if($LASTEXITCODE -ne 0){throw "$exe failed: $LASTEXITCODE"} }
New-Item -ItemType Directory -Force build\classes,build\dex,dist | Out-Null
[xml]$manifest=Get-Content -LiteralPath 'app\src\main\AndroidManifest.xml' -Raw
$androidNs='http://schemas.android.com/apk/res/android'
$minSdk=$manifest.manifest.'uses-sdk'.GetAttribute('minSdkVersion',$androidNs)
$targetSdk=$manifest.manifest.'uses-sdk'.GetAttribute('targetSdkVersion',$androidNs)
$version=$manifest.manifest.GetAttribute('versionName',$androidNs)
if ($Release -and $manifest.manifest.application.GetAttribute('debuggable',$androidNs) -eq 'true') { throw 'Release manifest must not enable debugging.' }
if(!(Test-Path app\src\main\assets\china_mainland.json)){throw 'Bundled channel configuration is missing.'}
Run "$bt\aapt2.exe" @('compile','--dir','app\src\main\res','-o','build\resources.zip')
Run "$bt\aapt2.exe" @('link','-o','build\unsigned.apk','-I',$android,'--manifest','app\src\main\AndroidManifest.xml','build\resources.zip','--min-sdk-version',$minSdk,'--target-sdk-version',$targetSdk)
# Windows aapt2 -A writes backslashes for nested assets. jar writes Android ZIP paths with '/'.
Run "$Jdk\bin\jar.exe" @('uf','build\unsigned.apk','-C','app\src\main','assets')
& (Join-Path $PSScriptRoot 'verify-assets.ps1') -Apk 'build\unsigned.apk'
$javaFiles=@(Get-ChildItem app\src\main\java -Filter *.java -Recurse | ForEach-Object FullName)
Run "$Jdk\bin\javac.exe" (@('-encoding','UTF-8','-source','8','-target','8','-classpath',$android,'-d','build\classes')+$javaFiles)
$classFiles=@(Get-ChildItem build\classes -Filter *.class -Recurse | ForEach-Object FullName)
$dexMode=if($Release){'--release'}else{'--debug'}
Run "$bt\d8.bat" (@($dexMode,'--lib',$android,'--min-api',$minSdk,'--output','build\dex')+$classFiles)
Push-Location build\dex
try {Run "$Jdk\bin\jar.exe" @('uf','..\unsigned.apk','classes.dex')} finally {Pop-Location}
Run "$bt\zipalign.exe" @('-f','4','build\unsigned.apk','build\aligned.apk')
if ($Release) {
    $output="dist\WebViewTV-$version-android9plus-universal-release.apk"
    $signArgs=@('sign','--ks',$KeyStore,'--ks-key-alias',$KeyAlias,'--ks-pass','env:WVT_RELEASE_STORE_PASSWORD','--key-pass','env:WVT_RELEASE_STORE_PASSWORD','--min-sdk-version',$minSdk,'--v1-signing-enabled','false','--v2-signing-enabled','false','--v3-signing-enabled','true','--v4-signing-enabled','false')
    if ($Lineage) { $signArgs+=@('--lineage',$Lineage,'--rotation-min-sdk-version','28') }
    Run "$bt\apksigner.bat" ($signArgs+@('--out',$output,'build\aligned.apk'))
} else {
    $output='dist\webviewtv-reconstructed-debug.apk'
    if(!(Test-Path build\debug.keystore)){Run "$Jdk\bin\keytool.exe" @('-genkeypair','-keystore','build\debug.keystore','-storepass','android','-keypass','android','-alias','androiddebugkey','-dname','CN=WebViewTV Research,O=Local,C=CN','-keyalg','RSA','-keysize','2048','-validity','3650')}
    Run "$bt\apksigner.bat" @('sign','--ks','build\debug.keystore','--ks-pass','pass:android','--key-pass','pass:android','--out',$output,'build\aligned.apk')
}
Run "$bt\apksigner.bat" @('verify','--verbose','--min-sdk-version',$minSdk,$output)
Get-FileHash -LiteralPath $output -Algorithm SHA256
if ($Release) {
    # This Java/WebView application has no ABI-specific libraries. Keep a named
    # ARMv7 download, but refuse this alias if native binaries are added later.
    $archive=[IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $output).Path)
    try {
        if (@($archive.Entries | Where-Object { $_.FullName -like 'lib/*' -or $_.FullName -like '*.so' }).Count) {
            throw 'Native libraries found; review ARMv7 compatibility before publishing an alias.'
        }
    } finally { $archive.Dispose() }
    $armv7="dist\WebViewTV-$version-android9plus-armeabi-v7a-release.apk"
    Copy-Item -LiteralPath $output -Destination $armv7
    $checksums=foreach ($package in @($output,$armv7)) {
        (Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash.ToLowerInvariant()+'  '+(Split-Path $package -Leaf)
    }
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'dist\SHA256SUMS.txt'),($checksums -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
}


} finally { Pop-Location }
