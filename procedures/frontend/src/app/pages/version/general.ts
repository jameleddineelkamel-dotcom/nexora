import { Component, computed, effect, input, output, signal } from '@angular/core';
import { Partie, VersionComplete } from '../../core/api';
import { I18N } from '../../core/i18n';
import { Colonne, Grille } from '../../shared/grille';
import { Icon } from '../../shared/icon';

export type Enregistrement = { partie: Partie; corps: unknown; motif: string };

const NATURES = 'INITIAL, RENEWAL, AMENDMENT, EXTENSION, CANCELLATION';

/** Réglages d'exécution, types de demande (codes CUR), acteurs et statuts du dossier. */
@Component({
  selector: 'nx-onglet-general',
  imports: [Icon, Grille, ...I18N],
  template: `
    <div class="card pad stack">
      <div class="card-title"><nx-icon name="gauge" /><h2>{{ 'Réglages d\\'exécution' | t }}</h2></div>
      <p class="muted small">{{ 'Lus par le Runtime : pays et fuseau du CUR, monnaie, délai de paiement (ISO 8601), délai de traitement, champs pivots, en-têtes et mention du livrable.' | t }}</p>
      <fieldset class="lecture" [disabled]="!modifiable()">
        <div class="form-grid">
          @for (r of reglages; track r.cle) {
            <div class="field" [class.full]="r.large"><label [attr.for]="'r-' + r.cle">{{ r.libelle | t }}</label>
              @if (r.large) { <textarea class="input" [id]="'r-' + r.cle" [value]="val(r.cle)" (input)="poser(r.cle, $any($event.target).value)"></textarea> }
              @else { <input class="input mono" [id]="'r-' + r.cle" [value]="val(r.cle)" (input)="poser(r.cle, $any($event.target).value)" /> }
              @if (r.aide) { <span class="hint">{{ r.aide | t }}</span> }</div>
          }
          <div class="field full"><label for="autres">{{ 'Autres réglages (JSON)' | t }}</label>
            <textarea id="autres" class="input code" style="min-height: 110px" [value]="autres()" (input)="autresSaisis.set($any($event.target).value)"></textarea>
            <span class="hint">{{ 'Ex. bieContext (contexte des chemins SCRDM par section), viewNames (noms des vues métier).' | t }}</span></div>
          <div class="field full"><label for="note">{{ 'Note de version' | t }}</label><textarea id="note" class="input" [value]="note()" (input)="note.set($any($event.target).value)"></textarea></div>
        </div>
      </fieldset>
      @if (erreurJson()) { <div class="alert err">{{ erreurJson() }}</div> }
      @if (modifiable()) { <div class="row"><button class="btn primary" (click)="enregistrerReglages()"><nx-icon name="save" /> {{ 'Enregistrer les réglages' | t }}</button></div> }
    </div>

    <div class="card pad stack">
      <div class="card-title"><nx-icon name="tag" /><h2>{{ 'Types de demande' | t }}</h2></div>
      <p class="muted small">{{ 'Chaque déclinaison a son code CUR (3 lettres) et ses natures autorisées : {0}.' | t: natures }}</p>
      <nx-grille [colonnes]="colTypes" [donnees]="$any(v().requestTypes)" [modele]="{ natures: ['INITIAL'] }" [lectureSeule]="!modifiable()"
        (enregistrer)="enregistrer.emit({ partie: 'requestTypes', corps: $event.lignes, motif: $event.motif })" />
    </div>
    <div class="card pad stack">
      <div class="card-title"><nx-icon name="users" /><h2>{{ 'Acteurs' | t }}</h2></div>
      <p class="muted small">{{ 'Rôles des organismes dans la procédure ; le rôle SSO relie l\\'acteur aux comptes qui l\\'incarnent.' | t }}</p>
      <nx-grille [colonnes]="colActeurs" [donnees]="$any(v().actors)" [modele]="{ kind: 'ADMINISTRATION', systemCode: 'EGOV' }" [lectureSeule]="!modifiable()"
        (enregistrer)="enregistrer.emit({ partie: 'actors', corps: $event.lignes, motif: $event.motif })" />
    </div>
    <div class="card pad stack">
      <div class="card-title"><nx-icon name="flag" /><h2>{{ 'Statuts du dossier' | t }}</h2></div>
      <nx-grille [colonnes]="colStatuts" [donnees]="$any(v().statuses)" [modele]="{ tone: 'info', isFinal: false }" [lectureSeule]="!modifiable()"
        (enregistrer)="enregistrer.emit({ partie: 'statuses', corps: $event.lignes, motif: $event.motif })" />
    </div>
  `,
  styles: [`:host { display: flex; flex-direction: column; gap: 16px; }`],
})
export class OngletGeneral {
  readonly v = input.required<VersionComplete>();
  readonly modifiable = input(false);
  readonly enregistrer = output<Enregistrement>();
  protected readonly natures = NATURES;
  protected readonly reglages = [
    { cle: 'country', libelle: 'Pays (CUR)', aide: 'ISO 3166-1, ex. CM' }, { cle: 'currency', libelle: 'Monnaie', aide: 'ISO 4217, ex. XAF' },
    { cle: 'timezone', libelle: 'Fuseau métier', aide: 'Remise à zéro du CUR au 1er janvier' }, { cle: 'paymentDelay', libelle: 'Délai de paiement', aide: 'ISO 8601, ex. P30D' },
    { cle: 'slaHours', libelle: 'Délai de traitement (heures)' }, { cle: 'cotation', libelle: 'Stratégie de cotation', aide: 'ALEATOIRE_EQUITABLE' },
    { cle: 'processingLocationField', libelle: 'Champ « lieu de traitement »' }, { cle: 'applicantField', libelle: 'Champ « NIU du demandeur »' },
    { cle: 'previousField', libelle: 'Champ « dossier source »' }, { cle: 'renewalYearField', libelle: 'Champ « année de renouvellement »' },
    { cle: 'registryUpdate', libelle: 'Mettre à jour le registre à la délivrance', aide: 'true / false (RG2 UC-05)' },
    { cle: 'documentHeaderFr', libelle: 'En-tête du livrable (FR)', aide: '« | » = retour à la ligne' }, { cle: 'documentHeaderEn', libelle: 'En-tête du livrable (EN)' },
    { cle: 'documentMention', libelle: 'Mention du livrable', large: true },
  ] as { cle: string; libelle: string; aide?: string; large?: boolean }[];
  private readonly connues = new Set(this.reglages.map(r => r.cle));
  protected readonly valeurs = signal<Record<string, unknown>>({});
  protected readonly autresSaisis = signal<string | null>(null);
  protected readonly note = signal('');
  protected readonly erreurJson = signal<string | null>(null);
  protected readonly autres = computed(() => this.autresSaisis() ?? JSON.stringify(Object.fromEntries(Object.entries(this.v().version.settings)
    .filter(([k]) => !this.connues.has(k))), null, 2));

