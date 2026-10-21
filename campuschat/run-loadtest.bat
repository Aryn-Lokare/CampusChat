@echo off
echo ===================================================
echo   Campus Chat System - 500 Connection Load Test
echo ===================================================
cd /d "%~dp0"
call .\mvnw.cmd compile exec:java -Ploadtest
pause
