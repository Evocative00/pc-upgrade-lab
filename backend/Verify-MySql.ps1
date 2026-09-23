$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Run this helper on Windows.' }

$mysqlCheckPreviousPassword = $env:DB_PASSWORD
$mysqlCheckSecurePassword = $null
try {
    if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
        $mysqlCheckSecurePassword = Read-Host 'MySQL pc_upgrade_app password' -AsSecureString
        $mysqlCheckCredential = [System.Net.NetworkCredential]::new('', $mysqlCheckSecurePassword)
        $env:DB_PASSWORD = $mysqlCheckCredential.Password
        $mysqlCheckCredential = $null
    }
    & (Join-Path $PSScriptRoot 'gradlew.bat') --project-dir $PSScriptRoot mysqlTest --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw 'MySQL verification failed. See the Gradle output and build/reports/tests/mysqlTest/index.html.'
    }
} finally {
    $env:DB_PASSWORD = $mysqlCheckPreviousPassword
    if ($null -ne $mysqlCheckSecurePassword) { $mysqlCheckSecurePassword.Dispose() }
}
