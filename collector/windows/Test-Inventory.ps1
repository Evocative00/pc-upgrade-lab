# 실제 장치 대신 고정된 가짜 데이터(fixture)를 넣어 수집 결과의 변환 규칙을 확인한다.
# 이 검사가 통과해도 실제 Windows의 CIM 조회나 브라우저 링크 실행까지 확인한 것은 아니다.
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'PcInventory.psm1') -Force
$checks = 0
function Assert($Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:checks++
}

# 같은 이름의 RAM 2개, 서로 다른 저장장치 2개, 큰 용량과 누락값을 의도적으로 포함한다.
$fixture = {
    param($ClassName)
    switch ($ClassName) {
        'Win32_Processor' { [pscustomobject]@{Name='Fixture CPU';Manufacturer='Fixture';NumberOfCores=8;NumberOfLogicalProcessors=16;MaxClockSpeed=4000;ProcessorId='DO_NOT_EMIT'} }
        'Win32_VideoController' { [pscustomobject]@{Name='Fixture GPU';AdapterRAM=4294967295;DriverVersion='1.0'} }
        'Win32_PhysicalMemory' {
            [pscustomobject]@{PartNumber='RAM-A';Manufacturer='Fixture';DeviceLocator='DIMM A';Capacity=17179869184;Speed=0;SerialNumber='DO_NOT_EMIT'}
            [pscustomobject]@{PartNumber='RAM-A';Manufacturer='Fixture';DeviceLocator='DIMM B';Capacity=17179869184}
        }
        'Win32_BaseBoard' { [pscustomobject]@{Product='Fixture Board';Manufacturer='Fixture'} }
        'Win32_DiskDrive' {
            [pscustomobject]@{Model='Disk A';Size=1000000000000;InterfaceType='SCSI'}
            [pscustomobject]@{Model='Disk B';Size=2000000000000;InterfaceType='SCSI'}
        }
    }
}
$result = Get-PcInventory -Query $fixture
Assert (@($result.parts | Where-Object type -eq 'RAM').Count -eq 2) 'RAM devices were collapsed.'
Assert (@($result.parts | Where-Object type -eq 'STORAGE').Count -eq 2) 'Storage devices were collapsed.'
$ram = @($result.parts | Where-Object type -eq 'RAM')
Assert ($ram[0].specs.slot -ne $ram[1].specs.slot) 'RAM slot identity was lost.'
Assert ($ram[0].specs.capacityBytes -eq 17179869184) 'Capacity or byte units changed.'
Assert ($null -eq $ram[0].specs.reportedSpeedMHz) 'Unknown speed was stored as zero.'
$gpu = @($result.parts | Where-Object type -eq 'GPU')[0]
Assert ($null -eq $gpu.specs.vramBytes) 'Unreliable AdapterRAM was used.'
$json = ConvertTo-Json -InputObject $result -Depth 12
Assert ($json -notmatch 'DO_NOT_EMIT|ProcessorId|SerialNumber') 'A hardware identifier leaked.'
Assert (@($result.parts | Where-Object quantity -ne 1).Count -eq 0) 'Quantity must count individual devices.'

# 일부 조회 실패와 전체 빈 결과를 따로 확인한다. 읽은 항목이 사라지거나 경고가 누락되지 않아야 한다.
$partial = Get-PcInventory -Query {
    param($ClassName)
    if ($ClassName -eq 'Win32_Processor') { throw 'Fixture query failure' }
    if ($ClassName -eq 'Win32_VideoController') { [pscustomobject]@{Name='Working GPU'} }
}
Assert (@($partial.parts | Where-Object type -eq 'GPU').Count -eq 1) 'A failed CPU query discarded the GPU.'
Assert (@($partial.warnings | Where-Object code -eq 'CIM_QUERY_FAILED').Count -eq 1) 'Failure reason was lost.'
Assert ($partial.parts -is [array]) 'Single-device output must remain an array.'
$empty = Get-PcInventory -Query { param($ClassName) @() }
Assert ($empty.parts.Count -eq 0) 'Empty inventory should be empty.'
Assert ($empty.warnings.Count -eq 5) 'Each empty category must be reported.'
Write-Host "PASS: $checks collector fixture assertions. Real Windows CIM and URI activation still require Windows."
