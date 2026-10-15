import { Component, computed, effect, input, output, signal } from '@angular/core';
import { Section, VersionComplete } from '../../core/api';
import { I18N } from '../../core/i18n';
import { Icon } from '../../shared/icon';
import { Enregistrement } from './general';

/** Gabarit d'IHM du formulaire (réglage « ihm » de la version), appliqué par le Runtime e-Services. */
export interface GabaritIhm {
  disposition: 'CONTINU' | 'ONGLETS' | 'ETAPES';
  colonnes: number;
  densite: 'COMPACTE' | 'NORMALE' | 'AEREE';
  libelles: 'DESSUS' | 'COTE';
  navigation: boolean;
  resume: boolean;
  repliables: boolean;
}

export const GABARIT_DEFAUT: GabaritIhm = { disposition: 'CONTINU', colonnes: 3, densite: 'COMPACTE', libelles: 'DESSUS', navigation: true, resume: true, repliables: false };

const MODELES: { code: string; libelle: string; aide: string; g: GabaritIhm }[] = [
  { code: 'GUCE', libelle: 'Formulaire GUCE compact', aide: 'Sections à la suite, 3 colonnes, pastilles de navigation', g: GABARIT_DEFAUT },
  { code: 'ASSISTANT', libelle: 'Assistant pas à pas', aide: 'Une section par étape, Précédent / Suivant',
    g: { disposition: 'ETAPES', colonnes: 2, densite: 'NORMALE', libelles: 'DESSUS', navigation: true, resume: false, repliables: false } },
  { code: 'ONGLETS', libelle: 'Onglets', aide: 'Une section de premier niveau par onglet',
    g: { disposition: 'ONGLETS', colonnes: 3, densite: 'COMPACTE', libelles: 'DESSUS', navigation: false, resume: true, repliables: false } },
  { code: 'DENSE', libelle: 'Saisie dense (agents)', aide: '4 colonnes, libellés à gauche, sections repliables',
    g: { disposition: 'CONTINU', colonnes: 4, densite: 'COMPACTE', libelles: 'COTE', navigation: true, resume: false, repliables: true } },
];

/** Lecture tolérante du réglage « ihm » : valeurs inconnues remplacées par celles du gabarit par défaut. */
export function gabarit(x: unknown): GabaritIhm {
  const o = (x && typeof x === 'object' ? x : {}) as Record<string, unknown>;
  const un = <T extends string>(v: unknown, l: readonly T[], d: T): T => (l.includes(v as T) ? (v as T) : d);
  const n = Number(o['colonnes']);
  return {
    disposition: un(o['disposition'], ['CONTINU', 'ONGLETS', 'ETAPES'] as const, GABARIT_DEFAUT.disposition),
    colonnes: n >= 1 && n <= 4 ? Math.round(n) : GABARIT_DEFAUT.colonnes,
    densite: un(o['densite'], ['COMPACTE', 'NORMALE', 'AEREE'] as const, GABARIT_DEFAUT.densite),
    libelles: un(o['libelles'], ['DESSUS', 'COTE'] as const, GABARIT_DEFAUT.libelles),
    navigation: typeof o['navigation'] === 'boolean' ? o['navigation'] : GABARIT_DEFAUT.navigation,
    resume: typeof o['resume'] === 'boolean' ? o['resume'] : GABARIT_DEFAUT.resume,
    repliables: typeof o['repliables'] === 'boolean' ? o['repliables'] : GABARIT_DEFAUT.repliables,
  };
}

/**
 * Gabarit d'IHM : modèle de disposition du formulaire de demande (continu, onglets ou assistant par étapes), colonnes,
 * densité, position des libellés. Enregistré dans le réglage « ihm » de la version ; importable / exportable en JSON.
 */
