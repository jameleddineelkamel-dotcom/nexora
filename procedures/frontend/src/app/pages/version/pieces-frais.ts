import { Component, computed, input, output } from '@angular/core';
import { VersionComplete } from '../../core/api';
import { I18N } from '../../core/i18n';
import { Colonne, Grille } from '../../shared/grille';
import { Icon } from '../../shared/icon';
import { Enregistrement } from './general';

/** Pièces à fournir (avec conditions traduites en règles), frais par bénéficiaire et livrables. */
@Component({
  selector: 'nx-onglet-pieces-frais',
  imports: [Icon, Grille, ...I18N],
  template: `
    <div class="card pad stack">
      <div class="card-title"><nx-icon name="archive" /><h2>{{ 'Pièces à fournir' | t }}</h2></div>
      <p class="muted small">{{ 'Une condition simple « champ == \\'valeur\\' » (combinable avec &&) est traduite en règle DRL par le Rules Agent. Types de demande : vide = tous.' | t }}</p>
      <nx-grille [colonnes]="colPieces" [donnees]="$any(v().documents)" [modele]="{ mandatory: true, requestTypes: [] }" [lectureSeule]="!modifiable()"
        (enregistrer)="enregistrer.emit({ partie: 'documents', corps: $event.lignes, motif: $event.motif })" />
    </div>
    <div class="card pad stack">
      <div class="card-title"><nx-icon name="wallet" /><h2>{{ 'Frais et répartition' | t }}</h2></div>
      <p class="muted small">{{ 'Le Rules Agent génère l\\'agenda-group DRL « frais » à partir de ce tableau ; le montant total détermine si un paiement est requis.' | t }}</p>
      <nx-grille [colonnes]="colFrais()" [donnees]="$any(v().fees)" [modele]="{ currency: 'XAF', amount: 0, requestTypes: [], natures: ['INITIAL'] }" [lectureSeule]="!modifiable()"
        (enregistrer)="enregistrer.emit({ partie: 'fees', corps: $event.lignes, motif: $event.motif })" />
    </div>
    <div class="card pad stack">
      <div class="card-title"><nx-icon name="shield" /><h2>{{ 'Livrables' | t }}</h2></div>
      <p class="muted small">{{ 'Numéro = CUR du dossier + code document (+ rang d\\'amendement). Validité : FIN_ANNEE (année budgétaire), UN_AN, AUCUNE.' | t }}</p>
      <nx-grille [colonnes]="colLivrables" [donnees]="$any(v().deliverables)" [modele]="{ validityRule: 'FIN_ANNEE', bodyFields: [] }" [lectureSeule]="!modifiable()"
        (enregistrer)="enregistrer.emit({ partie: 'deliverables', corps: $event.lignes, motif: $event.motif })" />
    </div>
  `,
  styles: [`:host { display: flex; flex-direction: column; gap: 16px; }`],
})
export class OngletPiecesFrais {
  readonly v = input.required<VersionComplete>();
  readonly modifiable = input(false);
  readonly enregistrer = output<Enregistrement>();
  protected readonly colPieces: Colonne[] = [
    { cle: 'code', libelle: 'Code', mono: true, largeur: 70 }, { cle: 'labelFr', libelle: 'Libellé (FR)', largeur: 300 }, { cle: 'labelEn', libelle: 'Libellé (EN)', largeur: 220 },
    { cle: 'mandatory', libelle: 'Obligatoire', type: 'bool', largeur: 90 }, { cle: 'conditionText', libelle: 'Condition', mono: true, largeur: 220 },
    { cle: 'requestTypes', libelle: 'Types de demande', type: 'list', mono: true, largeur: 140 }, { cle: 'observation', libelle: 'Observation', largeur: 240 }];
  protected readonly colFrais = computed<Colonne[]>(() => [
    { cle: 'code', libelle: 'Code', mono: true, largeur: 130 },
    { cle: 'beneficiary', libelle: 'Bénéficiaire', type: 'select', options: this.v().actors.map(a => ({ valeur: a.code, libelle: a.code })), largeur: 150 },
    { cle: 'labelFr', libelle: 'Libellé (FR)', largeur: 280 }, { cle: 'amount', libelle: 'Montant', type: 'number', largeur: 110 }, { cle: 'currency', libelle: 'Monnaie', mono: true, largeur: 80 },
    { cle: 'requestTypes', libelle: 'Types de demande', type: 'list', mono: true, largeur: 150 }, { cle: 'natures', libelle: 'Natures', type: 'list', mono: true, largeur: 170 }]);
  protected readonly colLivrables: Colonne[] = [
    { cle: 'code', libelle: 'Code', mono: true, largeur: 130 }, { cle: 'labelFr', libelle: 'Libellé (FR)', largeur: 260 }, { cle: 'docCode', libelle: 'Code document', mono: true, largeur: 100 },
    { cle: 'validityRule', libelle: 'Validité', type: 'select', options: ['FIN_ANNEE', 'UN_AN', 'AUCUNE'].map(x => ({ valeur: x, libelle: x })), largeur: 130 },
    { cle: 'titleFr', libelle: 'Titre imprimé', largeur: 280 }, { cle: 'bodyFields', libelle: 'Champs reportés', type: 'list', mono: true, largeur: 280 },
    { cle: 'signatureNote', libelle: 'Mention de signature', largeur: 240 }];
}
