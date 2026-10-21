@echo off
echo ===================================================
echo     Campus Chat System - Promote Admin Account
echo ===================================================
cd /d "%~dp0"
call .\mvnw.cmd compile exec:java -Dexec.mainClass=com.campuschat.PromoteToAdmin
pause
