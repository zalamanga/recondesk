[CmdletBinding()]
param([switch]$NoBuild)
. (Join-Path $PSScriptRoot 'Common.ps1')
& (Join-Path $PSScriptRoot 'Init.ps1')
$reconValues = Get-ReconEnvironment
if ($reconValues['RECON_APP_ORIGIN'] -ne 'http://127.0.0.1:8096') { throw 'Compose lokal ini memakai origin http://127.0.0.1:8096. Sesuaikan port dan origin bersama bila mengubah deployment.' }
Push-Location $script:ReconRoot
try {
    if (-not $NoBuild) {
        Invoke-ReconDocker -Arguments @('compose','build','api')
        Invoke-ReconDocker -Arguments @('compose','build','web')
    }
    $reconDockerArguments = @('compose','up','--no-build','-d','--wait','--wait-timeout','180')
    Invoke-ReconDocker -Arguments $reconDockerArguments
    Write-Output 'ReconDesk: http://127.0.0.1:8096'
    Write-Output 'Akun lokal: .local/LOCAL-CREDENTIALS.txt'
} finally { Pop-Location }
