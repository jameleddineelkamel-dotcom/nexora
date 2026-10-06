@echo off
rem NEXORA auth : lance le service d'authentification unique sur http://localhost:8090 (Ctrl+C pour arreter).
rem Les secrets sont lus dans .env.local (copie de .env.example).
cd /d "%~dp0"
if not exist .env.local goto sansenv
if not exist backend\target\nexora-auth.jar goto absent
start "" http://localhost:8090/login
java -jar backend\target\nexora-auth.jar
pause
goto :eof

:sansenv
echo Fichier .env.local introuvable : copiez .env.example en .env.local et renseignez les mots de passe.
pause
goto :eof

:absent
echo Jar introuvable : lancez d'abord construire.cmd
pause
