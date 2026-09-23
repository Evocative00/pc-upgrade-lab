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
# Delete only the two files installed by this collector.
foreach ($name in @('PcInventory.psm1', 'Invoke-Scan.ps1')) {
    $file = Join-Path $target $name
    if (Test-Path $file) { Remove-Item -LiteralPath $file -Force }
}
Write-Host 'PC Upgrade Lab collector was uninstalled.'
