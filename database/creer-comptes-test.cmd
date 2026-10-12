@echo off
rem NEXORA : cree les comptes de test du guichet unique (service auth demarre sur le port 8090).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0creer-comptes-test.ps1"
pause
