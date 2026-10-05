param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/auth/providers" -SessionVariable catalogExpansionCheckSession | Out-Null
$catalogExpansionCsrfCookie = $catalogExpansionCheckSession.Cookies.GetCookies([uri]$BaseUrl)['XSRF-TOKEN']
if ($null -eq $catalogExpansionCsrfCookie) { throw 'The backend did not issue an XSRF-TOKEN cookie. Check the security configuration.' }
$catalogExpansionCsrfHeaders = @{ 'X-XSRF-TOKEN' = [uri]::UnescapeDataString($catalogExpansionCsrfCookie.Value) }
# HTTP reads and temporary compatibility checks only. This helper does not seed or save a PC.
function Assert-Equal {
    param($Actual, $Expected, [string]$Label)
    if ($Actual -cne $Expected) { throw "$Label expected '$Expected', received '$Actual'." }
}
function Get-Detail {
    param([string]$Id)
    if (-not $script:details.ContainsKey($Id)) {
        $script:details[$Id] = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/catalog/products/$Id"
    }
    return $script:details[$Id]
}
function Find-Model {
    param([string]$Type, [string]$Name)
    $matches = @($script:products[$Type] | Where-Object { $_.modelName -ceq $Name })
    Assert-Equal $matches.Count 1 "Unique $Type model $Name"
    return $matches[0].id
}
function Find-RamPart {
    param([string]$PartNumber)
    $matches = @($script:products['RAM'] | Where-Object { $_.partNumber -ceq $PartNumber })
    Assert-Equal $matches.Count 1 "Unique RAM part $PartNumber"
    return $matches[0].id
}
function New-KnownConfiguration {
    param([string]$Cpu, [string]$Board, [string]$Ram, [int]$Modules = 2)
    $profile = $script:support[$Board].profiles[0]
    $rows = @($profile.entries | Where-Object {
        $_.support.cpuProductId -ceq $Cpu -and $_.support.supportStatus -ceq 'LISTED' -and $_.support.biosRequirement -cne 'UNKNOWN'
    })
    if ($rows.Count -lt 1) { throw 'No confirmed support row for this virtual test configuration.' }
    $row = $rows[0].support
    return @{
        cpuProductId = $Cpu; motherboardProductId = $Board
        ram = @(@{ catalogProductId = $Ram; quantity = $Modules })
        motherboardRevision = $profile.hardwareRevision
        cpuStepping = $row.cpuStepping; currentBiosVersion = $row.minimumBiosVersion
    }
}
function Test-Configuration {
    param([string]$Scenario, [hashtable]$Configuration, [hashtable]$ExpectedChecks)
    # BIOS/revision values here describe a virtual fixture, not the user's physical hardware.
    $body = $Configuration | ConvertTo-Json -Depth 6
    $response = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/compatibility/check" -ContentType 'application/json; charset=utf-8' -Body $body -WebSession $catalogExpansionCheckSession -Headers $catalogExpansionCsrfHeaders
    Assert-Equal $response.scope 'CPU_MOTHERBOARD_RAM_V1' 'Compatibility scope'
    Assert-Equal $response.fullPcCompatibilityChecked $false 'Full PC guarantee'
    foreach ($code in $ExpectedChecks.Keys) {
        $matches = @($response.checks | Where-Object { $_.code -ceq $code })
        Assert-Equal $matches.Count 1 "Check $code"
        Assert-Equal $matches[0].status $ExpectedChecks[$code] "$Scenario / $code"
    }
    return [pscustomobject]@{ Scenario = $Scenario; Status = $response.status; Modules = $response.memory.installedModuleCount }
}

