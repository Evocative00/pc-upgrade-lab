param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
# HTTP reads and temporary compatibility checks only. No seed, PC save or DB write.
# Compatible with Windows PowerShell 5.1; the script text intentionally uses ASCII.

Invoke-WebRequest -Method Get -Uri "$BaseUrl/api/auth/providers" -UseBasicParsing -SessionVariable catalog300CheckSession | Out-Null
$catalog300CsrfCookie = $catalog300CheckSession.Cookies.GetCookies([uri]$BaseUrl)['XSRF-TOKEN']
if ($null -eq $catalog300CsrfCookie) { throw 'The backend did not issue an XSRF-TOKEN cookie. Check the security configuration.' }
$catalog300CsrfToken = [uri]::UnescapeDataString($catalog300CsrfCookie.Value)

function Invoke-JsonUtf8 {
    param(
        [string]$Uri,
        [ValidateSet('Get', 'Post')][string]$Method = 'Get',
        [string]$Body
    )
    # Read original response bytes: Windows PowerShell 5.1 can otherwise decode
    # application/json without a charset as Latin-1 and corrupt Korean text.
    $response = $null
    try {
        $request = @{
            Uri = $Uri; Method = $Method; WebSession = $catalog300CheckSession
            UseBasicParsing = $true; Headers = @{ Accept = 'application/json' }
        }
        if ($Method -ceq 'Post') {
            $request.ContentType = 'application/json; charset=utf-8'
            $request.Headers['X-XSRF-TOKEN'] = $catalog300CsrfToken
            $request.Body = [System.Text.Encoding]::UTF8.GetBytes($Body)
        }
        $response = Invoke-WebRequest @request
        $buffer = New-Object System.IO.MemoryStream
        try {
            if ($response.RawContentStream.CanSeek) { $response.RawContentStream.Position = 0 }
            $response.RawContentStream.CopyTo($buffer)
            $bytes = $buffer.ToArray()
        } finally { $buffer.Dispose() }
        $utf8 = New-Object System.Text.UTF8Encoding -ArgumentList $false, $true
        $json = $utf8.GetString($bytes).TrimStart([char]0xFEFF)
        return ($json | ConvertFrom-Json)
    } finally {
        if ($null -ne $response) { $response.RawContentStream.Dispose() }
    }
}

