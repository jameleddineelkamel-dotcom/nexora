@echo off
rem NEXORA eservices : construit backend\target\nexora-eservices.jar (API + interface Angular integree).
cd /d "%~dp0backend"
set CI=true
set NG_CLI_ANALYTICS=false
call mvnw.cmd -B clean package
echo.
if exist target\nexora-eservices.jar (echo Jar pret : backend\target\nexora-eservices.jar) else (echo La construction a echoue.)
pause
