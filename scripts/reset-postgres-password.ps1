# Run this in PowerShell AS ADMINISTRATOR to reset local Postgres password to: admin
# Right-click PowerShell -> Run as administrator -> paste these commands

$ErrorActionPreference = "Stop"
$pgHba = "C:\Program Files\PostgreSQL\16\data\pg_hba.conf"
$psql  = "C:\Program Files\PostgreSQL\16\bin\psql.exe"
$backup = "$pgHba.bak_password_reset"

Write-Host "1) Backing up pg_hba.conf..."
Copy-Item $pgHba $backup -Force

Write-Host "2) Temporarily allowing local trust login..."
$lines = Get-Content $pgHba
$newLines = foreach ($line in $lines) {
	if ($line -match '^\s*host\s+all\s+all\s+127\.0\.0\.1/32\s+') {
		"host    all             all             127.0.0.1/32            trust"
	} elseif ($line -match '^\s*host\s+all\s+all\s+::1/128\s+') {
		"host    all             all             ::1/128                 trust"
	} else {
		$line
	}
}
$newLines | Set-Content $pgHba -Encoding ascii

Write-Host "3) Restarting PostgreSQL..."
Restart-Service postgresql-x64-16 -Force
Start-Sleep -Seconds 3

Write-Host "4) Setting password for user postgres to 'admin'..."
& $psql -h 127.0.0.1 -U postgres -d postgres -c "ALTER USER postgres WITH PASSWORD 'admin';"

Write-Host "5) Creating app user/database for HAPI..."
& $psql -h 127.0.0.1 -U postgres -d postgres -c "DO `$`$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'admin') THEN CREATE ROLE admin LOGIN PASSWORD 'admin' SUPERUSER; END IF; END `$`$;"
& $psql -h 127.0.0.1 -U postgres -d postgres -c "SELECT 'exists' FROM pg_database WHERE datname = 'hapi'" | Out-Null
$dbExists = & $psql -h 127.0.0.1 -U postgres -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='hapi'"
if (-not $dbExists.Trim()) {
	& $psql -h 127.0.0.1 -U postgres -d postgres -c "CREATE DATABASE hapi OWNER admin;"
} else {
	Write-Host "Database hapi already exists"
}

Write-Host "6) Restoring pg_hba.conf security..."
Copy-Item $backup $pgHba -Force
Restart-Service postgresql-x64-16 -Force
Start-Sleep -Seconds 3

Write-Host "7) Verifying login with admin/admin..."
$env:PGPASSWORD = "admin"
& $psql -h 127.0.0.1 -U admin -d hapi -c "SELECT current_user, current_database();"
Remove-Item Env:PGPASSWORD

Write-Host ""
Write-Host "DONE. Use in application.yaml:"
Write-Host "  username: admin"
Write-Host "  password: admin"
Write-Host "  url: jdbc:postgresql://localhost:5432/hapi"
