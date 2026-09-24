# 브라우저의 pcupgradelab:// 링크로 실행되는 진입점.
# URI 확인 → 서버에 시작 등록 → PcInventory로 수집 → 결과 전송 순서로 처리한다.
param([Parameter(Mandatory = $true)][string]$LaunchUri)
$ErrorActionPreference = 'Stop'

# 정해진 URI 형식의 검사 ID·토큰만 읽는다. 외부 명령이나 전송 주소를 인자로 받지 않는다.
$pattern = '\Apcupgradelab://scan/\?id=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})&token=([A-Za-z0-9_-]{43})\z'
if ($LaunchUri -cnotmatch $pattern) {
    Write-Host 'Invalid PC Upgrade Lab launch request.'
    exit 1
}
$scanId = $Matches[1]
$scanToken = $Matches[2]
# 이번 개발 단계는 같은 PC의 백엔드(8080)에만 전송한다. 서버 포트와 맞춰야 한다.
$endpoint = "http://127.0.0.1:8080/api/scan-sessions/$scanId"
$headers = @{ Authorization = "Bearer $scanToken" }
$started = $false

function Send-ScanJson([string]$Suffix, $Payload) {
    $json = ConvertTo-Json -InputObject $Payload -Depth 12 -Compress
    # PowerShell 5.1에서도 한글 모델명이 깨지지 않도록 JSON을 UTF-8 바이트로 전송한다.
    # 리다이렉트를 따라 다른 주소로 토큰을 전달하지 않도록 제한한다.
    Invoke-RestMethod -Method Post -Uri "$endpoint/$Suffix" -Headers $headers `
        -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($json)) `
        -TimeoutSec 10 -MaximumRedirection 0 | Out-Null
}

try {
    if ($env:OS -ne 'Windows_NT') { throw 'Windows is required.' }
    Write-Host 'PC Upgrade Lab: reading hardware model information...'
    # 먼저 시작을 등록한다. 중복 실행이나 만료된 요청이면 실제 하드웨어 조회 전에 거절된다.
    Send-ScanJson 'start' @{}
    $started = $true
    Import-Module (Join-Path $PSScriptRoot 'PcInventory.psm1') -Force
    $result = Get-PcInventory
    Send-ScanJson 'result' $result
    Write-Host 'Result sent. Return to your browser.'
} catch {
    if ($started) {
        try { Send-ScanJson 'failure' @{ code = 'COLLECTOR_FAILED'; message = 'Collection or delivery failed. Please start a new scan.' } }
        catch { } # 실패 보고도 전송할 수 없으면 브라우저의 조회 오류 또는 만료 안내로 확인하게 된다.
    }
    Write-Host 'Scan failed. Check the local backend and start a new scan in your browser.'
    # 요청 URL·토큰·원본 예외를 화면이나 로그에 출력하지 않는다.
    exit 1
}
