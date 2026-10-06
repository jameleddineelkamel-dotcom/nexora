package com.nexora.auth.web;

import com.nexora.auth.account.AccountService;
import com.nexora.auth.account.AdminService;
import com.nexora.auth.account.Compte;
import com.nexora.auth.account.Journal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Administration des utilisateurs, groupes, rôles et habilitations (permission auth.administration). */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService admin;
    private final AccountService comptes;
    private final Journal journal;

    public AdminController(AdminService admin, AccountService comptes, Journal journal) {
        this.admin = admin;
        this.comptes = comptes;
        this.journal = journal;
    }

    @GetMapping("/users")
    public List<Compte> utilisateurs() { return comptes.tous(); }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminService.Creation creerUtilisateur(@RequestBody AdminService.UtilisateurSaisi s, @AuthenticationPrincipal Jwt jwt, HttpServletRequest r) {
        return admin.creerUtilisateur(s, jwt.getSubject(), SsoController.ip(r));
    }

    @PutMapping("/users/{id}")
    public Compte modifierUtilisateur(@PathVariable long id, @RequestBody AdminService.UtilisateurSaisi s, @AuthenticationPrincipal Jwt jwt, HttpServletRequest r) {
        return admin.modifierUtilisateur(id, s, jwt.getSubject(), SsoController.ip(r));
    }

    @PostMapping("/users/{id}/reset-password")
    public Map<String, String> reinitialiser(@PathVariable long id, @AuthenticationPrincipal Jwt jwt, HttpServletRequest r) {
        return Map.of("temporaryPassword", admin.reinitialiserMotDePasse(id, jwt.getSubject(), SsoController.ip(r)));
    }

    @PostMapping("/users/{id}/unlock")
    public Compte deverrouiller(@PathVariable long id, @AuthenticationPrincipal Jwt jwt, HttpServletRequest r) {
        return admin.deverrouiller(id, jwt.getSubject(), SsoController.ip(r));
    }

    @GetMapping("/groups")
    public List<AdminService.Groupe> groupes() { return admin.groupes(); }

    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminService.Groupe creerGroupe(@RequestBody AdminService.GroupeSaisi s, @AuthenticationPrincipal Jwt jwt, HttpServletRequest r) {
        return admin.enregistrerGroupe(null, s, jwt.getSubject(), SsoController.ip(r));
    }

    @PutMapping("/groups/{id}")
    public AdminService.Groupe modifierGroupe(@PathVariable long id, @RequestBody AdminService.GroupeSaisi s, @AuthenticationPrincipal Jwt jwt, HttpServletRequest r) {
        return admin.enregistrerGroupe(id, s, jwt.getSubject(), SsoController.ip(r));
    }

    @DeleteMapping("/groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimerGroupe(@PathVariable long id, @AuthenticationPrincipal Jwt jwt, HttpServletRequest r) {
        admin.supprimerGroupe(id, jwt.getSubject(), SsoController.ip(r));
    }

    @GetMapping("/roles")
    public List<AdminService.Role> roles() { return admin.roles(); }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminService.Role creerRole(@RequestBody AdminService.RoleSaisi s, @AuthenticationPrincipal Jwt jwt, HttpServletRequest r) {
        return admin.enregistrerRole(null, s, jwt.getSubject(), SsoController.ip(r));
    }

    @PutMapping("/roles/{id}")
    public AdminService.Role modifierRole(@PathVariable long id, @RequestBody AdminService.RoleSaisi s, @AuthenticationPrincipal Jwt jwt, HttpServletRequest r) {
        return admin.enregistrerRole(id, s, jwt.getSubject(), SsoController.ip(r));
    }

    @DeleteMapping("/roles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimerRole(@PathVariable long id, @AuthenticationPrincipal Jwt jwt, HttpServletRequest r) {
        admin.supprimerRole(id, jwt.getSubject(), SsoController.ip(r));
    }

    @GetMapping("/permissions")
    public List<AdminService.Permission> permissions() { return admin.permissions(); }

    /** Journal de sécurité : connexions réussies et échouées, verrouillages, actions d'administration. */
    @GetMapping("/events")
    public Journal.Page evenements(@RequestParam(required = false) String username, @RequestParam(required = false) String type,
                                   @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return journal.lire(username, type, page, size);
    }
}
