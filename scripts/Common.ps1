$ErrorActionPreference = 'Stop'
$script:ReconRoot = Split-Path -Parent $PSScriptRoot

function Invoke-ReconDocker {
    param([Parameter(Mandatory=$true)][string[]]$Arguments)
    $reconPreviousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & docker @Arguments
        if ($LASTEXITCODE -ne 0) { throw 'Perintah Docker gagal. Periksa keluaran di atas.' }
    } finally { $ErrorActionPreference = $reconPreviousPreference }
}

function Get-ReconEnvironment {
    $reconPath = Join-Path $script:ReconRoot '.env'
    if (-not (Test-Path -LiteralPath $reconPath)) { throw 'Jalankan scripts/Init.ps1 terlebih dahulu.' }
    $reconValues = @{}
    foreach ($reconLine in [System.IO.File]::ReadAllLines($reconPath)) {
        if ($reconLine -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $reconValues[$matches[1]] = $matches[2] }
    }
    foreach ($reconKey in @('RECON_DB_PASSWORD','RECON_DEMO_PASSWORD','RECON_TEST_DB_PASSWORD')) {
        if (-not $reconValues[$reconKey] -or $reconValues[$reconKey].Length -lt 16) { throw 'Konfigurasi lokal belum lengkap; periksa file .env privat.' }
    }
    return $reconValues
}
