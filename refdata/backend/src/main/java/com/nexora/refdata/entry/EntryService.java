package com.nexora.refdata.entry;

import com.nexora.refdata.audit.AuditContext;
import com.nexora.refdata.catalogue.CatalogueRepository;
import com.nexora.refdata.catalogue.CatalogueModel.TableDef;
import com.nexora.refdata.catalogue.CatalogueService;
import com.nexora.refdata.entry.Entry.LookupItem;
import com.nexora.refdata.entry.Entry.Page;
import com.nexora.refdata.web.RefdataException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class EntryService {

    private static final Set<String> LANGUES = Set.of("fr", "en");

    private final EntryRepository repo;
    private final CatalogueService catalogue;
    private final CatalogueRepository catalogueRepo;
    private final AuditContext audit;

    public EntryService(EntryRepository repo, CatalogueService catalogue, CatalogueRepository catalogueRepo, AuditContext audit) {
        this.repo = repo;
        this.catalogue = catalogue;
        this.catalogueRepo = catalogueRepo;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<Entry> page(String table, EntryRepository.Filtre filtre, int page, int taille, String tri, boolean desc) {
        TableDef t = catalogue.table(table);
        return repo.page(id(t), filtre, Math.max(page, 0), Math.clamp(taille, 1, 500), tri, desc);
    }

    @Transactional(readOnly = true)
    public Entry lire(String table, String code) {
        TableDef t = catalogue.table(table);
        return repo.lire(id(t), code).orElseThrow(() -> RefdataException.introuvable("Code « " + code + " » introuvable dans " + t.code() + "."));
    }

    @Transactional
    public Entry creer(String table, Entry saisie) {
        TableDef t = modifiable(table);
        EntryValidator.Resultat r = EntryValidator.valider(t, saisie, repo::existe);
        if (!r.valide()) throw RefdataException.invalide(r.erreurs());
        repo.lire(id(t), r.entree().code()).ifPresent(e -> {
            throw RefdataException.conflit("Le code « " + e.code() + " » existe déjà dans " + t.code()
                    + ("INVALID".equals(e.status()) ? " (invalidé : réactivez-le plutôt que de le recréer)." : "."));
        });
        audit.appliquer();
        repo.inserer(id(t), r.entree());
        return lire(t.code(), r.entree().code());
    }

    /** Modification d'un code existant ; le code lui-même est l'identifiant et ne change pas. */
    @Transactional
    public Entry modifier(String table, String code, Entry saisie) {
        TableDef t = modifiable(table);
        Entry actuel = lire(t.code(), code);
        Entry demande = new Entry(actuel.code(), saisie.labelFr(), saisie.labelEn(), saisie.parentCode(), saisie.attributes(),
                saisie.validFrom(), saisie.validTo(), saisie.status() == null ? actuel.status() : saisie.status(), saisie.version(),
                null, null, null);
        EntryValidator.Resultat r = EntryValidator.valider(t, demande, repo::existe);
        if (!r.valide()) throw RefdataException.invalide(r.erreurs());
        audit.appliquer();
        if (repo.modifier(id(t), r.entree(), saisie.version()) == 0)
            throw RefdataException.conflit("Le code « " + code + " » a été modifié par quelqu'un d'autre entre-temps. Rechargez la page.");
        return lire(t.code(), code);
    }

    /** Invalidation : le code reste dans le référentiel, avec une fin de validité (pas de suppression). */
    @Transactional
    public Entry invalider(String table, String code, LocalDate date, String motif) {
        TableDef t = modifiable(table);
        Entry e = lire(t.code(), code);
        LocalDate fin = date == null ? LocalDate.now() : date;
        if (e.validFrom() != null && fin.isBefore(e.validFrom())) throw RefdataException.invalide("La date d'invalidation précède le début de validité.");
        audit.appliquer(null, motif, null);
        repo.modifier(id(t), new Entry(e.code(), e.labelFr(), e.labelEn(), e.parentCode(), e.attributes(), e.validFrom(), fin,
                "INVALID", null, null, null, null), null);
        return lire(t.code(), code);
    }

    @Transactional
    public Entry reactiver(String table, String code, String motif) {
        TableDef t = modifiable(table);
        Entry e = lire(t.code(), code);
        audit.appliquer(null, motif, null);
        repo.modifier(id(t), new Entry(e.code(), e.labelFr(), e.labelEn(), e.parentCode(), e.attributes(), e.validFrom(), null,
                "ACTIVE", null, null, null, null), null);
        return lire(t.code(), code);
    }

    // ---------- Services de requête pour les autres composants (interopérabilité) ----------
    @Transactional(readOnly = true)
    public List<LookupItem> lookup(String table, String langue, LocalDate date, String q, String parent, int limite) {
        TableDef t = consultable(table);
        return repo.lookup(id(t), anglais(langue), date == null ? LocalDate.now() : date, q, parent, Math.clamp(limite, 1, 5000));
    }

    @Transactional(readOnly = true)
    public LookupItem resoudre(String table, String code, String langue, LocalDate date) {
        TableDef t = consultable(table);
        boolean en = anglais(langue);
        Entry e = repo.lire(id(t), code).orElseThrow(() -> RefdataException.introuvable("Code « " + code + " » inconnu dans " + t.code() + "."));
        LocalDate d = date == null ? LocalDate.now() : date;
        boolean valide = "ACTIVE".equals(e.status()) && (e.validFrom() == null || !e.validFrom().isAfter(d)) && (e.validTo() == null || !e.validTo().isBefore(d));
        if (!valide) throw RefdataException.introuvable("Le code « " + code + " » n'est pas valide au " + d + " dans " + t.code() + ".");
        String libelle = en && e.labelEn() != null ? e.labelEn() : e.labelFr() != null ? e.labelFr() : e.code();
        return new LookupItem(e.code(), libelle, e.parentCode());
    }

    private static boolean anglais(String langue) {
        String l = langue == null ? "fr" : langue.toLowerCase(Locale.ROOT);
        if (!LANGUES.contains(l)) throw RefdataException.invalide("Langue « " + langue + " » non prise en charge : fr ou en.");
        return "en".equals(l);
    }

    private TableDef consultable(String table) {
        TableDef t = catalogue.table(table);
        if ("ARCHIVED".equals(t.status())) throw RefdataException.introuvable("La table « " + t.code() + " » est archivée.");
        return t;
    }

    private TableDef modifiable(String table) {
        TableDef t = catalogue.table(table);
        if ("ARCHIVED".equals(t.status())) throw RefdataException.conflit("La table « " + t.code() + " » est archivée : réactivez-la pour la modifier.");
        return t;
    }

    long id(TableDef t) {
        return catalogueRepo.idDe(t.code());
    }
}