function Assert-Equal {
    param($Actual, $Expected, [string]$Label)
    if ($Actual -cne $Expected) { throw "$Label expected '$Expected', received '$Actual'." }
}
function Assert-Properties {
    param($Value, [string[]]$Names, [string]$Label)
    if ($null -eq $Value) { throw "$Label is missing." }
    $actualNames = @($Value.PSObject.Properties | ForEach-Object { $_.Name })
    foreach ($name in $Names) {
        if ($actualNames -cnotcontains $name) { throw "$Label is missing property '$name'." }
    }
}
function Read-Manifest {
    param([string]$RelativePath)
    $path = Join-Path $PSScriptRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required local verification resource is missing: $path"
    }
    return (Get-Content -LiteralPath $path -Encoding UTF8 -Raw | ConvertFrom-Json)
}
function Get-Detail {
    param([string]$Id)
    if (-not $script:details.ContainsKey($Id)) {
        $script:details[$Id] = Invoke-JsonUtf8 -Uri "$BaseUrl/api/catalog/products/$Id"
    }
    return $script:details[$Id]
}
function Find-Model {
    param([string]$Type, [string]$Name)
    $modelMatches = @($script:products[$Type] | Where-Object { $_.modelName -ceq $Name })
    Assert-Equal $modelMatches.Count 1 "Unique $Type model $Name"
    return $modelMatches[0].id
}
function Find-RamPart {
    param([string]$PartNumber)
    $partMatches = @($script:products['RAM'] | Where-Object { $_.partNumber -ceq $PartNumber })
    Assert-Equal $partMatches.Count 1 "Unique RAM part $PartNumber"
    return $partMatches[0].id
}
function Assert-Specification {
    param($Actual, $Expected, [string]$Label)
    foreach ($property in $Expected.PSObject.Properties) {
        $name = $property.Name
        Assert-Properties $Actual @($name) $Label
        if ($name -ceq 'powerConnectors') {
            $actualConnectors = @($Actual.powerConnectors | Sort-Object connectorType)
            $expectedConnectors = @($Expected.powerConnectors | Sort-Object connectorType)
            Assert-Equal $actualConnectors.Count $expectedConnectors.Count "$Label / power connector rows"
            for ($index = 0; $index -lt $expectedConnectors.Count; $index++) {
                Assert-Equal $actualConnectors[$index].connectorType $expectedConnectors[$index].connectorType "$Label / connector type"
                Assert-Equal $actualConnectors[$index].connectorCount $expectedConnectors[$index].connectorCount "$Label / connector count"
            }
        } else {
            Assert-Equal $Actual.$name $property.Value "$Label / $name"
        }
    }
}
function New-KnownConfiguration {
    param([string]$Cpu, [string]$Board, [string]$Ram, [int]$Modules = 2)
    $profile = $script:support[$Board].profiles[0]
    $listedRows = @($profile.entries | Where-Object {
        $_.support.cpuProductId -ceq $Cpu -and $_.support.supportStatus -ceq 'LISTED' -and $_.support.biosRequirement -cne 'UNKNOWN'
    })
    if ($listedRows.Count -lt 1) { throw 'No confirmed manufacturer row for this virtual test configuration.' }
    $row = $listedRows[0].support
    return @{
        cpuProductId = $Cpu; motherboardProductId = $Board
        ram = @(@{ catalogProductId = $Ram; quantity = $Modules })
        motherboardRevision = $profile.hardwareRevision
        cpuStepping = $row.cpuStepping; currentBiosVersion = $row.minimumBiosVersion
    }
}
function Test-Configuration {
    param([string]$Scenario, [hashtable]$Configuration, [hashtable]$ExpectedChecks)
    # BIOS/revision/stepping describe virtual fixtures, not the user's physical PC.
    $body = $Configuration | ConvertTo-Json -Depth 6
    $response = Invoke-JsonUtf8 -Method Post -Uri "$BaseUrl/api/compatibility/check" -Body $body
    Assert-Equal $response.scope 'CPU_MOTHERBOARD_RAM_V1' 'Compatibility scope'
    Assert-Equal $response.fullPcCompatibilityChecked $false 'Full PC guarantee'
    foreach ($code in $ExpectedChecks.Keys) {
        $checkMatches = @($response.checks | Where-Object { $_.code -ceq $code })
        Assert-Equal $checkMatches.Count 1 "Check $code"
        Assert-Equal $checkMatches[0].status $ExpectedChecks[$code] "$Scenario / $code"
    }
    return [pscustomobject]@{ Scenario = $Scenario; Status = $response.status; Modules = $response.memory.installedModuleCount }
}

$newManifest = Read-Manifest 'src/main/resources/catalog/seed/week2-expand300/manifest.json'
$boardManifest = Read-Manifest 'src/main/resources/catalog/enrichment/motherboard-cpu-expand300.json'
$memoryManifests = @(
    (Read-Manifest 'src/main/resources/catalog/enrichment/cpu-memory-v1.json')
    (Read-Manifest 'src/main/resources/catalog/enrichment/cpu-memory-expand100.json')
    (Read-Manifest 'src/main/resources/catalog/enrichment/cpu-memory-expand300.json')
)
Assert-Equal $newManifest.seedName 'week2-expand300' 'Product seed name'
Assert-Equal @($newManifest.items).Count 139 'New product manifest count'
Assert-Equal $boardManifest.seedName 'motherboard-cpu-expand300' 'Board enrichment name'
Assert-Equal @($boardManifest.cpus).Count 70 'Board enrichment CPU identities'
Assert-Equal @($boardManifest.items).Count 70 'Board enrichment profiles'
$expectedCounts = [ordered]@{ CPU = 70; MOTHERBOARD = 70; RAM = 60; GPU = 80; MONITOR = 20 }
$script:products = @{}; $script:details = @{}; $script:support = @{}
$script:externalToProduct = @{}; $memoryByExternal = @{}; $boardByExternal = @{}
$countResults = @()
foreach ($type in $expectedCounts.Keys) {
    $page = Invoke-JsonUtf8 -Uri "$BaseUrl/api/catalog/products?type=$type&size=100"
    Assert-Equal $page.totalElements $expectedCounts[$type] "$type total"
    Assert-Equal @($page.items).Count $expectedCounts[$type] "$type returned items"
    $script:products[$type] = @($page.items)
    $countResults += [pscustomobject]@{ Type = $type; Products = $page.totalElements }
}
$allIds = @()
for ($pageIndex = 0; $pageIndex -lt 3; $pageIndex++) {
    $page = Invoke-JsonUtf8 -Uri "$BaseUrl/api/catalog/products?page=$pageIndex&size=100"
    Assert-Equal $page.totalElements 300 'Catalog total'
    Assert-Equal $page.totalPages 3 'Catalog pages at size 100'
    Assert-Equal $page.page $pageIndex 'Returned page index'
    Assert-Equal @($page.items).Count 100 "Catalog page $pageIndex item count"
    $allIds += @($page.items | ForEach-Object { $_.id })
}
Assert-Equal @($allIds | Sort-Object -Unique).Count 300 'Distinct IDs across all three pages'
$typedIds = @($script:products.Values | ForEach-Object { $_ } | ForEach-Object { $_.id } | Sort-Object)
Assert-Equal ($typedIds -join ',') (($allIds | Sort-Object) -join ',') 'Type lists and unfiltered pagination contain the same products'