$expectedCounts = [ordered]@{ CPU = 37; MOTHERBOARD = 41; RAM = 41; GPU = 34; MONITOR = 8 }
$script:products = @{}; $script:details = @{}; $script:support = @{}
$countResults = @()
foreach ($type in $expectedCounts.Keys) {
    $page = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/catalog/products?type=$type&size=100"
    Assert-Equal $page.totalElements $expectedCounts[$type] "$type total"
    Assert-Equal @($page.items).Count $expectedCounts[$type] "$type returned items"
    $script:products[$type] = @($page.items)
    $countResults += [pscustomobject]@{ Type = $type; Products = $page.totalElements }
}
$all = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/catalog/products?size=100"
Assert-Equal $all.totalElements 161 'Catalog total'
Assert-Equal $all.totalPages 2 'Catalog pages at size 100'
foreach ($cpu in $script:products['CPU']) {
    $null = Get-Detail $cpu.id
    $memory = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/catalog/products/$($cpu.id)/memory-support"
    Assert-Equal $memory.dataAvailable $true "CPU memory evidence $($cpu.modelName)"
    if (@($memory.supportedTypes).Count -lt 1) { throw 'CPU memory generation evidence is missing.' }
}
$pairCount = 0; $variantCount = 0; $unverifiedCount = 0
foreach ($board in $script:products['MOTHERBOARD']) {
    $detail = Get-Detail $board.id
    $view = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/catalog/products/$($board.id)/cpu-support"
    Assert-Equal $view.dataAvailable $true "Board support evidence $($board.modelName)"
    Assert-Equal $view.listComplete $false 'Support list must declare its limited scope'
    Assert-Equal @($view.profiles).Count 1 'One approved revision profile'
    Assert-Equal $view.profiles[0].source.sourceRevision 'motherboard-cpu-expand100' 'Expanded support evidence'
    $script:support[$board.id] = $view
    $expectedIds = @($script:products['CPU'] | Where-Object { (Get-Detail $_.id).specification.socketCode -ceq $detail.specification.socketCode } | ForEach-Object { $_.id } | Sort-Object)
    $entries = @($view.profiles[0].entries | ForEach-Object { $_.support })
    $actualIds = @($entries | ForEach-Object { $_.cpuProductId } | Sort-Object -Unique)
    Assert-Equal ($actualIds -join ',') ($expectedIds -join ',') "All current same-socket CPUs / $($board.modelName)"
    $pairCount += $actualIds.Count; $variantCount += $entries.Count
    $unverifiedCount += @($entries | Where-Object { $_.supportStatus -ceq 'UNVERIFIED' }).Count
}
Assert-Equal $pairCount 505 'Board/CPU pairs'
Assert-Equal $variantCount 577 'Stepping variant rows'
Assert-Equal $unverifiedCount 27 'Explicitly unverified support rows'

$cpuOld = Find-Model 'CPU' 'Core i5-12400F'
$cpuNew = Find-Model 'CPU' 'Core i9-14900K'
$boardOld = Find-Model 'MOTHERBOARD' 'PRO B760M-A WIFI DDR4'
$boardNew = Find-Model 'MOTHERBOARD' 'PRO B660M-A DDR4'
$ddr4 = Find-RamPart 'KVR32N22S8/16'
$ddr5 = Find-RamPart 'KVR56U46BD8-32'
$knownChecks = @{ CPU_MANUFACTURER_SUPPORT = 'COMPATIBLE'; CPU_BIOS = 'COMPATIBLE' }
$oldCpuNewBoard = New-KnownConfiguration $cpuOld $boardNew $ddr4
$newCpuOldBoard = New-KnownConfiguration $cpuNew $boardOld $ddr4
$wrongDdr = New-KnownConfiguration $cpuNew $boardNew $ddr5
$tooMany = New-KnownConfiguration $cpuOld $boardNew $ddr4 5
$unknownRevision = @{
    cpuProductId = (Find-Model 'CPU' 'Ryzen 5 7600X')
    motherboardProductId = (Find-Model 'MOTHERBOARD' 'B650 EAGLE AX')
    ram = @(@{ catalogProductId = $ddr5; quantity = 2 })
    motherboardRevision = $null; cpuStepping = $null; currentBiosVersion = $null
}
$unknownSupport = @{} + $unknownRevision
$unknownSupport.cpuProductId = Find-Model 'CPU' 'Ryzen 7 9800X3D'
$unknownSupport.motherboardProductId = Find-Model 'MOTHERBOARD' 'B650M Pro RS'
$scenarioResults = @(
    Test-Configuration 'Old CPU / new board' $oldCpuNewBoard $knownChecks
    Test-Configuration 'New CPU / old board' $newCpuOldBoard $knownChecks
    Test-Configuration 'DDR4 board / DDR5 RAM' $wrongDdr @{ MOTHERBOARD_RAM_TYPE_0 = 'INCOMPATIBLE' }
    Test-Configuration 'Five modules / four slots' $tooMany @{ RAM_SLOT_COUNT = 'INCOMPATIBLE' }
    Test-Configuration 'Unknown board revision' $unknownRevision @{ CPU_MANUFACTURER_SUPPORT = 'NEEDS_CHECK' }
    Test-Configuration 'Unverified manufacturer row' $unknownSupport @{ CPU_MANUFACTURER_SUPPORT = 'NEEDS_CHECK'; CPU_BIOS = 'NEEDS_CHECK' }
)
$countResults | Format-Table -AutoSize
$scenarioResults | Format-Table -AutoSize
Write-Host "Verified 161 products, 37 CPU memory profiles, 41 board profiles, $pairCount CPU pairs and $variantCount variant rows."
