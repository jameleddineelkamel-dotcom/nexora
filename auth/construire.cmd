@echo off
rem NEXORA auth : construit backend\target\nexora-auth.jar (SSO + console d'administration integree).
cd /d "%~dp0backend"
call mvnw.cmd -B package
echo.
if exist target\nexora-auth.jar (echo Jar pret : backend\target\nexora-auth.jar) else (echo La construction a echoue.)
pause
