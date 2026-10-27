@echo off
chcp 65001 >nul
title FQXS Startup

:: FQXS startup script (Windows)
:: Pin JDK 17: the system default JAVA_HOME may point to JDK 25, which
:: Spring Boot 3.3.4 does not support and crashes on startup (window flashes).

:: 1. Resolve script directory so the H2 file DB (data/) is created in the
::    correct relative location. See HANDOVER.md section 8.
cd /d "%~dp0"

:: 2. Locate JDK 17 without hard-coding a machine-specific version path:
::    a 17.x JAVA_HOME first, then the common install locations.
set "FQXS_JDK17="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
  "%JAVA_HOME%\bin\java" -version 2>&1 | findstr /C:"17.0" >nul && set "FQXS_JDK17=%JAVA_HOME%"
)
if not defined FQXS_JDK17 if exist "C:\Program Files\Java\jdk-17\bin\java.exe" set "FQXS_JDK17=C:\Program Files\Java\jdk-17"
if not defined FQXS_JDK17 if exist "C:\Program Files\Eclipse Adoptium\jdk-17\bin\java.exe" set "FQXS_JDK17=C:\Program Files\Eclipse Adoptium\jdk-17"
if not defined FQXS_JDK17 (
  echo [ERROR] JDK 17 not found. Install JDK 17 or set JAVA_HOME to a JDK 17 directory.
  pause
  exit /b 1
)
set "JAVA_HOME=%FQXS_JDK17%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo [INFO] Using JDK: %JAVA_HOME%
"%JAVA_HOME%\bin\java" -version

:: 3. Neutralize a stray SERVER__PORT variable that some IDE/agent runtimes
::    inject. Spring Boot relaxed binding would treat it as server.port and
::    bind a random port instead of 8080. Harmless when the var is absent.
set "SERVER__PORT="

:: 4. Free port 8080. The H2 file DB allows only one process at a time; a
::    stale instance would block startup or create a wrong empty DB.
set "KILLED=0"
for /f "tokens=5" %%p in ('netstat -ano 2^>nul ^| findstr ":8080" ^| findstr "LISTENING"') do (
  echo [INFO] Killing process on port 8080: %%p
  taskkill /PID %%p /F >nul 2>&1
  set "KILLED=1"
)
if "%KILLED%"=="1" timeout /t 1 >nul 2>&1

:: 5. Optional admin token. Omit it -> /api/db/** returns 403 (safe by default).
if not defined APP_ADMIN_TOKEN set "APP_ADMIN_TOKEN="
if not defined DB_URL set "DB_URL=jdbc:h2:file:./data/fqxs;DB_CLOSE_ON_EXIT=FALSE"

:: 5b. Load local AI keys (GLM / QIANFAN / QWEN) from ai-keys.bat if present.
::     That file is gitignored; ai-keys.bat.example is the template. Keys are
::     only passed as environment variables, never written into the DB or repo.
if exist "%~dp0ai-keys.bat" (
  call "%~dp0ai-keys.bat"
  echo [INFO] Loaded AI keys from ai-keys.bat
) else (
  echo [INFO] No ai-keys.bat found - AI detection engines stay disabled.
  echo [INFO] Copy ai-keys.bat.example to ai-keys.bat and fill in a free key.
)

:: 6. Start (foreground; Ctrl+C to stop). The window stays open on error.
echo [INFO] Starting FQXS. Open http://localhost:8080 after it boots.
echo [INFO] Data file: %CD%data\fqxs.mv.db
echo [INFO] Press Ctrl+C to stop.
call mvnw.cmd -B spring-boot:run
echo [EXIT] FQXS stopped with exit code %errorlevel%.
pause
