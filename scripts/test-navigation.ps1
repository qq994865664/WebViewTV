param(
    [string]$Sdk='D:\FangteToolchains\android-sdk',
    [string]$Jdk='D:\FangteToolchains\jdk17\jdk-17.0.20.1+1',
    [string]$Adb='C:\Program Files\Netease\MuMu\nx_device\15.0\shell\adb.exe',
    [string]$Serial='emulator-5554',
    [ValidateSet('Navigation','Fallback','Information','Playback')][string]$Suite='Navigation'
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
# A fresh build directory prevents stale inner classes from affecting regressions.
$testBuild=Join-Path $repo ('build\navigation-test-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $testBuild,(Join-Path $testBuild 'classes'),(Join-Path $testBuild 'dex') | Out-Null
$bt=Join-Path $Sdk 'build-tools\36.0.0'
$android=Join-Path $Sdk 'platforms\android-36\android.jar'
$env:JAVA_HOME=$Jdk
function Run([string]$exe,[string[]]$arguments) { & $exe @arguments; if($LASTEXITCODE -ne 0){throw "$exe failed: $LASTEXITCODE"} }
Run "$bt\aapt2.exe" @('link','-o',"$testBuild\unsigned.apk",'-I',$android,'--manifest',"$repo\tests\navigation\AndroidManifest.xml")
$sources=@(Get-ChildItem "$repo\app\src\main\java" -Recurse -Filter '*.java' | ForEach-Object FullName)+@(Get-ChildItem "$repo\tests\navigation" -Filter '*.java' | ForEach-Object FullName)
Run "$Jdk\bin\jar.exe" @('uf',"$testBuild\unsigned.apk",'-C',"$repo\app\src\main",'assets')
Run "$Jdk\bin\javac.exe" (@('-encoding','UTF-8','-source','8','-target','8','-classpath',$android,'-d',"$testBuild\classes")+$sources)
Run "$Jdk\bin\jar.exe" @('cf',"$testBuild\classes.jar",'-C',"$testBuild\classes",'.')
Run "$bt\d8.bat" @('--lib',$android,'--min-api','28','--output',"$testBuild\dex","$testBuild\classes.jar")
Run "$Jdk\bin\jar.exe" @('uf',"$testBuild\unsigned.apk",'-C',"$testBuild\dex",'classes.dex')
Run "$bt\zipalign.exe" @('-f','4',"$testBuild\unsigned.apk","$testBuild\aligned.apk")
Run "$bt\apksigner.bat" @('sign','--ks',"$repo\build\debug.keystore",'--ks-pass','pass:android','--out',"$testBuild\test.apk","$testBuild\aligned.apk")
Run $Adb @('-s',$Serial,'install','--no-incremental','-r',"$testBuild\test.apk")
$result=& $Adb -s $Serial shell am instrument -w -r "com.vasthread.webviewtv.navigationtest/.${Suite}Test"
$instrumentExit=$LASTEXITCODE
$result | Set-Content -LiteralPath (Join-Path $testBuild 'result.txt') -Encoding utf8
$result
if ($instrumentExit -ne 0 -or ($result -join "`n") -notmatch 'PASS \d+ (navigation|fallback|information|playback) assertions' -or ($result -join "`n") -notmatch 'INSTRUMENTATION_CODE: -1') { throw "$Suite regression failed. Evidence: $testBuild" }
