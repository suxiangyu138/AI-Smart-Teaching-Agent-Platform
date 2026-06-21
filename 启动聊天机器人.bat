@echo off
cd /d "%~dp0"
echo.
echo  ==========================================
echo    LLMChatBot - Spring Boot
echo  ==========================================
echo.
echo  Starting...
echo.
call mvn clean package -q -DskipTests
if %errorlevel% neq 0 (
    echo Build failed! Close the running app first.
    pause
    exit /b
)
echo.
echo  Build OK. Starting server on http://localhost:8080
echo.
start http://localhost:8080
java -jar "target\deepseek-chatbot-1.0.0.jar"
pause
