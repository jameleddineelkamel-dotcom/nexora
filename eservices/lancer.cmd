@echo off
rem NEXORA eservices : lance le microservice eservices sur http://localhost:8085 (Ctrl+C pour arreter).
cd /d "%~dp0"
if not exist backend\target\nexora-eservices.jar goto absent
start "" http://localhost:8085
java -jar backend\target\nexora-eservices.jar
pause
goto :eof

:absent
echo Jar introuvable : lancez d'abord construire.cmd
pause