# Map source UUIDs to actual database IDs; seed UUIDs are not assumed to be DB IDs.
foreach ($type in $expectedCounts.Keys) {
    foreach ($product in $script:products[$type]) {
        $detail = Get-Detail $product.id
        Assert-Equal $detail.product.id $product.id 'Detail database ID'
        Assert-Equal $detail.product.type $type 'Detail product type'
        $rawSources = @($detail.sources | Where-Object { $_.sourceName -ceq 'BUILDCORES' })
        Assert-Equal $rawSources.Count 1 "BuildCores source / $($product.modelName)"
        $externalId = $rawSources[0].externalId
        if ($script:externalToProduct.ContainsKey($externalId)) { throw "Duplicate BuildCores source UUID: $externalId" }
        $script:externalToProduct[$externalId] = $product.id
    }
}
Assert-Equal $script:externalToProduct.Count 300 'Distinct fixed-source UUIDs'
foreach ($item in $newManifest.items) {
    if (-not $script:externalToProduct.ContainsKey($item.externalId)) { throw "Missing new product UUID: $($item.externalId)" }
    $detail = Get-Detail $script:externalToProduct[$item.externalId]
    foreach ($name in @('type', 'manufacturer', 'modelName', 'partNumber')) {
        Assert-Equal $detail.product.$name $item.product.$name "New identity $($item.externalId) / $name"
    }
    $rawSource = @($detail.sources | Where-Object { $_.sourceName -ceq 'BUILDCORES' })[0]
    Assert-Equal $rawSource.sourceRevision $newManifest.revision 'Pinned BuildCores revision'
    Assert-Specification $detail.specification $item.specification "New specification $($item.product.modelName)"
}

