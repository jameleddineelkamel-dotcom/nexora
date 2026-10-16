package com.nexora.eservices.stockage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Stockage objet : repli sur la base sans adresse MinIO ; client MinIO (OkHttp, Okio, Kotlin) chargeable et erreurs explicites. */
class StockageObjetTest {

    @Test
    void sansAdresseLeStockageResteEnBase() {
        StockageObjet s = new StockageObjet("", "", "", "nexora-eservices");
        assertFalse(s.actif());
        assertThrows(IllegalStateException.class, () -> s.lire("pieces/x"));
    }

    @Test
    void minioInjoignableDonneUneErreurExplicite() {
        // Port 1 : connexion refusée immédiatement ; le dépôt traverse toute la pile HTTP du client MinIO
        StockageObjet s = new StockageObjet("http://127.0.0.1:1", "cle", "secret", "nexora-test");
        assertTrue(s.actif());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.deposer("pieces/essai", new byte[] {1, 2, 3}, "text/plain"));
        assertTrue(e.getMessage().startsWith("Dépôt impossible dans le stockage objet"), e.getMessage());
    }
}
