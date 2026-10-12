@echo off
rem NEXORA procedures : construit backend\target\nexora-procedures.jar (API + interface Angular integree).
cd /d "%~dp0backend"
set CI=true
set NG_CLI_ANALYTICS=false
call mvnw.cmd -B clean package
echo.
if exist target\nexora-procedures.jar (echo Jar pret : backend\target\nexora-procedures.jar) else (echo La construction a echoue.)
pause