foreach ($manifest in $memoryManifests) {
    foreach ($item in $manifest.items) {
        if ($memoryByExternal.ContainsKey($item.externalId)) { throw "Duplicate CPU memory identity: $($item.externalId)" }
        $memoryByExternal[$item.externalId] = @{ Item = $item; SeedName = $manifest.seedName }
    }
}
Assert-Equal $memoryByExternal.Count 70 'Expected CPU memory profiles'
foreach ($externalId in $memoryByExternal.Keys) {
    $expected = $memoryByExternal[$externalId]
    $cpuId = $script:externalToProduct[$externalId]
    $memory = Invoke-JsonUtf8 -Uri "$BaseUrl/api/catalog/products/$cpuId/memory-support"
    Assert-Equal $memory.dataAvailable $true "CPU memory evidence $($expected.Item.modelName)"
    Assert-Equal $memory.memoryTypesKnown $expected.Item.support.memoryTypesKnown 'Complete memory type evidence'
    Assert-Equal $memory.maxMemoryBytes $expected.Item.support.maxMemoryBytes 'CPU maximum memory, including null'
    Assert-Equal $memory.channelCount $expected.Item.support.channelCount 'CPU channel count, including null'
    Assert-Equal $memory.source.sourceRevision $expected.SeedName 'CPU memory source batch'
    Assert-Equal $memory.source.sourceUrl $expected.Item.sourceUrl 'CPU memory official source'
    $actualTypes = @($memory.supportedTypes | Sort-Object memoryType)
    $expectedTypes = @($expected.Item.support.supportedTypes | Sort-Object memoryType)
    Assert-Equal $actualTypes.Count $expectedTypes.Count 'CPU supported memory generations'
    for ($index = 0; $index -lt $expectedTypes.Count; $index++) {
        Assert-Equal $actualTypes[$index].memoryType $expectedTypes[$index].memoryType 'CPU supported DDR generation'
        Assert-Equal $actualTypes[$index].maxStandardDataRateMts $expectedTypes[$index].maxStandardDataRateMts 'CPU published standard MT/s'
        Assert-Equal $actualTypes[$index].dataRateConditions $expectedTypes[$index].dataRateConditions 'CPU population/rank speed conditions'
    }
}
$expectedPairCount = 0; $expectedVariantCount = 0; $expectedUnverifiedCount = 0
foreach ($item in $boardManifest.items) {
    $boardByExternal[$item.board.externalId] = $item
    $expectedPairCount += @($item.support.entries | ForEach-Object { $_.cpuExternalId } | Sort-Object -Unique).Count
    $expectedVariantCount += @($item.support.entries).Count
    $expectedUnverifiedCount += @($item.support.entries | Where-Object { $_.supportStatus -ceq 'UNVERIFIED' }).Count
}
Assert-Equal $boardByExternal.Count 70 'Distinct expected board profiles'
Assert-Equal $expectedPairCount 1638 'All current same-socket board/CPU pairs'
$pairCount = 0; $variantCount = 0; $unverifiedCount = 0
foreach ($externalId in $boardByExternal.Keys) {
    $expected = $boardByExternal[$externalId]
    $boardId = $script:externalToProduct[$externalId]
    $boardDetail = Get-Detail $boardId
    $view = Invoke-JsonUtf8 -Uri "$BaseUrl/api/catalog/products/$boardId/cpu-support"
    Assert-Equal $view.dataAvailable $true "Board support evidence $($expected.board.modelName)"
    Assert-Equal $view.listComplete $false 'Limited manufacturer support scope'
    Assert-Equal @($view.profiles).Count 1 'One approved hardware revision profile'
    $profile = $view.profiles[0]
    Assert-Equal $profile.source.sourceRevision 'motherboard-cpu-expand300' 'Latest expanded board support source'
    Assert-Equal $profile.source.sourceUrl $expected.sourceUrl 'Board support official source'
    Assert-Equal $profile.revisionScope $expected.support.revisionScope 'Board revision scope'
    Assert-Equal $profile.hardwareRevision $expected.support.hardwareRevision 'Board hardware revision, including null'
    $script:support[$boardId] = $view
    $expectedIds = @($script:products['CPU'] | Where-Object {
        (Get-Detail $_.id).specification.socketCode -ceq $boardDetail.specification.socketCode
    } | ForEach-Object { $_.id } | Sort-Object)
    $entries = @($profile.entries | ForEach-Object { $_.support })
    $actualIds = @($entries | ForEach-Object { $_.cpuProductId } | Sort-Object -Unique)
    Assert-Equal ($actualIds -join ',') ($expectedIds -join ',') "All same-socket CPUs / $($expected.board.modelName)"
    Assert-Equal $entries.Count @($expected.support.entries).Count 'Board variant row count'
    $actualByKey = @{}
    foreach ($entry in $entries) {
        $key = "$($entry.cpuProductId)|$($entry.variantKey)"
        if ($actualByKey.ContainsKey($key)) { throw "Duplicate persisted CPU variant: $key" }
        $actualByKey[$key] = $entry
    }
    foreach ($entry in $expected.support.entries) {
        $cpuId = $script:externalToProduct[$entry.cpuExternalId]
        $key = "$cpuId|$($entry.variantKey)"
        if (-not $actualByKey.ContainsKey($key)) { throw "Missing persisted CPU variant: $key" }
        foreach ($name in @('supportStatus', 'reportedCpuName', 'cpuStepping', 'biosRequirement', 'minimumBiosVersion', 'manufacturerBiosLabel', 'sourceUrl', 'conditions')) {
            Assert-Equal $actualByKey[$key].$name $entry.$name "$($expected.board.modelName) / $key / $name"
        }
    }
    $pairCount += $actualIds.Count; $variantCount += $entries.Count
    $unverifiedCount += @($entries | Where-Object { $_.supportStatus -ceq 'UNVERIFIED' }).Count
}
Assert-Equal $pairCount $expectedPairCount 'Board/CPU pairs'
Assert-Equal $variantCount $expectedVariantCount 'Stepping variant rows'
Assert-Equal $unverifiedCount $expectedUnverifiedCount 'Explicitly unverified manufacturer rows'

