@echo off
chcp 65001 >nul
setlocal
set "ENV=%~dp0..\.env.local"
if not exist "%ENV%" goto sansenv
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ENV%") do if "%%a"=="REGISTRATION_DB_PASSWORD" set "MDP=%%b"
if not defined MDP goto sansmdp
echo NEXORA registration : creation de l'utilisateur et de la base nexora_registration...
echo Saisissez le mot de passe de l'utilisateur postgres (rien ne s'affiche pendant la frappe, c'est normal).
echo.
"C:\Program Files\PostgreSQL\17\bin\psql.exe" -h localhost -U postgres -v mdp_registration="%MDP%" -f "%~dp0init-db.sql"
echo.
pause
goto :eof

:sansenv
echo Fichier .env.local introuvable : copiez .env.example en .env.local et choisissez REGISTRATION_DB_PASSWORD.
pause
goto :eof

:sansmdp
echo REGISTRATION_DB_PASSWORD absent de .env.local.
pause
