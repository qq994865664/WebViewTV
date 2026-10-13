param([string]$Sdk=$env:ANDROID_HOME,[string]$Jdk=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
if ([string]::IsNullOrWhiteSpace($Sdk)) { $Sdk='D:\FangteToolchains\android-sdk' }
if ([string]::IsNullOrWhiteSpace($Jdk)) { $Jdk='D:\FangteToolchains\jdk17\jdk-17.0.20.1+1' }
Push-Location $PSScriptRoot
try {
$bt=Join-Path $Sdk 'build-tools\36.0.0'; $android=Join-Path $Sdk 'platforms\android-36\android.jar'; $env:JAVA_HOME=$Jdk; $env:PATH="$Jdk\bin;$env:PATH"
function Run([string]$exe,[string[]]$arguments){ & $exe @arguments; if($LASTEXITCODE -ne 0){throw "$exe failed: $LASTEXITCODE"} }
New-Item -ItemType Directory -Force build\classes,build\dex,dist | Out-Null
if(!(Test-Path app\src\main\assets\china_mainland.json)){throw 'Bundled channel configuration is missing.'}
Run "$bt\aapt2.exe" @('link','-o','build\unsigned.apk','-I',$android,'--manifest','app\src\main\AndroidManifest.xml','--min-sdk-version','26','--target-sdk-version','35')
# Windows aapt2 -A writes backslashes for nested assets. jar writes Android ZIP paths with '/'.
Run "$Jdk\bin\jar.exe" @('uf','build\unsigned.apk','-C','app\src\main','assets')
& (Join-Path $PSScriptRoot 'verify-assets.ps1') -Apk 'build\unsigned.apk'
$javaFiles=@(Get-ChildItem app\src\main\java -Filter *.java -Recurse | ForEach-Object FullName)
Run "$Jdk\bin\javac.exe" (@('-encoding','UTF-8','-source','8','-target','8','-classpath',$android,'-d','build\classes')+$javaFiles)
$classFiles=@(Get-ChildItem build\classes -Filter *.class -Recurse | ForEach-Object FullName)
Run "$bt\d8.bat" (@('--lib',$android,'--min-api','26','--output','build\dex')+$classFiles)
Push-Location build\dex
try {Run "$Jdk\bin\jar.exe" @('uf','..\unsigned.apk','classes.dex')} finally {Pop-Location}
Run "$bt\zipalign.exe" @('-f','4','build\unsigned.apk','build\aligned.apk')
if(!(Test-Path build\debug.keystore)){Run "$Jdk\bin\keytool.exe" @('-genkeypair','-keystore','build\debug.keystore','-storepass','android','-keypass','android','-alias','androiddebugkey','-dname','CN=WebViewTV Research,O=Local,C=CN','-keyalg','RSA','-keysize','2048','-validity','3650')}
Run "$bt\apksigner.bat" @('sign','--ks','build\debug.keystore','--ks-pass','pass:android','--key-pass','pass:android','--out','dist\webviewtv-reconstructed-debug.apk','build\aligned.apk')
Run "$bt\apksigner.bat" @('verify','--verbose','dist\webviewtv-reconstructed-debug.apk')
Get-FileHash dist\webviewtv-reconstructed-debug.apk -Algorithm SHA256


} finally { Pop-Location }