# Focused display-contract checks complement the exact 139-item specification checks.
$gpu = Get-Detail (Find-Model 'GPU' 'Intel Arc A380 Challenger ITX 6GB OC')
Assert-Equal $gpu.specification.pcieConnectorLanes $null 'Unknown physical connector lanes stay null'
Assert-Equal $gpu.specification.pcieActiveLanes 8 'Known PCIe active lanes stay separate'
Assert-Equal $gpu.specification.powerConnectorsKnown $true 'Confirmed GPU auxiliary connector evidence'
Assert-Equal $gpu.specification.powerConnectors[0].connectorType 'PCIE_8PIN' 'GPU board-side power connector'
$monitor = Get-Detail (Find-Model 'MONITOR' 'VG259QM')
Assert-Equal $monitor.specification.nativeStandardRefreshHz 240 'Monitor non-overclock refresh'
Assert-Equal $monitor.specification.hasRefreshOverclock $true 'Monitor overclock evidence'
Assert-Equal $monitor.specification.nativeOcRefreshHz 280 'Monitor overclock refresh'
$kit = Get-Detail (Find-RamPart 'CMK48GX5M2B5600C40')
Assert-Equal $kit.specification.moduleCount 2 'RAM kit physical module count'
Assert-Equal $kit.specification.moduleCapacityBytes 25769803776 'Each RAM module is 24 GiB'
Assert-Properties $kit.specification @('isEcc') 'RAM nullable ECC contract'
Assert-Equal $kit.specification.isEcc $null 'Unverified system ECC is not inferred from DDR5 on-die ECC'

$oldCpu = Find-Model 'CPU' 'Ryzen 5 5600X'
$newCpu = Find-Model 'CPU' 'Ryzen 9 7900'
$newIntel = Find-Model 'CPU' 'Core i5-14500'
$newAm4Board = Find-Model 'MOTHERBOARD' 'MAG B550 TOMAHAWK'
$oldAm5Board = Find-Model 'MOTHERBOARD' 'PRO B650M-A WIFI'
$newIntelBoard = Find-Model 'MOTHERBOARD' 'PRO B760-P WIFI DDR4'
$ddr4 = Find-RamPart 'KVR32N22S8/16'
$ddr5 = Find-RamPart 'KVR56U46BD8-32'
$newDdr4 = Find-RamPart 'KVR26N19D8/16'
$knownChecks = @{ CPU_MANUFACTURER_SUPPORT = 'COMPATIBLE'; CPU_BIOS = 'COMPATIBLE' }
$oldCpuNewBoard = New-KnownConfiguration $oldCpu $newAm4Board $ddr4
$newCpuOldBoard = New-KnownConfiguration $newCpu $oldAm5Board $ddr5
$newCpuNewBoard = New-KnownConfiguration $newIntel $newIntelBoard $newDdr4
$wrongDdr = New-KnownConfiguration $oldCpu $newAm4Board $ddr5
$tooMany = New-KnownConfiguration $oldCpu $newAm4Board $ddr4 5
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
    Test-Configuration 'New CPU / new board / new RAM' $newCpuNewBoard $knownChecks
    Test-Configuration 'DDR4 board / DDR5 RAM' $wrongDdr @{ MOTHERBOARD_RAM_TYPE_0 = 'INCOMPATIBLE' }
    Test-Configuration 'Five modules / four slots' $tooMany @{ RAM_SLOT_COUNT = 'INCOMPATIBLE' }
    Test-Configuration 'Unknown board revision' $unknownRevision @{ CPU_MANUFACTURER_SUPPORT = 'NEEDS_CHECK' }
    Test-Configuration 'Unverified manufacturer row' $unknownSupport @{ CPU_MANUFACTURER_SUPPORT = 'NEEDS_CHECK'; CPU_BIOS = 'NEEDS_CHECK' }
)
$countResults | Format-Table -AutoSize
$scenarioResults | Format-Table -AutoSize
Write-Host "Verified 300 products, exact identities/specifications for 139 additions, 70 CPU memory profiles, 70 board profiles, $pairCount CPU pairs, $variantCount variant rows and $unverifiedCount unverified rows."
