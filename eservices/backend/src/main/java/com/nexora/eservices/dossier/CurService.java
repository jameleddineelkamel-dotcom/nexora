package com.nexora.eservices.dossier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Code Unique de Référence (CUR-GUCE, dossier v4.2 §10.10 ; SFD FIMEX RG6) : PAYS(2) ANNÉE(4) CODE PROCÉDURE(3) SÉQUENCE(6),
 * ex. CM2026IFI000001. Incrément atomique par (pays, année, code) ; l'année est calculée dans le fuseau métier, la
 * séquence repart à 000001 au 1er janvier (AC-CUR-01, AC-CUR-02). Un CUR n'est jamais réattribué (RG-CUR-01).
 */
@Service
public class CurService {

    private final JdbcClient jdbc;

    public CurService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String prochain(String pays, String codeProcedure, String fuseau) {
        int annee = ZonedDateTime.now(ZoneId.of(fuseau == null ? "Africa/Douala" : fuseau)).getYear();
        int valeur = jdbc.sql("""
                insert into wfe.cur_sequence (country_code, cur_year, op_code, last_value) values (:p, :a, :c, 1)
                on conflict (country_code, cur_year, op_code) do update set last_value = wfe.cur_sequence.last_value + 1
                returning last_value
                """).param("p", pays).param("a", annee).param("c", codeProcedure).query(Integer.class).single();
        String cur = pays + annee + codeProcedure + String.format("%06d", valeur);
        if (!cur.matches("^[A-Z0-9]{14,35}$")) throw new IllegalStateException("CUR hors grammaire (RG-CUR-03) : " + cur);
        return cur;
    }
}
