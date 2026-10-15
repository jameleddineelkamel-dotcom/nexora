import { Component, computed, inject, input, output, signal } from '@angular/core';
import { Api, Bie, CodeValue, Field, VersionComplete } from '../../core/api';
import { I18N } from '../../core/i18n';
import { erreurs } from '../../core/session';
import { Colonne, Grille } from '../../shared/grille';
import { Icon } from '../../shared/icon';
import { Enregistrement } from './general';
import { GabaritIhmComponent } from './ihm';

const TYPES = ['TEXT', 'LONGTEXT', 'NUMBER', 'DECIMAL', 'DATE', 'YEAR', 'CODE', 'EMAIL', 'PHONE', 'BOOLEAN', 'GEOLOCATION'];

/**
 * Formulaire et contenu des échanges : sections (dont groupes répétitifs) et champs reliés aux éléments de données
 * (UNTDED, modèle de l'OMD, DEN UN/CCL, chemin BIE du SCRDM) et aux listes du Référentiel Commun. L'assistant SCRDM
 * recherche un élément du modèle de référence et propose ses chemins depuis le message maître.
 */
@Component({
  selector: 'nx-onglet-formulaire',
  imports: [Icon, Grille, GabaritIhmComponent, ...I18N],
  template: `
    <nx-gabarit-ihm [v]="v()" [modifiable]="modifiable()" (enregistrer)="enregistrer.emit($event)" />
    <div class="card pad stack">
      <div class="card-title"><nx-icon name="layers" /><h2>{{ 'Sections et sous-sections' | t }}</h2></div>
      <nx-grille [colonnes]="colSections()" [donnees]="$any(v().sections)" [modele]="{ repeating: false, minRows: 0 }" [lectureSeule]="!modifiable()"
        (enregistrer)="enregistrer.emit({ partie: 'form', corps: { sections: $event.lignes, fields: v().fields }, motif: $event.motif })" />
    </div>
    <div class="card pad stack">
      <div class="between"><div class="card-title"><nx-icon name="file" /><h2>{{ 'Champs ({0})' | t: v().fields.length }}</h2></div>
        <span class="muted small">{{ '{0} champ(s) relié(s) au SCRDM' | t: avecBie() }}</span></div>
      <p class="muted small">{{ 'Listes : table REF_xxx du Référentiel Commun, « @LIEUX_TRAITEMENT » (lieux du Runtime) ou valeurs locales « code=libellé FR|libellé EN » séparées par des virgules. Pré-remplissage : attribut du registre des opérateurs.' | t }}</p>
      <nx-grille [colonnes]="colChamps()" [donnees]="champsEdition()" [modele]="{ dataType: 'TEXT', mandatory: 'OUI', amendable: true, searchable: false }"
        [lectureSeule]="!modifiable()" (enregistrer)="enregistrerChamps($event.lignes, $event.motif)" />
    </div>
    <div class="card pad stack">
      <div class="card-title"><nx-icon name="database" /><h2>{{ 'Assistant SCRDM D23B' | t }}</h2></div>
      <div class="row"><input class="input grow" [value]="q()" (input)="q.set($any($event.target).value)" (keydown.enter)="chercher()" [placeholder]="'Ex. city name, gross weight, consignor…' | t" />
        <button class="btn" (click)="chercher()"><nx-icon name="search" /> {{ 'Rechercher' | t }}</button></div>
      @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
      <div class="resultats">
        @for (b of resultats(); track b.abie + b.property) {
          <button class="bie" [class.on]="choisi() === b" (click)="choisir(b)"><b>{{ b.den }}</b><span class="muted small">{{ b.kind }} · {{ b.cardinality }} · {{ b.abie }}/{{ b.property }}</span></button>
        }
      </div>
      @if (choisi(); as b) {
        <div class="alert info small">{{ b.definition }}</div>
        <div class="stack">
          @for (p of chemins(); track p) {
            <div class="row chemin"><code class="grow">{{ p }}</code><button class="btn sm" (click)="copier(p)"><nx-icon name="link" [size]="14" /> {{ 'Copier' | t }}</button></div>
          } @empty { <div class="muted small">{{ 'Calcul des chemins…' | t }}</div> }
        </div>
        @if (copie()) { <div class="muted small">{{ 'Chemin copié : collez-le dans la colonne « Chemin BIE » du champ.' | t }}</div> }
      }
    </div>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 16px; }
    .resultats { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(320px, 100%), 1fr)); gap: 6px; max-height: 300px; overflow-y: auto; }
    .bie { display: flex; flex-direction: column; text-align: left; padding: 8px 10px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface); font: inherit; color: var(--text); cursor: pointer; }
    .bie.on { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
    .chemin code { padding: 6px 8px; background: var(--surface-2); border-radius: 8px; overflow-wrap: anywhere; }
  `],
})
export class OngletFormulaire {
  private readonly api = inject(Api);
  readonly v = input.required<VersionComplete>();
  readonly modifiable = input(false);
  readonly enregistrer = output<Enregistrement>();
  protected readonly q = signal('');
  protected readonly resultats = signal<Bie[]>([]);
  protected readonly choisi = signal<Bie | null>(null);
  protected readonly chemins = signal<string[]>([]);
  protected readonly copie = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly avecBie = computed(() => this.v().fields.filter(f => f.biePath).length);

