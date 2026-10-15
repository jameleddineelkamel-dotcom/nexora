package com.nexora.procedures.rdm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Découpage des noms normalisés (DEN du SCRDM, noms UNTDED) en classe d'objet (sans qualifiant « X_ »), propriété et représentation. */
class DenTest {

    @Test
    void decoupeLesDenScrdmEtUntded() {
        assertArrayEquals(new String[]{"Consignment", "Gross Weight", "Measure"}, RdmService.decouperDen("Supply Chain_ Consignment. Gross Weight. Measure"));
        assertArrayEquals(new String[]{"Goods Item", "Gross Weight", "Measure"}, RdmService.decouperDen("Goods Item. Gross Weight.Measure"));
        assertArrayEquals(new String[]{"Document", "", "Identifier"}, RdmService.decouperDen("Document. Identifier"));
        assertArrayEquals(new String[]{"Party", "Name", "Text"}, RdmService.decouperDen("Trade_ Party. Name. Text"));
        assertArrayEquals(new String[]{"Document", "Issue", "Date Time"}, RdmService.decouperDen("Exchanged_ Document. Issue. Date Time"));
    }

    /** Restrictions contextuelles : le chemin exclu est relatif à l'ABIE qui le porte, à n'importe quelle profondeur. */
    @Test
    void appliqueLesRestrictionsContextuelles() {
        Map<String, Set<String>> ex = Map.of("logisticsPackage", Set.of("includedTradeLineItem/barcodeId"),
                "scrdmMaster", Set.of("tradeTransaction/includedTradeLineItem/physicalLogisticsPackage"));
        String[] exclu = "specifiedConsignment/transportLogisticsPackage/includedTradeLineItem/barcodeId".split("/");
        assertTrue(RdmService.exclu(List.of("scrdmMaster", "consignment", "logisticsPackage", "tradeLineItem"), exclu, ex));
        String[] admis = "specifiedConsignment/transportLogisticsPackage/includedTradeLineItem/id".split("/");
        assertFalse(RdmService.exclu(List.of("scrdmMaster", "consignment", "logisticsPackage", "tradeLineItem"), admis, ex));
        // même propriété hors du contexte logisticsPackage : admise
        String[] ailleurs = "tradeTransaction/includedTradeLineItem/barcodeId".split("/");
        assertFalse(RdmService.exclu(List.of("scrdmMaster", "supplyChainTradeTransaction", "tradeLineItem"), ailleurs, ex));
        // restriction posée par le message maître, et tout ce qui est en dessous
        String[] racine = "tradeTransaction/includedTradeLineItem/physicalLogisticsPackage/id".split("/");
        assertTrue(RdmService.exclu(List.of("scrdmMaster", "supplyChainTradeTransaction", "tradeLineItem", "logisticsPackage"), racine, ex));
    }
}
