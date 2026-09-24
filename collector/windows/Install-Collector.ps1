# 현재 Windows 사용자에게 수집기를 설치하고 pcupgradelab:// 링크의 실행 규칙을 등록한다.
# 프로젝트의 두 실행 파일을 별도 폴더에 복사하므로, 수집기 수정 후에는 이 설치 스크립트를 다시 실행한다.
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Run this installer on Windows.' }
$target = Join-Path $env:LOCALAPPDATA 'PcUpgradeLab\Collector'
# HKCU는 현재 사용자의 레지스트리다. 전체 사용자용 설정이나 관리자 권한이 필요하지 않다.
$registry = 'HKCU:\Software\Classes\pcupgradelab'
$powerShellExe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'

# 다른 프로그램이 같은 링크 규칙을 사용하고 있으면 덮어쓰지 않는다.
if (Test-Path $registry) {
    $existing = (Get-Item $registry).GetValue('PCULOwner')
    if ($existing -ne 'PcUpgradeLabCollector') { throw 'The pcupgradelab scheme is already owned by another application.' }
}
New-Item -ItemType Directory -Force $target | Out-Null
foreach ($name in @('PcInventory.psm1', 'Invoke-Scan.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $target $name) -Force
}
$handler = Join-Path $target 'Invoke-Scan.ps1'
# 브라우저가 넘긴 URI(%1)는 실행 스크립트의 인자다. 실행 정책은 이 프로세스에 지정하며 전역 설정을 변경하지 않는다.
$command = '"{0}" -NoProfile -ExecutionPolicy RemoteSigned -File "{1}" -LaunchUri "%1"' -f $powerShellExe, $handler
New-Item -Path "$registry\shell\open\command" -Force | Out-Null
Set-Item -Path $registry -Value 'URL:PC Upgrade Lab Collector'
New-ItemProperty -Path $registry -Name 'URL Protocol' -Value '' -PropertyType String -Force | Out-Null
New-ItemProperty -Path $registry -Name 'PCULOwner' -Value 'PcUpgradeLabCollector' -PropertyType String -Force | Out-Null
Set-Item -Path "$registry\shell\open\command" -Value $command
Write-Host 'Installed for this Windows user. No administrator privileges are required.'
Write-Host 'Start the local backend and frontend, then use the scan button in your browser.'
