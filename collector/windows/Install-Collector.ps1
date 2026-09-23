$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Run this installer on Windows.' }
$target = Join-Path $env:LOCALAPPDATA 'PcUpgradeLab\Collector'
$registry = 'HKCU:\Software\Classes\pcupgradelab'
$powerShellExe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'

# Refuse to take over a handler installed by a different program.
if (Test-Path $registry) {
    $existing = (Get-Item $registry).GetValue('PCULOwner')
    if ($existing -ne 'PcUpgradeLabCollector') { throw 'The pcupgradelab scheme is already owned by another application.' }
}
New-Item -ItemType Directory -Force $target | Out-Null
foreach ($name in @('PcInventory.psm1', 'Invoke-Scan.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $target $name) -Force
}
$handler = Join-Path $target 'Invoke-Scan.ps1'
$command = '"{0}" -NoProfile -ExecutionPolicy RemoteSigned -File "{1}" -LaunchUri "%1"' -f $powerShellExe, $handler
New-Item -Path "$registry\shell\open\command" -Force | Out-Null
Set-Item -Path $registry -Value 'URL:PC Upgrade Lab Collector'
New-ItemProperty -Path $registry -Name 'URL Protocol' -Value '' -PropertyType String -Force | Out-Null
New-ItemProperty -Path $registry -Name 'PCULOwner' -Value 'PcUpgradeLabCollector' -PropertyType String -Force | Out-Null
Set-Item -Path "$registry\shell\open\command" -Value $command
Write-Host 'Installed for this Windows user. No administrator privileges are required.'
Write-Host 'Start the local backend and frontend, then use the scan button in your browser.'
