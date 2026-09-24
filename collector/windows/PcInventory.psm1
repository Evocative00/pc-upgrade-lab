# Windows의 CIM(시스템 관리 정보 조회 기능)에서 부품 이름과 제원을 읽어 공통 JSON 형식의 객체를 만든다.
# 이 모듈은 조회·변환만 담당한다. 서버로 전송하는 코드는 Invoke-Scan.ps1에 있다.
# 같은 종류가 여러 개면 각각 보존하며, 일부 종류를 읽지 못해도 나머지 결과와 경고를 반환한다.
function Get-PcInventory {
    [CmdletBinding()]
    param(
        # 테스트에서는 실제 Windows 조회 대신 가짜 데이터를 반환하는 함수를 넣을 수 있다.
        # 프로그램 실행 URI로는 코드를 전달받지 않는다.
        [scriptblock]$Query = {
            param($ClassName)
            Get-CimInstance -ClassName $ClassName -OperationTimeoutSec 12 -ErrorAction Stop
        }
    )
    $parts = New-Object 'System.Collections.Generic.List[object]'
    $warnings = New-Object 'System.Collections.Generic.List[object]'

    # 장치·드라이버에 따라 속성이 없을 수 있다. 없는 속성과 빈 문자열은 null로 다룬다.
    function Field($Row, [string]$Name) {
        if ($null -eq $Row) { return $null }
        $property = $Row.PSObject.Properties[$Name]
        if ($null -eq $property) { return $null }
        return $property.Value
    }
    function Text($Value) {
        if ($null -eq $Value -or [string]::IsNullOrWhiteSpace([string]$Value)) { return $null }
        return ([string]$Value).Trim()
    }
    # 용량은 32비트 범위를 넘을 수 있어 long(64비트 정수)으로 보존한다. 0·음수·누락값은 미확인 값이다.
    function Positive($Value) {
        if ($null -ne $Value -and [long]$Value -gt 0) { return [long]$Value }
        return $null
    }
    function Warn([string]$Scope, [string]$Code, [string]$Message) {
        $warnings.Add([ordered]@{ scope = $Scope; code = $Code; message = $Message })
    }
    # 종류별로 실패를 처리한다. CPU 조회가 실패해도 GPU·RAM 등 다음 조회는 계속한다.
    function Rows([string]$ClassName, [string]$Scope) {
        try {
            $rows = @(& $Query $ClassName)
            if ($rows.Count -eq 0) { Warn $Scope 'NO_DEVICE' "No $Scope device was returned by Windows." }
            return $rows
        } catch {
            Warn $Scope 'CIM_QUERY_FAILED' "Windows could not read $Scope. You can enter it manually."
            return @()
        }
    }
    # 수집기에서 만든 항목은 항상 AUTO/UNMATCHED다. 장치 이름만으로 카탈로그의 판매 제품을 확정하지 않는다.
    function Part([string]$Type, $Name, $RawName, $Specs) {
        $display = Text $Name
        if ($null -eq $display) {
            $display = "$Type (model unknown)"
            Warn $Type 'MODEL_UNKNOWN' "$Type model name was not available."
        }
        if ($display.Length -gt 255) { $display = $display.Substring(0, 255) }
        $raw = Text $RawName
        if ($null -ne $raw -and $raw.Length -gt 500) { $raw = $raw.Substring(0, 500) }
        $parts.Add([ordered]@{
            type = $Type; displayName = $display; rawName = $raw; quantity = 1
            source = 'AUTO'; catalogProductId = $null; matchStatus = 'UNMATCHED'; specs = $Specs
        })
    }

    foreach ($row in @(Rows 'Win32_Processor' 'CPU')) {
        Part 'CPU' (Field $row 'Name') (Field $row 'Name') ([ordered]@{
            manufacturer = Text (Field $row 'Manufacturer')
            cores = Positive (Field $row 'NumberOfCores')
            logicalProcessors = Positive (Field $row 'NumberOfLogicalProcessors')
            maxClockMHz = Positive (Field $row 'MaxClockSpeed')
        })
    }
    foreach ($row in @(Rows 'Win32_VideoController' 'GPU')) {
        Part 'GPU' (Field $row 'Name') (Field $row 'Name') ([ordered]@{
            manufacturer = Text (Field $row 'AdapterCompatibility')
            driverVersion = Text (Field $row 'DriverVersion')
            # AdapterRAM은 32비트 값이라 큰 VRAM 용량을 신뢰하기 어렵다. 추측하지 않고 미확인으로 전달한다.
            vramBytes = $null
        })
        Warn 'GPU' 'VRAM_UNAVAILABLE' 'VRAM capacity was not determined by this collector.'
    }
    # 같은 PartNumber라도 Windows가 보고한 위치별 항목을 합치지 않는다. 각 항목의 quantity는 1이다.
    # 노트북의 보고 항목 수를 실제로 교체 가능한 RAM 모듈이나 슬롯 수로 해석해서는 안 된다.
    foreach ($row in @(Rows 'Win32_PhysicalMemory' 'RAM')) {
        $model = Text (Field $row 'PartNumber')
        $manufacturer = Text (Field $row 'Manufacturer')
        $slot = Text (Field $row 'DeviceLocator')
        $display = $model
        if ($null -eq $display) {
            $display = "$manufacturer RAM ($slot)".Trim()
            Warn 'RAM' 'MODEL_UNKNOWN' 'RAM part number was unavailable. Check the module model manually.'
        }
        $capacity = Positive (Field $row 'Capacity')
        if ($null -eq $capacity) { Warn 'RAM' 'CAPACITY_UNKNOWN' 'RAM capacity could not be determined.' }
        Part 'RAM' $display $model ([ordered]@{
            manufacturer = $manufacturer; partNumber = $model; slot = $slot
            capacityBytes = $capacity
            # Windows가 보고한 값을 보존한다. 임의로 배수를 곱해 유효 전송률(MT/s)을 만들어 내지 않는다.
            reportedSpeedMHz = Positive (Field $row 'Speed')
            configuredClockMHz = Positive (Field $row 'ConfiguredClockSpeed')
            smbiosMemoryType = Positive (Field $row 'SMBIOSMemoryType')
        })
    }
    foreach ($row in @(Rows 'Win32_BaseBoard' 'MOTHERBOARD')) {
        $manufacturer = Text (Field $row 'Manufacturer')
        $model = Text (Field $row 'Product')
        Part 'MOTHERBOARD' $model $model ([ordered]@{
            manufacturer = $manufacturer; model = $model
        })
    }
    # 저장장치도 여러 개면 각각 보존한다. 보고된 SCSI 등의 인터페이스명만으로 SATA/NVMe를 단정하지 않는다.
    foreach ($row in @(Rows 'Win32_DiskDrive' 'STORAGE')) {
        Part 'STORAGE' (Field $row 'Model') (Field $row 'Model') ([ordered]@{
            capacityBytes = Positive (Field $row 'Size')
            interfaceReported = Text (Field $row 'InterfaceType')
            mediaTypeReported = Text (Field $row 'MediaType')
        })
    }
    # 일련번호·ProcessorId·MAC·사용자 이름·컴퓨터 이름은 반환 항목에 넣지 않는다.
    # 항목이 0개나 1개여도 parts/warnings는 배열로 유지해 서버·프런트에서 동일하게 처리할 수 있게 한다.
    return [ordered]@{
        schemaVersion = 1; collectorVersion = '0.1.0'
        collectedAt = [DateTime]::UtcNow.ToString('o')
        parts = @($parts.ToArray()); warnings = @($warnings.ToArray())
    }
}

Export-ModuleMember -Function Get-PcInventory
