[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'Common.ps1')
& (Join-Path $PSScriptRoot 'Init.ps1')
if (Get-NetTCPConnection -State Listen -LocalPort 5447 -ErrorAction SilentlyContinue) { throw 'Port 5447 sedang dipakai. Benchmark hanya memakai database sementaranya sendiri.' }
$reconCompose = @('compose','-f','compose.test.yml','-p','recondesk-benchmark')
Push-Location $script:ReconRoot
$reconStarted = $false
try {
    $reconStarted = $true
    Invoke-ReconDocker -Arguments ($reconCompose + @('up','-d','--wait','--wait-timeout','90'))
    $reconSql = Get-Content -LiteralPath (Join-Path $script:ReconRoot 'benchmarks/query-plan.sql') -Raw -Encoding UTF8
    $reconPriorPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $reconRawLines = @($reconSql | & docker @reconCompose 'exec' '-T' 'db' 'psql' '-X' '-qAt' '-v' 'ON_ERROR_STOP=1' '-U' 'recondesk_test' '-d' 'recondesk_test')
        if ($LASTEXITCODE -ne 0) { throw 'SQL benchmark gagal.' }
    } finally { $ErrorActionPreference = $reconPriorPreference }
    $reconRaw = $reconRawLines -join "`n"
    function Get-ReconSegment([string]$Name) {
        $reconStartTag = 'BEGIN_' + $Name
        $reconEndTag = 'END_' + $Name
        $reconStartIndex = $reconRaw.IndexOf($reconStartTag)
        $reconEndIndex = $reconRaw.IndexOf($reconEndTag)
        if ($reconStartIndex -lt 0 -or $reconEndIndex -le $reconStartIndex) { throw 'Output benchmark tidak lengkap.' }
        return $reconRaw.Substring($reconStartIndex + $reconStartTag.Length, $reconEndIndex - $reconStartIndex - $reconStartTag.Length).Trim()
    }
    $reconBefore = Get-ReconSegment 'PLAN_BEFORE'
    $reconAfter = Get-ReconSegment 'PLAN_AFTER'
    $reconResultBefore = Get-ReconSegment 'RESULT_BEFORE'
    $reconResultAfter = Get-ReconSegment 'RESULT_AFTER'
    if ($reconResultBefore -ne $reconResultAfter) { throw 'Hasil query berubah setelah penambahan indeks.' }
    $reconEnvironment = (Get-ReconSegment 'ENVIRONMENT') | ConvertFrom-Json
    $reconPlanBefore = @($reconBefore | ConvertFrom-Json)
    $reconPlanAfter = @($reconAfter | ConvertFrom-Json)
    $reconOutput = Join-Path $script:ReconRoot 'docs/benchmarks'
    New-Item -ItemType Directory -Path $reconOutput -Force | Out-Null
    $reconUtf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path $reconOutput 'before.json'), $reconBefore + "`n", $reconUtf8)
    [System.IO.File]::WriteAllText((Join-Path $reconOutput 'after.json'), $reconAfter + "`n", $reconUtf8)
    [System.IO.File]::WriteAllText((Join-Path $reconOutput 'result.json'), $reconResultAfter + "`n", $reconUtf8)
    $reconMeasuredAt = (Get-Date).ToString('o')
    $reconSummary = @(
        '# Local SQL plan measurement'
        ''
        ('Measured at: ' + $reconMeasuredAt)
        ('PostgreSQL: ' + $reconEnvironment.serverVersion)
        ('Synthetic input rows: ' + $reconEnvironment.rows)
        ''
        '| Measurement | Before index | After index |'
        '| --- | ---: | ---: |'
        ('| Execution time (ms) | ' + $reconPlanBefore[0].'Execution Time' + ' | ' + $reconPlanAfter[0].'Execution Time' + ' |')
        ('| Planning time (ms) | ' + $reconPlanBefore[0].'Planning Time' + ' | ' + $reconPlanAfter[0].'Planning Time' + ' |')
        ''
        'The same query groups daily amounts and calculates a running total with a window function. The only schema difference is the composite account/date index including amount. The complete result was compared byte-for-byte and is identical.'
        ''
        'This is one paired local measurement, with a result read before each EXPLAIN to warm data. It is not a production throughput benchmark or a universal speedup claim. PostgreSQL estimates, cache state, hardware and concurrent work affect timings. Read the complete plans and buffer counters in before.json and after.json.'
        ''
        'The table is temporary, contains generated synthetic data and is rolled back. The script creates a separate temporary PostgreSQL instance and never connects to the application database.'
        ''
        'Reproduce on Windows: .\scripts\Benchmark.ps1 (port 5447 must be free). Generated results replace this evidence with the new local measurement.'
        ''
        'Reference: [PostgreSQL EXPLAIN](https://www.postgresql.org/docs/17/using-explain.html).'
    )
    [System.IO.File]::WriteAllLines((Join-Path $reconOutput 'README.md'), $reconSummary, $reconUtf8)
    Write-Output ('Benchmark selesai; hasil query sama. Bukti: ' + $reconOutput)
} finally {
    try {
        if ($reconStarted) { Invoke-ReconDocker -Arguments ($reconCompose + @('down')) }
    } finally { Pop-Location }
}
