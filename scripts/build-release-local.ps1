param(
    [string]$SigningDirectory=(Join-Path (Split-Path $PSScriptRoot -Parent) '..\signing'),
    [string]$Sdk='D:\FangteToolchains\android-sdk',
    [string]$Jdk='D:\FangteToolchains\jdk17\jdk-17.0.20.1+1'
)
$ErrorActionPreference='Stop'
$credentialFile=Join-Path $SigningDirectory 'release-password.clixml'
$credential=Import-Clixml -LiteralPath $credentialFile
$previousPassword=$env:WVT_RELEASE_STORE_PASSWORD
try {
    $env:WVT_RELEASE_STORE_PASSWORD=$credential.GetNetworkCredential().Password
    & (Join-Path $PSScriptRoot '..\build.ps1') -Release -Sdk $Sdk -Jdk $Jdk `
        -KeyStore (Join-Path $SigningDirectory 'webviewtv-release.keystore') `
        -Lineage (Join-Path $SigningDirectory 'webviewtv-signing-lineage.bin')
} finally {
    $env:WVT_RELEASE_STORE_PASSWORD=$previousPassword
    $credential=$null
}
