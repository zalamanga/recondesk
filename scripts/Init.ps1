[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'Common.ps1')
$reconEnvPath = Join-Path $script:ReconRoot '.env'
$reconPrivateDirectory = Join-Path $script:ReconRoot '.local'
New-Item -ItemType Directory -Path $reconPrivateDirectory -Force | Out-Null

function New-ReconSecret {
    $reconBytes = New-Object byte[] 24
    $reconRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $reconRandom.GetBytes($reconBytes) } finally { $reconRandom.Dispose() }
    return ([BitConverter]::ToString($reconBytes)).Replace('-','').ToLowerInvariant()
}

if (-not (Test-Path -LiteralPath $reconEnvPath)) {
    $reconLines = @(
        '# Private local values. Do not commit or share this file.'
        ('RECON_DB_PASSWORD=' + (New-ReconSecret))
        ('RECON_DEMO_PASSWORD=' + (New-ReconSecret))
        ('RECON_TEST_DB_PASSWORD=' + (New-ReconSecret))
        'RECON_APP_ORIGIN=http://127.0.0.1:8096'
    )
    [System.IO.File]::WriteAllLines($reconEnvPath, $reconLines, (New-Object System.Text.UTF8Encoding($false)))
}
$reconValues = Get-ReconEnvironment
$reconCredentialPath = Join-Path $reconPrivateDirectory 'LOCAL-CREDENTIALS.txt'
if (-not (Test-Path -LiteralPath $reconCredentialPath)) {
    $reconCredentials = @(
        'ReconDesk - akun demo lokal sintetis'
        ('URL: ' + $reconValues['RECON_APP_ORIGIN'])
        'Operator: operator@recondesk.test'
        'Reviewer: reviewer@recondesk.test'
        ('Password awal untuk kedua akun: ' + $reconValues['RECON_DEMO_PASSWORD'])
        ''
        'Akun dibuat hanya jika tabel akun kosong. Init tidak mengubah akun atau data lama.'
        'Jangan masukkan file ini ke GitHub, screenshot, atau pesan publik.'
    )
    [System.IO.File]::WriteAllLines($reconCredentialPath, $reconCredentials, (New-Object System.Text.UTF8Encoding($false)))
}
Write-Output ('Konfigurasi siap. Baca akun lokal di: ' + $reconCredentialPath)