  protected readonly colTypes: Colonne[] = [
    { cle: 'code', libelle: 'Code', mono: true, largeur: 90 }, { cle: 'curCode', libelle: 'Code CUR', mono: true, largeur: 80 },
    { cle: 'labelFr', libelle: 'Libellé (FR)', largeur: 260 }, { cle: 'labelEn', libelle: 'Libellé (EN)', largeur: 220 },
    { cle: 'natures', libelle: 'Natures', type: 'list', mono: true, largeur: 240 }, { cle: 'description', libelle: 'Description', largeur: 220 }];
  protected readonly colActeurs: Colonne[] = [
    { cle: 'code', libelle: 'Code', mono: true, largeur: 140 }, { cle: 'labelFr', libelle: 'Libellé (FR)', largeur: 230 }, { cle: 'labelEn', libelle: 'Libellé (EN)', largeur: 180 },
    { cle: 'kind', libelle: 'Nature', type: 'select', options: ['DEMANDEUR', 'ADMINISTRATION', 'PARTENAIRE', 'BANQUE', 'SYSTEME'].map(x => ({ valeur: x, libelle: x })) },
    { cle: 'systemCode', libelle: 'Système', type: 'select', options: ['EFORCE', 'EGOV', 'EPAYMENT', 'EXTERNE'].map(x => ({ valeur: x, libelle: x })) },
    { cle: 'authRole', libelle: 'Rôle SSO', mono: true, largeur: 170 }, { cle: 'responsibilities', libelle: 'Responsabilités', type: 'textarea', largeur: 260 }];
  protected readonly colStatuts: Colonne[] = [
    { cle: 'code', libelle: 'Code', mono: true, largeur: 190 }, { cle: 'labelFr', libelle: 'Libellé (FR)', largeur: 230 }, { cle: 'labelEn', libelle: 'Libellé (EN)', largeur: 200 },
    { cle: 'isFinal', libelle: 'Final', type: 'bool', largeur: 60 },
    { cle: 'tone', libelle: 'Couleur', type: 'select', options: ['neutral', 'info', 'warn', 'ok', 'danger'].map(x => ({ valeur: x, libelle: x })) }];

  constructor() {
    effect(() => { this.valeurs.set({ ...this.v().version.settings }); this.note.set(this.v().version.changeNote ?? ''); this.autresSaisis.set(null); });
  }

  protected val(cle: string): string {
    const v = this.valeurs()[cle];
    return v === null || v === undefined ? '' : String(v);
  }

  protected poser(cle: string, v: string): void {
    this.valeurs.update(x => ({ ...x, [cle]: v }));
  }

  protected enregistrerReglages(): void {
    let autres: Record<string, unknown>;
    try {
      autres = JSON.parse(this.autres() || '{}');
    } catch {
      this.erreurJson.set('JSON invalide dans « Autres réglages ».');
      return;
    }
    this.erreurJson.set(null);
    const s: Record<string, unknown> = { ...autres };
    for (const r of this.reglages) {
      const v = this.val(r.cle).trim();
      if (!v) continue;
      s[r.cle] = r.cle === 'slaHours' ? Number(v) : r.cle === 'registryUpdate' ? v === 'true' : v;
    }
    this.enregistrer.emit({ partie: 'settings', corps: { settings: s, changeNote: this.note() }, motif: 'Réglages de la version' });
  }
}
