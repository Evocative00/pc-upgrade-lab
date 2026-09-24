# 이 수집기가 등록한 현재 사용자의 실행 규칙과 복사한 파일만 제거한다.
# 프로젝트 소스와 저장된 PC의 MySQL 데이터는 제거 대상이 아니다.
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Run this uninstaller on Windows.' }
$registry = 'HKCU:\Software\Classes\pcupgradelab'
if (Test-Path $registry) {
    if ((Get-Item $registry).GetValue('PCULOwner') -ne 'PcUpgradeLabCollector') {
        throw 'This URI handler is owned by a different application.'
    }
    Remove-Item -LiteralPath $registry -Recurse -Force
}
$target = Join-Path $env:LOCALAPPDATA 'PcUpgradeLab\Collector'
# 설치기가 복사한 두 파일만 지운다. 상위 폴더 전체를 재귀 삭제하지 않는다.
foreach ($name in @('PcInventory.psm1', 'Invoke-Scan.ps1')) {
    $file = Join-Path $target $name
    if (Test-Path $file) { Remove-Item -LiteralPath $file -Force }
}
Write-Host 'PC Upgrade Lab collector was uninstalled.'
