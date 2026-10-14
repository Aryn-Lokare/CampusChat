@echo off
echo ===================================================
echo     Campus Chat System - JavaFX Desktop Client
echo ===================================================
cd /d "%~dp0"
call .\mvnw.cmd compile exec:java -Pgui
pause
