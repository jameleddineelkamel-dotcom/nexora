package com.nexora.procedures.agents;

import com.nexora.procedures.web.ProceduresException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Agents IA du studio : catalogue et politique, exécutions, propositions et revue humaine. */
@RestController
@RequestMapping("/api/v1")
public class AgentController {

    private final AgentService agents;
    private final RevueService revues;

    public AgentController(AgentService agents, RevueService revues) {
        this.agents = agents;
        this.revues = revues;
    }

    @GetMapping("/agents")
    public Map<String, Object> catalogue() {
        return Map.of("agents", agents.agents(), "model", agents.modele(), "sequence", AgentService.SEQUENCE);
    }

    @PutMapping("/agents/{code}/enabled")
    public List<AgentService.AgentInfo> activer(@PathVariable String code, @RequestBody Map<String, Boolean> corps) {
        return agents.activer(code, Boolean.TRUE.equals(corps.get("enabled")));
    }

    public record Demande(String objective, String text, String sourceName) {}

    /** Exécution d'un agent (ou de l'orchestrateur) avec un texte source éventuel. */
    @PostMapping(value = "/versions/{id}/agents/{code}/run", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> executer(@PathVariable long id, @PathVariable String code, @RequestBody(required = false) Demande d) {
        return agents.executer(id, code, d == null ? null : d.objective(), d == null ? null : d.text(), d == null ? null : d.sourceName());
    }

    /** Exécution avec une SFD jointe (Word .docx ou texte) : le Document Agent l'analyse. */
    @PostMapping(value = "/versions/{id}/agents/{code}/run", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> executerAvecFichier(@PathVariable long id, @PathVariable String code, @RequestParam(required = false) String objective,
                                                   @RequestParam("file") MultipartFile fichier) {
        String texte;
        try {
            texte = TexteSource.lire(fichier.getBytes(), fichier.getOriginalFilename()).complet();
        } catch (IOException | IllegalArgumentException e) {
            throw ProceduresException.invalide("Document illisible : " + e.getMessage());
        }
        return agents.executer(id, code, objective, texte, fichier.getOriginalFilename());
    }

    @GetMapping("/versions/{id}/runs")
    public List<AgentService.Execution> executionsVersion(@PathVariable long id) {
        return agents.executions(id, 200);
    }

    @GetMapping("/runs")
    public List<AgentService.Execution> executions(@RequestParam(defaultValue = "200") int limit) {
        return agents.executions(null, Math.min(limit, 1000));
    }

    @GetMapping("/versions/{id}/proposals")
    public List<AgentService.PropositionEnregistree> propositions(@PathVariable long id, @RequestParam(required = false) String status) {
        return agents.propositions(id, status == null || status.isBlank() ? null : status);
    }

    @PostMapping("/proposals/{id}/review")
    public AgentService.PropositionEnregistree revoir(@PathVariable long id, @RequestBody RevueService.Revue r) {
        return revues.revoir(id, r);
    }

    @PostMapping("/versions/{id}/proposals/accept-all")
    public Map<String, Object> accepterTout(@PathVariable long id, @RequestBody Map<String, Object> corps) {
        String kind = String.valueOf(corps.getOrDefault("kind", ""));
        double min = corps.get("minConfidence") instanceof Number n ? n.doubleValue() : 0.8;
        return revues.accepterTout(id, kind, min);
    }
}
