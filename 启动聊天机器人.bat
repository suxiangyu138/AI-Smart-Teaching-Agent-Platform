@echo off
cd /d "%~dp0"

echo Killing old process on port 8080...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8080 ^| findstr LISTENING') do (
    taskkill /F /PID %%a >nul 2>&1
)
timeout /t 1 /nobreak >nul

echo Starting server on http://localhost:8080
echo Press Ctrl+C to stop
echo.

start http://localhost:8080
set MAVEN_OPTS=-Xmx512m
mvn spring-boot:run
pause
