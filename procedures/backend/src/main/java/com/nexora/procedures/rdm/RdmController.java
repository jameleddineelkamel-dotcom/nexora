package com.nexora.procedures.rdm;

import com.nexora.procedures.web.ProceduresException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Consultation du dictionnaire SCRDM D23B (ABIE, BIE, restrictions contextuelles, résolution de chemins), des types de données
 * et listes de codes, de l'UNTDED 2005, des rôles des parties, de la couche Buy-Ship-Pay, des instruments et des messages.
 */
@RestController
@RequestMapping("/api/v1/rdm")
public class RdmController {

    private final RdmService rdm;

    public RdmController(RdmService rdm) {
        this.rdm = rdm;
    }

    @GetMapping
    public Map<String, Object> resume() {
        return rdm.resume();
    }

    @GetMapping("/abies")
    public List<RdmService.Abie> abies() {
        return rdm.abies();
    }

    @GetMapping("/abies/{code}")
    public Map<String, Object> abie(@PathVariable String code) {
        RdmService.Abie a = rdm.abie(code).orElseThrow(() -> ProceduresException.introuvable("ABIE inconnu : " + code));
        return Map.of("abie", a, "properties", rdm.proprietes(code), "paths", rdm.cheminsVers(code, 5, 8), "restrictions", rdm.restrictions(code));
    }

    /** Types de données UDT / QDT et leur liste de codes. */
    @GetMapping("/data-types")
    public List<RdmService.TypeDonnees> typesDonnees() {
        return rdm.typesDonnees();
    }

    @GetMapping("/code-lists")
    public List<RdmService.ListeCodes> listesCodes() {
        return rdm.listesCodes();
    }

    @GetMapping("/code-lists/{id}")
    public Map<String, Object> listeCodes(@PathVariable String id, @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "500") int limit) {
        RdmService.ListeCodes l = rdm.listeCodes(id).orElseThrow(() -> ProceduresException.introuvable("Liste de codes inconnue : " + id));
        return Map.of("list", l, "codes", rdm.codes(id, q, Math.min(limit, 5000)));
    }

    /** Domaines, processus, entités de haut niveau du Buy-Ship-Pay, leurs règles et les parties attachées. */
    @GetMapping("/bsp")
    public Map<String, Object> bsp() {
        return rdm.bsp();
    }

    /** Recommandations UN/CEFACT, normes et conventions, et ce qu'elles normalisent dans le modèle. */
    @GetMapping("/instruments")
    public Map<String, Object> instruments() {
        return rdm.instruments();
    }

    /** Structures d'échange BSP-RDM D23B et messages UN/EDIFACT. */
    @GetMapping("/messages")
    public List<RdmService.Message> messages() {
        return rdm.messages();
    }

    @GetMapping("/search")
    public List<RdmService.Bie> rechercher(@RequestParam String q, @RequestParam(defaultValue = "50") int limit) {
        return rdm.rechercher(q, Math.min(limit, 200));
    }

    /** UNTDED 2005 (ISO 7372) : recherche par tag, nom ou description. */
    @GetMapping("/untded")
    public List<RdmService.Untded> untded(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "100") int limit) {
        return rdm.rechercherUntded(q, Math.min(limit, 500));
    }

    @GetMapping("/untded/{tag}")
    public RdmService.Untded untdedElement(@PathVariable String tag) {
        return rdm.untded(tag).orElseThrow(() -> ProceduresException.introuvable("Élément UNTDED inconnu : " + tag));
    }

    /** Rôles des parties et lieux du Buy-Ship-Pay (RSM SCRDM) et leur chemin BIE. */
    @GetMapping("/party-roles")
    public List<RdmService.RolePartie> roles() {
        return rdm.roles();
    }

    @GetMapping("/resolve")
    public RdmService.Element resoudre(@RequestParam String path) {
        return rdm.resoudre(path).orElseThrow(() -> ProceduresException.introuvable(rdm.excluParRestriction(path)
                ? "Chemin BIE exclu par une restriction contextuelle du SCRDM D23B : " + path
                : "Chemin BIE introuvable dans le SCRDM D23B : " + path));
    }
}
