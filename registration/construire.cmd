@echo off
rem NEXORA registration : construit backend\target\nexora-registration.jar (API + interface Angular integree).
cd /d "%~dp0backend"
set CI=true
set NG_CLI_ANALYTICS=false
call mvnw.cmd -B clean package
echo.
if exist target\nexora-registration.jar (echo Jar pret : backend\target\nexora-registration.jar) else (echo La construction a echoue.)
pause
