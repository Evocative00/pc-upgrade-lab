function Get-PcInventory {
    [CmdletBinding()]
    param(
        # Injectable for deterministic fixture tests; the URI handler never accepts code.
        [scriptblock]$Query = {
            param($ClassName)
            Get-CimInstance -ClassName $ClassName -OperationTimeoutSec 12 -ErrorAction Stop
        }
    )
    $parts = New-Object 'System.Collections.Generic.List[object]'
    $warnings = New-Object 'System.Collections.Generic.List[object]'

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
    function Positive($Value) {
        if ($null -ne $Value -and [long]$Value -gt 0) { return [long]$Value }
        return $null
    }
    function Warn([string]$Scope, [string]$Code, [string]$Message) {
        $warnings.Add([ordered]@{ scope = $Scope; code = $Code; message = $Message })
    }
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
            # AdapterRAM is uint32 and cannot represent modern VRAM reliably.
            vramBytes = $null
        })
        Warn 'GPU' 'VRAM_UNAVAILABLE' 'VRAM capacity was not determined by this collector.'
    }
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
            # Preserve WMI's reported values; do not invent an effective MT/s value.
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
    foreach ($row in @(Rows 'Win32_DiskDrive' 'STORAGE')) {
        Part 'STORAGE' (Field $row 'Model') (Field $row 'Model') ([ordered]@{
            capacityBytes = Positive (Field $row 'Size')
            interfaceReported = Text (Field $row 'InterfaceType')
            mediaTypeReported = Text (Field $row 'MediaType')
        })
    }
    # Device serials, ProcessorId, MAC, username and machine name are not emitted.
    return [ordered]@{
        schemaVersion = 1; collectorVersion = '0.1.0'
        collectedAt = [DateTime]::UtcNow.ToString('o')
        parts = @($parts.ToArray()); warnings = @($warnings.ToArray())
    }
}

Export-ModuleMember -Function Get-PcInventory
