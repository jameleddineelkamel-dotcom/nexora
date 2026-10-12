@echo off
chcp 65001 >nul
setlocal
rem NEXORA Guichet unique : cree en une fois les bases de registration, procedures et eservices
rem (un seul mot de passe postgres demande). Les mots de passe applicatifs sont lus dans chaque .env.local.
set "R=%~dp0.."
for %%s in (registration procedures eservices) do if not exist "%R%\%%s\.env.local" (echo Fichier %%s\.env.local introuvable : copiez %%s\.env.example en .env.local. & pause & goto :eof)
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%R%\registration\.env.local") do if "%%a"=="REGISTRATION_DB_PASSWORD" set "M1=%%b"
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%R%\procedures\.env.local") do if "%%a"=="PROCEDURES_DB_PASSWORD" set "M2=%%b"
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%R%\eservices\.env.local") do if "%%a"=="ESERVICES_DB_PASSWORD" set "M3=%%b"
echo NEXORA : creation des bases nexora_registration, nexora_procedures et nexora_eservices...
echo Saisissez le mot de passe de l'utilisateur postgres (rien ne s'affiche pendant la frappe, c'est normal).
echo.
"C:\Program Files\PostgreSQL\17\bin\psql.exe" -h localhost -U postgres -v mdp_registration="%M1%" -v mdp_procedures="%M2%" -v mdp_eservices="%M3%" -f "%R%\registration\database\init-db.sql" -f "%R%\procedures\database\init-db.sql" -f "%R%\eservices\database\init-db.sql"
echo.
pause
