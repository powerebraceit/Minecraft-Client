@echo off
setlocal
cd /d "%~dp0"
where java >nul 2>&1
if errorlevel 1 (
  echo Java saknas. Installera Java 17 eller senare fran https://adoptium.net/
  pause
  exit /b 1
)
java -jar "%~dp0AuroraLauncher-1.0.1.jar"
if errorlevel 1 (
  echo.
  echo Aurora kunde inte starta. Kontrollera att Java ar version 17 eller senare.
  pause
  exit /b 1
)
