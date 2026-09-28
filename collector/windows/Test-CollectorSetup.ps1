# 설치 설정을 바꾸지 않고 파일·실행 규칙·서버를 확인한다. 마지막 단계에서 빈 임시 검사 하나를 생성한다.
# 임시 검사는 2분 후 만료되며, 이 도구 자체는 하드웨어를 수집하거나 PC 구성을 저장하지 않는다.
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Run this diagnostic on Windows.' }
$failed = 0
function Check([bool]$Ok, [string]$Message) {
    if ($Ok) { Write-Host "[PASS] $Message" }
    else { Write-Host "[FAIL] $Message"; $script:failed++ }
}

$target = Join-Path $env:LOCALAPPDATA 'PcUpgradeLab\Collector'
$registry = 'HKCU:\Software\Classes\pcupgradelab'
$powerShellExe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$handler = Join-Path $target 'Invoke-Scan.ps1'
$expectedCommand = '"{0}" -NoProfile -ExecutionPolicy RemoteSigned -File "{1}" -LaunchUri "%1" -PauseOnError' -f $powerShellExe, $handler
Write-Host 'PC Upgrade Lab: 현재 PC / 현재 Windows 사용자 설치 진단'
Check (Test-Path -LiteralPath $powerShellExe -PathType Leaf) 'Windows PowerShell 실행 파일'

foreach ($name in @('Invoke-Scan.ps1', 'PcInventory.psm1')) {
    $installed = Join-Path $target $name
    $source = Join-Path $PSScriptRoot $name
    $exists = Test-Path -LiteralPath $installed -PathType Leaf
    Check $exists "설치 파일: $name (없으면 Install-Collector.ps1 실행)"
    if ($exists) {
        if (Test-Path -LiteralPath $source -PathType Leaf) {
            Check ((Get-FileHash -LiteralPath $source).Hash -eq (Get-FileHash -LiteralPath $installed).Hash) "최신 설치본: $name (다르면 다시 설치)"
        }
        $zone = Get-Item -LiteralPath $installed -Stream Zone.Identifier -ErrorAction SilentlyContinue
        if ($null -ne $zone) {
            # 인터넷 출처 표시를 자동 삭제하거나 실행 정책을 변경하지 않는다.
            Check $false "다운로드 차단 표시: $name. 프로젝트 출처와 파일 속성의 차단 상태를 확인해 주세요."
        }
    }
}
if (Test-Path $registry) {
    $key = Get-Item $registry
    Check ($key.GetValue('PCULOwner') -eq 'PcUpgradeLabCollector') '현재 사용자에게 수집기 등록'
    Check ($key.GetValueNames() -contains 'URL Protocol') '프로그램 열기 링크 등록'
    $commandKey = "$registry\shell\open\command"
    $actualCommand = $null
    if (Test-Path $commandKey) { $actualCommand = (Get-Item $commandKey).GetValue('') }
    Check ($actualCommand -ceq $expectedCommand) '실행 경로와 오류 창 유지 설정 (다르면 다시 설치)'
} else {
    Check $false '현재 Windows 사용자에게 설치되지 않았습니다. Install-Collector.ps1을 실행해 주세요.'
}

# Group Policy는 프로세스의 RemoteSigned보다 우선한다. 현재 상태만 표시한다.
$policies = Get-ExecutionPolicy -List
$policies | Format-Table -AutoSize
foreach ($policy in $policies) {
    if ($policy.Scope -in @('MachinePolicy', 'UserPolicy') -and $policy.ExecutionPolicy -in @('Restricted', 'AllSigned')) {
        Check $false '조직 실행 정책으로 제한되어 있습니다. 관리자에게 확인해 주세요.'
    }
}
Check ($null -ne (Get-Command Get-CimInstance -ErrorAction SilentlyContinue)) 'Windows 장치 조회 명령'

try {
    $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/health' -TimeoutSec 5 -MaximumRedirection 0
    Check ($health.status -eq 'UP' -and $health.service -eq 'pc-upgrade-lab') '같은 PC의 백엔드 응답 (8080)'
} catch { Check $false '백엔드 연결 실패. IntelliJ의 local 프로필과 8080 포트를 확인해 주세요.' }
try {
    $prepared = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/scan-sessions' `
        -Headers @{ 'X-PCUL-Client' = 'web' } -TimeoutSec 5 -MaximumRedirection 0
    Check (-not [string]::IsNullOrWhiteSpace($prepared.sessionId)) '검사 준비 API 응답 (local 프로필)'
    # 응답의 launchUri/readToken은 화면·로그로 출력하지 않는다.
    $prepared = $null
} catch { Check $false '검사 준비 실패. 백엔드의 SPRING_PROFILES_ACTIVE=local 설정을 확인해 주세요.' }

if ($failed -gt 0) {
    Write-Host "확인할 항목 $failed 개. 위의 [FAIL] 항목부터 해결해 주세요."
    exit 1
}
Write-Host '설치·서버 점검 통과. 화면에서 새 검사를 시작하고 보조 프로그램 실행을 허용해 주세요.'
Write-Host '브라우저의 프로그램 열기 허용과 실제 사양 수집은 화면에서 별도로 확인해야 합니다.'
