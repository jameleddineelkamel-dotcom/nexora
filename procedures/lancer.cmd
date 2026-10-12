@echo off
rem NEXORA procedures : lance le microservice procedures sur http://localhost:8084 (Ctrl+C pour arreter).
cd /d "%~dp0"
if not exist backend\target\nexora-procedures.jar goto absent
start "" http://localhost:8084
java -jar backend\target\nexora-procedures.jar
pause
goto :eof

:absent
echo Jar introuvable : lancez d'abord construire.cmd
pause
