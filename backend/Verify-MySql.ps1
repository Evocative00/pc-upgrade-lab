# 실제 MySQL 저장 검사를 선택 실행하는 Windows 도구. 일반 build와 달리 로컬 DB가 필요하다.
# 현재는 V6까지의 테이블과 CPU/보드/RAM/GPU/모니터 초기 자료 등록을 마친 뒤 실행한다.
# 직접 입력·카탈로그 연결 PC의 저장, 수정, 컨텍스트 재시작 후 재조회를 검사한다.
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Run this helper on Windows.' }

# 이 PowerShell의 기존 환경 변수 값을 기억해 두고, 검사 종료 시 원래 값으로 복구한다.
$mysqlCheckPreviousPassword = $env:DB_PASSWORD
$mysqlCheckSecurePassword = $null
try {
    # 환경 변수에 비밀번호가 없을 때만 가려진 입력으로 받는다. 명령 인자나 파일에는 비밀번호를 적지 않는다.
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
