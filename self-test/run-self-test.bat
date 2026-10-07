@echo off
REM ============================================================================
REM  Self-test bed launcher
REM
REM  Boots a headless server on a test save and runs the mod's built-in
REM  self test unattended, so results can be read from a FILE instead of
REM  being watched on screen.
REM
REM  It:
REM    1. builds the newest code            (gradlew build)
REM    2. prepares self-test\               (eula, server.properties, datapack)
REM    3. boots the server with -Pselftest  (run dir = self-test\, --nogui)
REM
REM  Afterwards read:
REM    self-test\guardianprotocol-selftest.log   <- the report
REM    self-test\logs\latest.log                 <- server log / crash reason
REM
REM  Requires JDK 17 reachable through JAVA_HOME (or org.gradle.java.home).
REM
REM  ASCII-only on purpose: Chinese in a .bat gets mangled by the console
REM  codepage and cmd then tries to execute pieces of the comment.
REM
REM  NOTE: if a previous run left a hung java process holding the world lock,
REM  the next run fails with a "session.lock" sharing violation.  Check the
REM  port first; kill leftovers with:  taskkill /F /IM java.exe
REM
REM  Usage:  self-test\run-self-test.bat
REM ============================================================================

setlocal
set "ROOT=%~dp0.."
set "HERE=%~dp0"
set "PORT=25599"

REM --- refuse to start if something is already listening on our port ---------
netstat -ano | findstr /R /C:"LISTENING" | findstr /C:":%PORT% " >nul
if not errorlevel 1 (
  echo [ERROR] port %PORT% is already in use - a previous server is still alive.
  echo         close it with:  taskkill /F /IM java.exe
  exit /b 1
)

echo [1/4] build ...
call "%ROOT%\gradlew.bat" build
if errorlevel 1 (
  echo [ERROR] build failed - see the output above.
  exit /b 1
)

echo [2/4] prepare self-test environment ...
if not exist "%HERE%eula.txt" (
  >"%HERE%eula.txt" echo eula=true
  echo       wrote eula.txt ^(eula=true^)
)

REM ---------------------------------------------------------------- world cfg
REM Fresh world settings.  Regenerated only if missing.  It pins:
REM   level-name=testbed          -> save folder is self-test\testbed
REM   online-mode=false           -> no Mojang auth on a throwaway server
REM   function-permission-level=4 -> so a startup function may run "stop"
if not exist "%HERE%server.properties" (
  >"%HERE%server.properties" echo level-name=testbed
  >>"%HERE%server.properties" echo online-mode=false
  >>"%HERE%server.properties" echo function-permission-level=4
  >>"%HERE%server.properties" echo spawn-protection=0
  >>"%HERE%server.properties" echo max-tick-time=-1
  >>"%HERE%server.properties" echo view-distance=6
  >>"%HERE%server.properties" echo simulation-distance=6
  echo       wrote server.properties
)

REM Read level-name back out: the world folder MUST match it, otherwise the
REM datapack is installed into a folder the server never opens.
set "LEVEL=testbed"
for /f "usebackq tokens=1,* delims==" %%a in ("%HERE%server.properties") do (
  if /i "%%a"=="level-name" set "LEVEL=%%b"
)
set "SAVE=%HERE%saves\%LEVEL%"

REM ------------------------------------------------------- forge conditional
REM Optional fallback channel.  The mod normally drives the whole run itself
REM (-Dguardianprotocol.selftest=1), so this file is only a safety net.
if not exist "%HERE%config" mkdir "%HERE%config"
>"%HERE%config\forge-server.toml" echo [default]
>>"%HERE%config\forge-server.toml" echo     # Run this function once the server has finished starting.
>>"%HERE%config\forge-server.toml" echo     runFunction="guardian_selftest:selftest"
>>"%HERE%config\forge-server.toml" echo     functionPermissionLevel=4
echo       wrote config\forge-server.toml ^(runFunction^)

REM ------------------------------------------------------------- world folder
REM TWO accepted layouts, because both are natural to a human:
REM   A) self-test\testbed\...            <- save folder dropped straight in
REM   B) self-test\saves\testbed\...      <- the layout this project uses
REM A wins when both exist.  Nothing is moved: the server is simply told which
REM one to open (level-name is written to match).
if exist "%HERE%%LEVEL%\level.dat" (
  set "SAVE=%HERE%%LEVEL%"
  set "LAYOUT=direct (self-test\%LEVEL%)"
) else if exist "%HERE%saves\%LEVEL%\level.dat" (
  set "SAVE=%HERE%saves\%LEVEL%"
  set "LAYOUT=saves (self-test\saves\%LEVEL%)"
) else (
  if not exist "%HERE%saves" mkdir "%HERE%saves"
  set "SAVE=%HERE%saves\%LEVEL%"
  set "LAYOUT=new (self-test\saves\%LEVEL%, fresh world)"
)
if not exist "%SAVE%" mkdir "%SAVE%"
echo       world folder: %SAVE%

REM ------------------------------------------------- level-name must match it
REM The server always opens <run dir>\<level-name>.  Write level-name to the
REM layout actually chosen, and make sure the self test datapack is ENABLED at
REM world creation (initial-enabled-packs only applies when the world is
REM created - for an existing world enable it in game).
set "RELLEVEL=%LEVEL%"
if /i "%SAVE%"=="%HERE%saves\%LEVEL%" set "RELLEVEL=saves/%LEVEL%"
powershell -NoProfile -Command ^
  "$p='%HERE%server.properties'; $t=Get-Content $p; $t=$t -replace '^level-name=.*','level-name=%RELLEVEL%' -replace '^initial-enabled-packs=.*','initial-enabled-packs=vanilla,guardian_selftest'; if (-not ($t -match '^initial-enabled-packs=')) { $t += 'initial-enabled-packs=vanilla,guardian_selftest' }; Set-Content -Path $p -Value $t -Encoding ascii" >nul 2>&1
echo       level-name=%RELLEVEL%  initial-enabled-packs=vanilla,guardian_selftest

REM Always re-sync (not "if not exist"): the datapack is the tracked source of
REM the test-only branch definitions, and a stale copy in the world would make
REM the self test check an old JSON.
if exist "%SAVE%\datapacks\guardian_selftest" rmdir /S /Q "%SAVE%\datapacks\guardian_selftest"
xcopy /E /I /Y /Q "%HERE%datapack\guardian_selftest" "%SAVE%\datapacks\guardian_selftest" >nul
echo       synced datapack into %SAVE%\datapacks\guardian_selftest

if not exist "%SAVE%\level.dat" echo       NOTE: no level.dat there - a fresh test world will be generated.

REM Start from a clean report so old runs cannot be mistaken for this one.
if exist "%HERE%guardianprotocol-selftest.log" del "%HERE%guardianprotocol-selftest.log"

echo [3/4] starting headless server (run dir = self-test, save = saves\%LEVEL%) ...
echo [4/4] waiting for the self test to finish and the server to stop ...
echo.
echo ---- server console below ----

call "%ROOT%\gradlew.bat" runServer -Pselftest

echo.
if exist "%HERE%guardianprotocol-selftest.log" (
  echo DONE. report: %HERE%guardianprotocol-selftest.log
) else (
  echo WARNING: no report file - check %HERE%logs\latest.log
)
exit /b 0
