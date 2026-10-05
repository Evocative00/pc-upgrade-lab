param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

# Keep the issued CSRF cookie with the token header for temporary POST checks.
Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/auth/providers" -SessionVariable compatibilityCheckSession | Out-Null
$compatibilityCsrfCookie = $compatibilityCheckSession.Cookies.GetCookies([uri]$BaseUrl)['XSRF-TOKEN']
if ($null -eq $compatibilityCsrfCookie) { throw 'The backend did not issue an XSRF-TOKEN cookie. Check the security configuration.' }
$compatibilityCsrfHeaders = @{ 'X-XSRF-TOKEN' = [uri]::UnescapeDataString($compatibilityCsrfCookie.Value) }

# Read-only checks against the running local backend. No PC or catalog rows are created/updated.
function Find-CatalogProductId {
    param([string]$Type, [string]$Model)
    $keyword = [uri]::EscapeDataString($Model)
    $page = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/catalog/products?type=$Type&q=$keyword&size=100"
    $modelMatches = @($page.items | Where-Object { $_.modelName -ceq $Model -and $_.type -ceq $Type })
    if ($modelMatches.Count -ne 1) {
        throw "Expected one $Type product named '$Model'; found $($modelMatches.Count). Check the catalog seed batches."
    }
    return $modelMatches[0].id
}

function Test-TemporaryConfiguration {
    param([string]$Scenario, [hashtable]$Configuration, [string]$ExpectedStatus)
    $body = $Configuration | ConvertTo-Json -Depth 5
    $result = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/compatibility/check" -ContentType 'application/json; charset=utf-8' -Body $body -WebSession $compatibilityCheckSession -Headers $compatibilityCsrfHeaders
    if ($result.status -cne $ExpectedStatus) {
        $result.checks | Format-Table code, status, message -AutoSize | Out-Host
        throw "$Scenario expected $ExpectedStatus but received $($result.status)."
    }
    if ($result.scope -cne 'CPU_MOTHERBOARD_RAM_V1' -or $result.fullPcCompatibilityChecked -ne $false) {
        throw 'The response must explicitly limit its scope to CPU, motherboard and RAM.'
    }
    if ($result.memory.installedModuleCount -ne 2 -or $result.memory.totalCapacityBytes -ne 34359738368) {
        throw 'RAM must count two actual 16 GiB modules, without multiplying by the kit moduleCount again.'
    }
    return [pscustomobject]@{
        Scenario = $Scenario
        Status = $result.status
        InstalledModules = $result.memory.installedModuleCount
        TotalRamGiB = ($result.memory.totalCapacityBytes / 1GB)
    }
}

$cpuId = Find-CatalogProductId 'CPU' 'Core i5-12400F'
$boardId = Find-CatalogProductId 'MOTHERBOARD' 'PRO B760M-A WIFI DDR4'
$ddr4Id = Find-CatalogProductId 'RAM' 'FURY Beast DDR4-3200 CL16 32GB (2x16GB)'
$ddr5Id = Find-CatalogProductId 'RAM' 'FURY Beast DDR5-6000 CL36 32GB (2x16GB)'

# These BIOS/stepping values belong to a virtual test configuration, not the user's actual PC.
$known = @{
    cpuProductId = $cpuId
    motherboardProductId = $boardId
    ram = @(@{ catalogProductId = $ddr4Id; quantity = 2 })
    motherboardRevision = $null
    cpuStepping = 'H0'
    currentBiosVersion = '7D99v10'
}
$unknown = @{} + $known
$unknown.cpuStepping = $null
$unknown.currentBiosVersion = $null
$ddrMismatch = @{} + $known
$ddrMismatch.ram = @(@{ catalogProductId = $ddr5Id; quantity = 2 })

$results = @(
    Test-TemporaryConfiguration 'Known conditions' $known 'COMPATIBLE'
    Test-TemporaryConfiguration 'Unknown stepping/BIOS' $unknown 'NEEDS_CHECK'
    Test-TemporaryConfiguration 'DDR4 board with DDR5' $ddrMismatch 'INCOMPATIBLE'
)
$results | Format-Table -AutoSize
Write-Host 'All three compatibility scenarios passed. Only temporary configurations were checked.'
