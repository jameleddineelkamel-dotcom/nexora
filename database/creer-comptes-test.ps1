# NEXORA - comptes de test du guichet unique (environnement de developpement uniquement).
# Cree, via l'API d'administration du service auth (http://localhost:8090), un compte par role du guichet unique.
# Le compte admin est lu dans auth\.env.local (AUTH_ADMIN_PASSWORD) ; le mot de passe des comptes de test est
# NEXORA_TEST_PASSWORD (meme fichier). Un compte deja existant est laisse tel quel.
$ErrorActionPreference = 'Stop'
$racine = Split-Path -Parent $PSScriptRoot
$conf = @{}
Get-Content (Join-Path $racine 'auth\.env.local') | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2; $conf[$k] = $v
}
if (-not $conf['AUTH_ADMIN_PASSWORD'] -or -not $conf['NEXORA_TEST_PASSWORD']) {
    Write-Host 'AUTH_ADMIN_PASSWORD ou NEXORA_TEST_PASSWORD absent de auth\.env.local.'; exit 1
}
$auth = 'http://localhost:8090'
try {
    $cnx = Invoke-RestMethod -Method Post -Uri "$auth/api/v1/auth/login" -ContentType 'application/json' `
        -Body (@{ username = 'admin'; password = $conf['AUTH_ADMIN_PASSWORD'] } | ConvertTo-Json)
} catch {
    Write-Host "Connexion du compte admin impossible (service auth lance ? mot de passe admin modifie ?) : $($_.Exception.Message)"; exit 1
}
$entetes = @{ Authorization = "Bearer $($cnx.accessToken)"; 'X-Nexora-Channel' = 'API'; 'X-Nexora-Reason' = 'Comptes%20de%20test' }
$comptes = @(
    @{ u = 'test.concepteur';  n = 'Concepteur de procedures (test)';  o = 'Programme GUCE';      g = @('CONCEPTEURS_PROCEDURES'); r = @() },
    @{ u = 'test.approbateur'; n = 'Approbateur de procedures (test)'; o = 'Comite metier';       g = @(); r = @('APPROBATEUR_PROCEDURES') },
    @{ u = 'test.operateur';   n = 'Operateur SCIE SA (test)';         o = 'SCIE SA';             g = @('OPERATEURS_ECONOMIQUES'); r = @() },
    @{ u = 'test.cda';         n = 'Commissionnaire agree (test)';     o = 'Transit CDA';         g = @('COMMISSIONNAIRES_AGREES'); r = @() },
    @{ u = 'test.agent';       n = 'Agent de traitement (test)';       o = 'MINCOMMERCE DCE/SOC'; g = @('MINCOMMERCE_TRAITEMENT'); r = @() },
    @{ u = 'test.signataire';  n = 'Agent signataire (test)';          o = 'MINCOMMERCE DCE';     g = @('MINCOMMERCE_SIGNATAIRES'); r = @() },
    @{ u = 'test.superviseur'; n = 'Superviseur GUCE (test)';          o = 'GUCE';                g = @(); r = @('SUPERVISEUR') },
    @{ u = 'test.adminesv';    n = 'Administrateur e-Services (test)'; o = 'GUCE';                g = @(); r = @('ADMINISTRATEUR_ESERVICES') },
    @{ u = 'test.banque';      n = 'Banque partenaire (test)';         o = 'BICEC';               g = @('BANQUES'); r = @() }
)
foreach ($c in $comptes) {
    $corps = @{ username = $c.u; fullName = $c.n; organisation = $c.o; language = 'fr'; active = $true; password = $conf['NEXORA_TEST_PASSWORD'];
                mustChangePassword = $false; groups = $c.g; roles = $c.r } | ConvertTo-Json
    try {
        Invoke-RestMethod -Method Post -Uri "$auth/api/v1/admin/users" -Headers $entetes -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($corps)) | Out-Null
        Write-Host "Cree      : $($c.u)"
    } catch {
        Write-Host "Inchange  : $($c.u) ($($_.ErrorDetails.Message))"
    }
}
Write-Host ''
Write-Host 'Mot de passe des comptes de test : NEXORA_TEST_PASSWORD dans auth\.env.local'
