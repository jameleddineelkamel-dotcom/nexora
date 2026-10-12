@echo off
rem NEXORA registration : lance le microservice registration sur http://localhost:8083 (Ctrl+C pour arreter).
cd /d "%~dp0"
if not exist backend\target\nexora-registration.jar goto absent
start "" http://localhost:8083
java -jar backend\target\nexora-registration.jar
pause
goto :eof

:absent
echo Jar introuvable : lancez d'abord construire.cmd
pause