  /** Sections et sous-sections : une section rattachée à une section de premier niveau s'affiche à l'intérieur de celle-ci. */
  protected readonly colSections = computed<Colonne[]>(() => [
    { cle: 'code', libelle: 'Code', mono: true, largeur: 140 }, { cle: 'labelFr', libelle: 'Libellé (FR)', largeur: 240 }, { cle: 'labelEn', libelle: 'Libellé (EN)', largeur: 200 },
    { cle: 'parentCode', libelle: 'Section parente', type: 'select', largeur: 150, aide: 'Vide : section de premier niveau ; sinon sous-section (un seul niveau).',
      options: this.v().sections.filter(s => !s.parentCode).map(s => ({ valeur: s.code, libelle: s.code })) },
    { cle: 'repeating', libelle: 'Répétitif', type: 'bool', largeur: 80 }, { cle: 'minRows', libelle: 'Lignes min.', type: 'number', largeur: 90 }]);

  protected readonly colChamps = computed<Colonne[]>(() => [
    { cle: 'code', libelle: 'Code', mono: true, largeur: 190 },
    { cle: 'sectionCode', libelle: 'Section', type: 'select', options: this.v().sections.map(s => ({ valeur: s.code, libelle: s.code })), largeur: 130 },
    { cle: 'labelFr', libelle: 'Libellé (FR)', largeur: 230 }, { cle: 'labelEn', libelle: 'Libellé (EN)', largeur: 190 },
    { cle: 'dataType', libelle: 'Type', type: 'select', options: TYPES.map(x => ({ valeur: x, libelle: x })), largeur: 120 },
    { cle: 'maxLength', libelle: 'Long. max', type: 'number', largeur: 80 }, { cle: 'pattern', libelle: 'Format (regex)', mono: true, largeur: 140 },
    { cle: 'mandatory', libelle: 'Obligatoire', type: 'select', options: ['OUI', 'NON', 'CONDITIONNEL'].map(x => ({ valeur: x, libelle: x })), largeur: 130 },
    { cle: 'conditionText', libelle: 'Condition', largeur: 200 }, { cle: 'refdataTable', libelle: 'Liste (REF_ / @)', mono: true, largeur: 170 },
    { cle: 'codeValues', libelle: 'Valeurs locales', type: 'list', largeur: 220 }, { cle: 'untdedRef', libelle: 'UNTDED', mono: true, largeur: 80 },
    { cle: 'wcoRef', libelle: 'Modèle OMD', largeur: 200 }, { cle: 'cclDen', libelle: 'DEN UN/CCL', largeur: 240 }, { cle: 'cclUid', libelle: 'UID CCL', mono: true, largeur: 110 },
    { cle: 'biePath', libelle: 'Chemin BIE (SCRDM)', mono: true, largeur: 300 }, { cle: 'searchable', libelle: 'Critère', type: 'bool', largeur: 70 },
    { cle: 'prefill', libelle: 'Pré-remplissage', mono: true, largeur: 140 }, { cle: 'amendable', libelle: 'Amendable', type: 'bool', largeur: 90 },
    { cle: 'helpFr', libelle: 'Aide', largeur: 220 }]);

  /** Valeurs locales présentées en texte « code=FR|EN ». */
  protected readonly champsEdition = computed(() => this.v().fields.map(f => ({
    ...f, codeValues: (f.codeValues ?? []).map(c => `${c.code}=${c.labelFr}${c.labelEn ? '|' + c.labelEn : ''}`) })) as unknown as Record<string, unknown>[]);

  protected enregistrerChamps(lignes: Record<string, unknown>[], motif: string): void {
    const champs = lignes.map(l => {
      const valeurs = (l['codeValues'] as string[] | undefined ?? []).map(t => {
        const [code, reste = ''] = t.split('=');
        const [fr, en] = reste.split('|');
        return { code: code.trim(), labelFr: (fr ?? code).trim(), labelEn: en?.trim() ?? null } as CodeValue;
      }).filter(c => c.code);
      return { ...l, codeValues: valeurs.length ? valeurs : null } as unknown as Field;
    });
    this.enregistrer.emit({ partie: 'form', corps: { sections: this.v().sections, fields: champs }, motif });
  }

  protected chercher(): void {
    if (!this.q().trim()) return;
    this.choisi.set(null);
    this.api.rechercherBie(this.q()).subscribe({ next: r => { this.resultats.set(r); this.erreur.set(null); }, error: e => this.erreur.set(erreurs(e).join(' ')) });
  }

  protected choisir(b: Bie): void {
    this.choisi.set(b);
    this.chemins.set([]);
    this.copie.set(false);
    this.api.abie(b.abie).subscribe({ next: r => this.chemins.set((r.paths.length ? r.paths : ['']).map(p => (p ? p + '/' : '') + b.property)) });
  }

  protected copier(p: string): void {
    navigator.clipboard?.writeText(p).then(() => this.copie.set(true), () => this.copie.set(false));
  }
}
