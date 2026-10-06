@echo off
rem NEXORA auth : construit backend\target\nexora-auth.jar (SSO + console d'administration integree).
cd /d "%~dp0backend"
set CI=true
set NG_CLI_ANALYTICS=false
call mvnw.cmd -B clean package
echo.
if exist target\nexora-auth.jar (echo Jar pret : backend\target\nexora-auth.jar) else (echo La construction a echoue.)
pause