@Component({
  selector: 'nx-gabarit-ihm',
  imports: [Icon, ...I18N],
  template: `
    <div class="card pad stack">
      <div class="between">
        <div class="card-title"><nx-icon name="grid" /><h2>{{ 'Gabarit d\\'IHM' | t }}</h2></div>
        <div class="row">
          <button class="btn sm" (click)="exporter()"><nx-icon name="download" [size]="15" /> JSON</button>
          @if (modifiable()) {
            <label class="btn sm"><nx-icon name="upload" [size]="15" /> {{ 'Importer' | t }}<input type="file" accept=".json,application/json" hidden (change)="importer($event)" /></label>
            <button class="btn primary sm" (click)="enregistrerGabarit()" [disabled]="!modifie()"><nx-icon name="save" [size]="15" /> {{ 'Enregistrer' | t }}</button>
          }
        </div>
      </div>
      <p class="muted small">{{ 'Disposition du formulaire de demande dans e-Services. Les sous-sections s\\'affichent à l\\'intérieur de leur section parente.' | t }}</p>
      <div class="modeles">
        @for (m of modeles; track m.code) {
          <button class="modele" [class.on]="identique(m.g)" [disabled]="!modifiable()" (click)="g.set(m.g)"><b>{{ m.libelle | t }}</b><span class="muted small">{{ m.aide | t }}</span></button>
        }
      </div>
      <fieldset class="lecture" [disabled]="!modifiable()">
        <div class="form-grid">
          <div class="field"><label for="ih-d">{{ 'Disposition' | t }}</label>
            <select id="ih-d" class="input" (change)="poser('disposition', $any($event.target).value)">
              @for (o of [['CONTINU', 'Continue (sections à la suite)'], ['ONGLETS', 'Onglets'], ['ETAPES', 'Assistant par étapes']]; track o[0]) {
                <option [value]="o[0]" [selected]="g().disposition === o[0]">{{ o[1] | t }}</option> }
            </select></div>
          <div class="field"><label for="ih-c">{{ 'Colonnes' | t }}</label>
            <select id="ih-c" class="input" (change)="poser('colonnes', +$any($event.target).value)">
              @for (n of [1, 2, 3, 4]; track n) { <option [value]="n" [selected]="g().colonnes === n">{{ n }}</option> }
            </select></div>
          <div class="field"><label for="ih-n">{{ 'Densité' | t }}</label>
            <select id="ih-n" class="input" (change)="poser('densite', $any($event.target).value)">
              @for (o of [['COMPACTE', 'Compacte'], ['NORMALE', 'Normale'], ['AEREE', 'Aérée']]; track o[0]) {
                <option [value]="o[0]" [selected]="g().densite === o[0]">{{ o[1] | t }}</option> }
            </select></div>
          <div class="field"><label for="ih-l">{{ 'Libellés' | t }}</label>
            <select id="ih-l" class="input" (change)="poser('libelles', $any($event.target).value)">
              @for (o of [['DESSUS', 'Au-dessus du champ'], ['COTE', 'À gauche du champ']]; track o[0]) {
                <option [value]="o[0]" [selected]="g().libelles === o[0]">{{ o[1] | t }}</option> }
            </select></div>
          <div class="field">
            <label class="check"><input type="checkbox" [checked]="g().navigation" (change)="poser('navigation', $any($event.target).checked)" /> {{ 'Pastilles de navigation' | t }}</label>
            <label class="check"><input type="checkbox" [checked]="g().resume" (change)="poser('resume', $any($event.target).checked)" /> {{ 'Suivi à droite du formulaire (sinon dessous)' | t }}</label>
            <label class="check"><input type="checkbox" [checked]="g().repliables" (change)="poser('repliables', $any($event.target).checked)" /> {{ 'Sections repliables' | t }}</label>
          </div>
        </div>
      </fieldset>
      @if (erreur()) { <div class="alert err small">{{ erreur() }}</div> }
      <div class="apercu" [attr.data-disposition]="g().disposition">
        <div class="muted small">{{ 'Aperçu' | t }} · {{ arbre().length }} {{ 'section(s)' | t }}</div>
        @if (g().disposition !== 'CONTINU') {
          <div class="row onglets">@for (s of arbre(); track s.s.code; let i = $index) { <span class="chip" [class.accent]="i === 0">{{ g().disposition === 'ETAPES' ? (i + 1) + '. ' : '' }}{{ s.s.labelFr }}</span> }</div>
        }
        @for (s of g().disposition === 'CONTINU' ? arbre() : arbre().slice(0, 1); track s.s.code) {
          <div class="sec">
            <div class="titre">{{ s.s.labelFr }} <span class="muted small">· {{ nb(s.s.code) }} {{ 'champ(s)' | t }}</span></div>
            <div class="cases" [style.grid-template-columns]="'repeat(' + g().colonnes + ', 1fr)'">
              @for (c of cases(s.s.code); track $index) { <span class="case"></span> }
            </div>
            @for (e of s.enfants; track e.code) {
              <div class="sous">
                <div class="titre small">{{ e.labelFr }} <span class="muted">· {{ nb(e.code) }} {{ 'champ(s)' | t }}</span></div>
                <div class="cases" [style.grid-template-columns]="'repeat(' + g().colonnes + ', 1fr)'">
                  @for (c of cases(e.code); track $index) { <span class="case"></span> }
                </div>
              </div>
            }
          </div>
        }
      </div>
    </div>
  `,
  styles: [`
    .modeles { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(200px, 100%), 1fr)); gap: 8px; }
    .modele { display: flex; flex-direction: column; gap: 2px; text-align: left; padding: 8px 10px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface); color: var(--text); font: inherit; cursor: pointer; }
    .modele.on { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
    .apercu { display: flex; flex-direction: column; gap: 8px; padding: 10px; border: 1px dashed var(--line); border-radius: 12px; background: var(--surface-2); }
    .onglets { gap: 6px; flex-wrap: wrap; }
    .sec { border: 1px solid var(--line); border-radius: 10px; padding: 8px; background: var(--surface); display: flex; flex-direction: column; gap: 6px; }
    .sous { margin-left: 12px; border-left: 3px solid var(--accent-soft); padding-left: 8px; display: flex; flex-direction: column; gap: 4px; }
    .titre { font-weight: 600; font-size: 13px; }
    .cases { display: grid; gap: 4px; }
    .case { height: 10px; border-radius: 4px; background: var(--chip); }
  `],
})
export class GabaritIhmComponent {
  readonly v = input.required<VersionComplete>();
  readonly modifiable = input(false);
  readonly enregistrer = output<Enregistrement>();
  protected readonly modeles = MODELES;
  protected readonly g = signal<GabaritIhm>(GABARIT_DEFAUT);
  protected readonly erreur = signal<string | null>(null);
  private readonly initial = computed(() => gabarit(this.v().version.settings['ihm']));
  protected readonly modifie = computed(() => JSON.stringify(this.g()) !== JSON.stringify(this.initial()));
  /** Sections de premier niveau et leurs sous-sections. */
  protected readonly arbre = computed(() => {
    const l = [...this.v().sections].sort((a, b) => a.sortOrder - b.sortOrder);
    const codes = new Set(l.map(s => s.code));
    return l.filter(s => !s.parentCode || !codes.has(s.parentCode))
      .map(s => ({ s, enfants: l.filter(e => e.parentCode === s.code) as Section[] }));
  });

