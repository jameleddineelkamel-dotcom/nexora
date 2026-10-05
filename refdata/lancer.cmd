@echo off
rem NEXORA refdata : lance le Referentiel Commun sur http://localhost:8082 (Ctrl+C pour arreter).
cd /d "%~dp0"
if not exist backend\target\nexora-refdata.jar goto absent
start "" http://localhost:8082
java -jar backend\target\nexora-refdata.jar
pause
goto :eof

:absent
echo Jar introuvable : lancez d'abord construire.cmd
pause
