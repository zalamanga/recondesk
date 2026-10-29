[CmdletBinding()]
param([string[]]$Arguments = @('verify'))
. (Join-Path $PSScriptRoot 'Common.ps1')
$reconTools = Join-Path $script:ReconRoot '.local/tools'
$reconVersion = '3.9.16'
$reconMaven = Join-Path $reconTools ('apache-maven-' + $reconVersion + '/bin/mvn.cmd')
if (-not (Test-Path -LiteralPath $reconMaven)) {
    New-Item -ItemType Directory -Path $reconTools -Force | Out-Null
    $reconArchive = Join-Path $reconTools ('apache-maven-' + $reconVersion + '-bin.zip')
    $reconUrl = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/' + $reconVersion + '/apache-maven-' + $reconVersion + '-bin.zip'
    $reconExpectedHash = 'ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3'
    Invoke-WebRequest -Uri $reconUrl -OutFile $reconArchive -UseBasicParsing
    if ((Get-FileHash -LiteralPath $reconArchive -Algorithm SHA512).Hash.ToLowerInvariant() -ne $reconExpectedHash) { throw 'Checksum unduhan Maven tidak cocok; arsip tidak dijalankan.' }
    Expand-Archive -LiteralPath $reconArchive -DestinationPath $reconTools -Force
}
$reconRepository = Join-Path $script:ReconRoot '.local/m2'
$reconPriorMavenOptions = $env:MAVEN_OPTS
$env:MAVEN_OPTS = '-Xmx320m -XX:MaxMetaspaceSize=192m'
Push-Location (Join-Path $script:ReconRoot 'backend')
try {
    & $reconMaven '-B' '-ntp' ('-Dmaven.repo.local=' + $reconRepository) @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Maven gagal. Lihat keluaran di atas.' }
} finally { Pop-Location; $env:MAVEN_OPTS = $reconPriorMavenOptions }
