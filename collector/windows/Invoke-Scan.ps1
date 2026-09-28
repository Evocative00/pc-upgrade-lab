# 브라우저의 pcupgradelab:// 링크로 실행되는 진입점.
# URI 확인 → 서버에 시작 등록 → PcInventory로 수집 → 결과 전송 순서로 처리한다.
param(
    [Parameter(Mandatory = $true)][string]$LaunchUri,
    # 브라우저 실행에서는 오류를 읽은 뒤 사용자가 창을 닫는다. 직접 실행·자동 검사는 대기하지 않는다.
    [switch]$PauseOnError
)
$ErrorActionPreference = 'Stop'

function Stop-Scan([string]$Message) {
    Write-Host $Message
    if ($PauseOnError) {
        try { Read-Host '안내를 확인한 뒤 Enter를 눌러 창을 닫으세요' | Out-Null }
        catch { } # 입력할 수 없는 실행 환경에서도 원본 예외·토큰을 출력하지 않는다.
    }
    exit 1
}

# 정해진 URI 형식의 검사 ID·토큰만 읽는다. 외부 명령이나 전송 주소를 인자로 받지 않는다.
$pattern = '\Apcupgradelab://scan/\?id=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})&token=([A-Za-z0-9_-]{43})\z'
if ($LaunchUri -cnotmatch $pattern) {
    Stop-Scan '실행 링크가 올바르지 않습니다. 화면에서 취소한 뒤 새 검사를 시작해 주세요.'
}
$scanId = $Matches[1]
$scanToken = $Matches[2]
# 이번 개발 단계는 같은 PC의 백엔드(8080)에만 전송한다. 서버 포트와 맞춰야 한다.
$endpoint = "http://127.0.0.1:8080/api/scan-sessions/$scanId"
$headers = @{ Authorization = "Bearer $scanToken" }
$started = $false
$stage = 'start'

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
    $stage = 'inventory'
    Import-Module (Join-Path $PSScriptRoot 'PcInventory.psm1') -Force
    $result = Get-PcInventory
    $stage = 'result'
    Send-ScanJson 'result' $result
    Write-Host 'Result sent. Return to your browser.'
} catch {
    # 응답 코드는 원인 안내에만 사용한다. 예외 메시지에는 실행 토큰이 포함될 수 있어 출력하지 않는다.
    $statusCode = 0
    try {
        if ($null -ne $_.Exception.Response) { $statusCode = [int]$_.Exception.Response.StatusCode }
    } catch { }
    if ($started) {
        try { Send-ScanJson 'failure' @{ code = 'COLLECTOR_FAILED'; message = 'Collection or delivery failed. Please start a new scan.' } }
        catch { } # 실패 보고도 전송할 수 없으면 브라우저의 조회 오류 또는 만료 안내로 확인하게 된다.
    }
    if ($stage -eq 'start') {
        if ($statusCode -in @(403, 404, 409, 410)) {
            Stop-Scan '검사 링크가 만료되었거나 이미 사용됐습니다. 이 PC의 백엔드(local 프로필)를 확인하고 화면에서 새 검사를 시작해 주세요.'
        }
        Stop-Scan '이 PC의 백엔드(127.0.0.1:8080)에 검사를 시작하지 못했습니다. 서버를 켜고 Test-CollectorSetup.ps1로 확인해 주세요.'
    }
    if ($stage -eq 'inventory') {
        Stop-Scan '사양 수집 모듈을 실행하지 못했습니다. 설치 스크립트를 다시 실행하고 Test-CollectorSetup.ps1로 확인해 주세요.'
    }
    Stop-Scan '사양 결과를 서버에 보내지 못했습니다. 서버 상태를 확인하고 화면에서 새 검사를 시작해 주세요.'
}