  constructor() {
    effect(() => this.g.set(this.initial()));
  }

  protected nb(section: string): number {
    return this.v().fields.filter(f => f.sectionCode === section).length;
  }

  protected cases(section: string): number[] {
    return Array.from({ length: Math.min(this.nb(section), 8) }, (_, i) => i);
  }

  protected identique(m: GabaritIhm): boolean {
    return JSON.stringify(m) === JSON.stringify(this.g());
  }

  protected poser<K extends keyof GabaritIhm>(k: K, val: GabaritIhm[K]): void {
    this.g.update(x => ({ ...x, [k]: val }));
  }

  protected enregistrerGabarit(): void {
    const v = this.v().version;
    this.enregistrer.emit({ partie: 'settings', corps: { settings: { ...v.settings, ihm: this.g() }, changeNote: v.changeNote }, motif: 'Gabarit d\'IHM du formulaire' });
  }

  protected exporter(): void {
    const blob = new Blob([JSON.stringify({ format: 'nexora.ihm/1', ihm: this.g() }, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `${this.v().procedure.code.toLowerCase()}-ihm.json`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  }

  protected importer(e: Event): void {
    const f = (e.target as HTMLInputElement).files?.[0];
    (e.target as HTMLInputElement).value = '';
    if (!f) return;
    f.text().then(t => {
      try {
        const o = JSON.parse(t);
        this.g.set(gabarit(o?.ihm ?? o));
        this.erreur.set(null);
      } catch {
        this.erreur.set('Fichier de gabarit invalide (JSON attendu).');
      }
    });
  }
}
