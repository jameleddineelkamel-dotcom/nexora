package com.nexora.procedures.rdm;

import com.nexora.procedures.web.ProceduresException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Consultation du dictionnaire SCRDM D23B (ABIE, BIE, résolution de chemins). */
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
        return Map.of("abie", a, "properties", rdm.proprietes(code), "paths", rdm.cheminsVers(code, 5, 8));
    }

    @GetMapping("/search")
    public List<RdmService.Bie> rechercher(@RequestParam String q, @RequestParam(defaultValue = "50") int limit) {
        return rdm.rechercher(q, Math.min(limit, 200));
    }

    @GetMapping("/resolve")
    public RdmService.Element resoudre(@RequestParam String path) {
        return rdm.resoudre(path).orElseThrow(() -> ProceduresException.introuvable("Chemin BIE introuvable dans le SCRDM D23B : " + path));
    }
}
