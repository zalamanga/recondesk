[CmdletBinding()]
param([switch]$WithBrowser)
. (Join-Path $PSScriptRoot 'Common.ps1')
& (Join-Path $PSScriptRoot 'Init.ps1')
$reconValues = Get-ReconEnvironment
if (Get-NetTCPConnection -State Listen -LocalPort 5447 -ErrorAction SilentlyContinue) { throw 'Port test 5447 sudah dipakai. Test tidak akan memakai atau menghapus database yang sudah berjalan.' }
$reconEnvironmentNames = @('RECON_TEST_DATABASE_URL','RECON_TEST_DB_USER','RECON_TEST_DB_PASSWORD','RECON_DEMO_PASSWORD','RECON_BASE_URL')
$reconPreviousValues = @{}
foreach ($reconName in $reconEnvironmentNames) { $reconPreviousValues[$reconName] = [Environment]::GetEnvironmentVariable($reconName, 'Process') }
$reconComposeArguments = @('compose','-f','compose.test.yml','-p','recondesk-test')
Push-Location $script:ReconRoot
$reconTestDatabaseStarted = $false
try {
    $env:RECON_TEST_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:5447/recondesk_test'
    $env:RECON_TEST_DB_USER = 'recondesk_test'
    $env:RECON_TEST_DB_PASSWORD = $reconValues['RECON_TEST_DB_PASSWORD']
    $reconTestDatabaseStarted = $true
    Invoke-ReconDocker -Arguments ($reconComposeArguments + @('up','-d','--wait','--wait-timeout','90'))
    & (Join-Path $PSScriptRoot 'Maven.ps1') -Arguments @('verify')
    Push-Location (Join-Path $script:ReconRoot 'frontend')
    try {
        if (-not (Test-Path -LiteralPath 'node_modules/.package-lock.json')) { & npm.cmd ci --ignore-scripts; if ($LASTEXITCODE -ne 0) { throw 'Instalasi frontend gagal.' } }
        & npm.cmd run test
        if ($LASTEXITCODE -ne 0) { throw 'Test frontend gagal.' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Build frontend gagal.' }
        if ($WithBrowser) {
            $env:RECON_BASE_URL = 'http://127.0.0.1:8096'
            $env:RECON_DEMO_PASSWORD = $reconValues['RECON_DEMO_PASSWORD']
            & npm.cmd run test:e2e
            if ($LASTEXITCODE -ne 0) { throw 'Test browser gagal.' }
        }
    } finally { Pop-Location }
} finally {
    try {
        if ($reconTestDatabaseStarted) { Invoke-ReconDocker -Arguments ($reconComposeArguments + @('down')) }
    } finally {
        foreach ($reconName in $reconEnvironmentNames) { [Environment]::SetEnvironmentVariable($reconName, $reconPreviousValues[$reconName], 'Process') }
        Pop-Location
    }
}
Write-Output 'Backend, PostgreSQL, dan frontend selesai diperiksa.'
