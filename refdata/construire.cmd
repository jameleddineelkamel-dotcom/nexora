@echo off
rem NEXORA refdata : construit backend\target\nexora-refdata.jar (API + interface Angular integree).
cd /d "%~dp0backend"
set CI=true
set NG_CLI_ANALYTICS=false
call mvnw.cmd -B clean package
echo.
if exist target\nexora-refdata.jar (echo Jar pret : backend\target\nexora-refdata.jar) else (echo La construction a echoue.)
pause
