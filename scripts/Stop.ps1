[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'Common.ps1')
Push-Location $script:ReconRoot
try { Invoke-ReconDocker -Arguments @('compose','down') } finally { Pop-Location }
Write-Output 'ReconDesk dihentikan. Volume database tetap tersimpan.'
