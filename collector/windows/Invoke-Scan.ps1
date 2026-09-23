param([Parameter(Mandatory = $true)][string]$LaunchUri)
$ErrorActionPreference = 'Stop'

# Fixed URI grammar and a fixed loopback destination. No command or callback URL is accepted.
$pattern = '\Apcupgradelab://scan/\?id=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})&token=([A-Za-z0-9_-]{43})\z'
if ($LaunchUri -cnotmatch $pattern) {
    Write-Host 'Invalid PC Upgrade Lab launch request.'
    exit 1
}
$scanId = $Matches[1]
$scanToken = $Matches[2]
$endpoint = "http://127.0.0.1:8080/api/scan-sessions/$scanId"
$headers = @{ Authorization = "Bearer $scanToken" }
$started = $false

function Send-ScanJson([string]$Suffix, $Payload) {
    $json = ConvertTo-Json -InputObject $Payload -Depth 12 -Compress
    # Explicit UTF-8 keeps Korean Windows model names intact on PowerShell 5.1.
    Invoke-RestMethod -Method Post -Uri "$endpoint/$Suffix" -Headers $headers `
        -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($json)) `
        -TimeoutSec 10 -MaximumRedirection 0 | Out-Null
}

try {
    if ($env:OS -ne 'Windows_NT') { throw 'Windows is required.' }
    Write-Host 'PC Upgrade Lab: reading hardware model information...'
    # Claim the single-use session before collecting hardware information.
    Send-ScanJson 'start' @{}
    $started = $true
    Import-Module (Join-Path $PSScriptRoot 'PcInventory.psm1') -Force
    $result = Get-PcInventory
    Send-ScanJson 'result' $result
    Write-Host 'Result sent. Return to your browser.'
} catch {
    if ($started) {
        try { Send-ScanJson 'failure' @{ code = 'COLLECTOR_FAILED'; message = 'Collection or delivery failed. Please start a new scan.' } }
        catch { } # Browser deadline still reports failure when the server cannot be reached.
    }
    Write-Host 'Scan failed. Check the local backend and start a new scan in your browser.'
    # Do not print the request URL, token or raw exception.
    exit 1
}
