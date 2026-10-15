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
  /** Rendu des sous-sections : blocs dans la section parente, ou accordéons (en-tête cliquable). */
  sousSections: 'BLOCS' | 'ACCORDEONS';
  /** Accordéons ouverts à l'affichage : aucun, le premier de chaque section, ou tous. */
  accordeons: 'AUCUN' | 'PREMIER' | 'TOUS';
}

export const GABARIT_DEFAUT: GabaritIhm = { disposition: 'CONTINU', colonnes: 3, densite: 'COMPACTE', libelles: 'DESSUS', navigation: true, resume: true, repliables: false,
  sousSections: 'BLOCS', accordeons: 'AUCUN' };

const MODELES: { code: string; libelle: string; aide: string; g: GabaritIhm }[] = [
  { code: 'GUCE', libelle: 'Formulaire GUCE compact', aide: 'Sections à la suite, 3 colonnes, pastilles de navigation', g: GABARIT_DEFAUT },
  { code: 'ASSISTANT', libelle: 'Assistant pas à pas', aide: 'Une section par étape, Précédent / Suivant',
    g: { ...GABARIT_DEFAUT, disposition: 'ETAPES', colonnes: 2, densite: 'NORMALE', resume: false } },
  { code: 'ONGLETS', libelle: 'Onglets', aide: 'Une section de premier niveau par onglet',
    g: { ...GABARIT_DEFAUT, disposition: 'ONGLETS', navigation: false } },
  { code: 'ONGLETS_ACCORDEONS', libelle: 'Onglets et accordéons', aide: 'Sections en onglets, sous-sections en accordéons (type déclaration EUCDM)',
    g: { ...GABARIT_DEFAUT, disposition: 'ONGLETS', navigation: false, resume: false, sousSections: 'ACCORDEONS' } },
  { code: 'DENSE', libelle: 'Saisie dense (agents)', aide: '4 colonnes, libellés à gauche, sections repliables',
    g: { ...GABARIT_DEFAUT, colonnes: 4, libelles: 'COTE', resume: false, repliables: true } },
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
    sousSections: un(o['sousSections'], ['BLOCS', 'ACCORDEONS'] as const, GABARIT_DEFAUT.sousSections),
    accordeons: un(o['accordeons'], ['AUCUN', 'PREMIER', 'TOUS'] as const, GABARIT_DEFAUT.accordeons),
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
          <button class="modele" [class.on]="identique(m.g)" (click)="choisir(m.g)"><b>{{ m.libelle | t }}</b><span class="muted small">{{ m.aide | t }}</span></button>
        }
      </div>
      <fieldset class="lecture">
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
          <div class="field"><label for="ih-s">{{ 'Sous-sections' | t }}</label>
            <select id="ih-s" class="input" (change)="poser('sousSections', $any($event.target).value)">
              @for (o of [['BLOCS', 'Blocs dans la section'], ['ACCORDEONS', 'Accordéons']]; track o[0]) {
                <option [value]="o[0]" [selected]="g().sousSections === o[0]">{{ o[1] | t }}</option> }
            </select></div>
          @if (g().sousSections === 'ACCORDEONS') {
            <div class="field"><label for="ih-a">{{ 'Accordéons ouverts' | t }}</label>
              <select id="ih-a" class="input" (change)="poser('accordeons', $any($event.target).value)">
                @for (o of [['AUCUN', 'Aucun (tous fermés)'], ['PREMIER', 'Le premier de chaque section'], ['TOUS', 'Tous']]; track o[0]) {
                  <option [value]="o[0]" [selected]="g().accordeons === o[0]">{{ o[1] | t }}</option> }
              </select></div>
          }
          <div class="field">
            <label class="check"><input type="checkbox" [checked]="g().navigation" (change)="poser('navigation', $any($event.target).checked)" /> {{ 'Pastilles de navigation' | t }}</label>
            <label class="check"><input type="checkbox" [checked]="g().resume" (change)="poser('resume', $any($event.target).checked)" /> {{ 'Suivi à droite du formulaire (sinon dessous)' | t }}</label>
            <label class="check"><input type="checkbox" [checked]="g().repliables" (change)="poser('repliables', $any($event.target).checked)" /> {{ 'Sections repliables' | t }}</label>
          </div>
        </div>
      </fieldset>
      @if (!modifiable() && modifie()) {
        <div class="alert small row between">
          <span>{{ 'Essai seulement : cette version n\\'est pas un brouillon, le gabarit ne peut pas être enregistré. Créez une nouvelle version depuis la page de la procédure pour l\\'appliquer.' | t }}</span>
          <button class="btn sm ghost" (click)="g.set(initial())">{{ 'Rétablir' | t }}</button>
        </div>
      }
      @if (erreur()) { <div class="alert err small">{{ erreur() }}</div> }
      <div class="apercu" [attr.data-disposition]="g().disposition" [attr.data-densite]="g().densite" [attr.data-libelles]="g().libelles">
        <div class="muted small">{{ 'Aperçu' | t }} · {{ arbre().length }} {{ 'section(s)' | t }}</div>
        @if (g().disposition === 'ONGLETS') {
          <div class="onglets" role="tablist">@for (s of arbre(); track s.s.code; let i = $index) {
            <button type="button" role="tab" class="onglet" [class.on]="i === courante()" (click)="courante.set(i)">{{ s.s.labelFr }}</button> }</div>
        } @else if (g().disposition === 'ETAPES') {
          <div class="etapes">@for (s of arbre(); track s.s.code; let i = $index) {
            <button type="button" class="etape" [class.on]="i === courante()" [class.faite]="i < courante()" (click)="courante.set(i)"><span class="num">{{ i + 1 }}</span>{{ s.s.labelFr }}</button> }</div>
        } @else if (g().navigation) {
          <div class="row pastilles">@for (s of arbre(); track s.s.code) { <span class="chip">{{ s.s.labelFr }}</span> }</div>
        }
        <div class="corps" [class.avec-suivi]="g().resume">
          <div class="sections">
            @for (s of sectionsAffichees(); track s.s.code) {
              <div class="sec">
                <button type="button" class="titre" (click)="basculer(s.s.code)" [disabled]="!g().repliables">
                  @if (g().repliables) { <span class="fleche">{{ repliee(s.s.code) ? '▸' : '▾' }}</span> }
                  {{ s.s.labelFr }} <span class="muted small">· {{ nb(s.s.code) }} {{ 'champ(s)' | t }}</span></button>
                @if (!repliee(s.s.code)) {
                  <div class="cases" [style.grid-template-columns]="'repeat(' + g().colonnes + ', minmax(0, 1fr))'">
                    @for (c of cases(s.s.code); track $index) { <span class="champ"><span class="lib"></span><span class="case"></span></span> }
                  </div>
                  @for (e of s.enfants; track e.code; let j = $index) {
                    @if (g().sousSections === 'ACCORDEONS') {
                      <div class="accordeon" [class.ouvert]="accordeonOuvert(e.code, j)">
                        <button type="button" class="acc-tete" (click)="basculerAccordeon(e.code)" [attr.aria-expanded]="accordeonOuvert(e.code, j)">
                          {{ e.labelFr }} <span class="muted small">· {{ nb(e.code) }} {{ 'champ(s)' | t }}</span><span class="chevron">⌄</span></button>
                        @if (accordeonOuvert(e.code, j)) {
                          <div class="cases acc-corps" [style.grid-template-columns]="'repeat(' + g().colonnes + ', minmax(0, 1fr))'">
                            @for (c of cases(e.code); track $index) { <span class="champ"><span class="lib"></span><span class="case"></span></span> }
                          </div>
                        }
                      </div>
                    } @else {
                      <div class="sous">
                        <div class="titre small">{{ e.labelFr }} <span class="muted">· {{ nb(e.code) }} {{ 'champ(s)' | t }}</span></div>
                        <div class="cases" [style.grid-template-columns]="'repeat(' + g().colonnes + ', minmax(0, 1fr))'">
                          @for (c of cases(e.code); track $index) { <span class="champ"><span class="lib"></span><span class="case"></span></span> }
                        </div>
                      </div>
                    }
                  }
                }
              </div>
            }
            @if (g().disposition === 'ETAPES') {
              <div class="row between">
                <button type="button" class="btn sm" [disabled]="courante() === 0" (click)="courante.set(courante() - 1)">{{ 'Précédent' | t }}</button>
                <span class="muted small">{{ 'Étape {0} sur {1}' | t: courante() + 1 : arbre().length }}</span>
                <button type="button" class="btn sm primary" [disabled]="courante() >= arbre().length - 1" (click)="courante.set(courante() + 1)">{{ 'Suivant' | t }}</button>
              </div>
            }
          </div>
          @if (g().resume) { <div class="suivi"><div class="titre small">{{ 'Suivi' | t }}</div><span class="case"></span><span class="case"></span><span class="case court"></span></div> }
        </div>
      </div>
    </div>
  `,
  styles: [`
    .modeles { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(200px, 100%), 1fr)); gap: 8px; }
    .modele { display: flex; flex-direction: column; gap: 2px; text-align: left; padding: 8px 10px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface); color: var(--text); font: inherit; cursor: pointer; }
    .modele.on { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
    .apercu { display: flex; flex-direction: column; gap: 8px; padding: 10px; border: 1px dashed var(--line); border-radius: 12px; background: var(--surface-2); }
    .onglets { display: flex; gap: 2px; border-bottom: 1px solid var(--line); overflow-x: auto; }
    .onglet { border: 0; border-bottom: 2px solid transparent; background: none; font: inherit; font-size: 13px; padding: 6px 10px; color: var(--muted); cursor: pointer; white-space: nowrap; }
    .onglet.on { color: var(--accent); border-bottom-color: var(--accent); }
    .etapes { display: flex; gap: 6px; flex-wrap: wrap; }
    .etape { display: inline-flex; align-items: center; gap: 6px; border: 1px solid var(--line); border-radius: 999px; padding: 3px 10px 3px 4px; background: var(--surface); font: inherit; font-size: 12.5px; color: var(--muted); cursor: pointer; }
    .etape .num { display: inline-grid; place-items: center; width: 20px; height: 20px; border-radius: 50%; background: var(--chip); font-size: 11px; }
    .etape.on { border-color: var(--accent); color: var(--accent); }
    .etape.on .num { background: var(--accent); color: #fff; }
    .etape.faite .num { background: var(--ok-soft); color: var(--ok); }
    .pastilles { gap: 6px; }
    .corps { display: flex; flex-direction: column; gap: 8px; }
    .corps.avec-suivi { display: grid; grid-template-columns: minmax(0, 1fr) 160px; gap: 8px; align-items: start; }
    .sections { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
    .suivi { border: 1px solid var(--line); border-radius: 10px; padding: 8px; background: var(--surface); display: flex; flex-direction: column; gap: 6px; }
    .court { width: 60%; }
    .accordeon { border-radius: 8px; background: var(--surface-2); }
    .acc-tete { display: flex; align-items: center; gap: 6px; width: 100%; padding: 8px 10px; border: 0; background: none; font: inherit; font-size: 12.5px; font-weight: 600; color: var(--text); text-align: left; cursor: pointer; }
    .acc-tete .chevron { margin-left: auto; transition: transform .15s; }
    .accordeon.ouvert .chevron { transform: rotate(180deg); }
    .acc-corps { padding: 0 10px 10px; }
    .champ { display: flex; flex-direction: column; gap: 3px; }
    .lib { height: 6px; width: 55%; border-radius: 3px; background: var(--line); }
    [data-libelles="COTE"] .champ { flex-direction: row; align-items: center; }
    [data-libelles="COTE"] .lib { width: 35%; flex: none; }
    [data-libelles="COTE"] .champ .case { flex: 1; }
    [data-densite="NORMALE"] .cases { gap: 8px; }
    [data-densite="AEREE"] .cases { gap: 14px; }
    [data-densite="AEREE"] .case { height: 14px; }
    @media (max-width: 640px) { .corps.avec-suivi { grid-template-columns: 1fr; } }
    .sec { border: 1px solid var(--line); border-radius: 10px; padding: 8px; background: var(--surface); display: flex; flex-direction: column; gap: 6px; }
    .sous { margin-left: 12px; border-left: 3px solid var(--accent-soft); padding-left: 8px; display: flex; flex-direction: column; gap: 4px; }
    .titre { display: flex; align-items: center; gap: 6px; border: 0; background: none; padding: 0; font: inherit; font-weight: 600; font-size: 13px; color: inherit; text-align: left; }
    button.titre:not(:disabled) { cursor: pointer; }
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
  /** Section (onglet ou étape) affichée dans l'aperçu. */
  protected readonly courante = signal(0);
  private readonly repliees = signal<ReadonlySet<string>>(new Set());
  protected readonly initial = computed(() => gabarit(this.v().version.settings['ihm']));
  protected readonly modifie = computed(() => JSON.stringify(this.g()) !== JSON.stringify(this.initial()));
  /** Sections de premier niveau et leurs sous-sections. */
  protected readonly arbre = computed(() => {
    const l = [...this.v().sections].sort((a, b) => a.sortOrder - b.sortOrder);
    const codes = new Set(l.map(s => s.code));
    return l.filter(s => !s.parentCode || !codes.has(s.parentCode))
      .map(s => ({ s, enfants: l.filter(e => e.parentCode === s.code) as Section[] }));
  });

  /** Sections de l'aperçu : toutes en continu, sinon l'onglet ou l'étape courante. */
  protected readonly sectionsAffichees = computed(() => {
    const a = this.arbre();
    if (this.g().disposition === 'CONTINU') return a;
    const i = Math.min(this.courante(), a.length - 1);
    return a.slice(i, i + 1);
  });

  constructor() {
    effect(() => this.g.set(this.initial()));
  }

  protected choisir(m: GabaritIhm): void {
    this.g.set(m);
    this.courante.set(0);
    this.repliees.set(new Set());
    this.basculesAcc.set(new Set());
  }

  /** Accordéons basculés par l'utilisateur dans l'aperçu (inversent l'état initial du gabarit). */
  private readonly basculesAcc = signal<ReadonlySet<string>>(new Set());

  protected accordeonOuvert(code: string, rang: number): boolean {
    const a = this.g().accordeons;
    const initial = a === 'TOUS' || (a === 'PREMIER' && rang === 0);
    return this.basculesAcc().has(code) ? !initial : initial;
  }

  protected basculerAccordeon(code: string): void {
    const r = new Set(this.basculesAcc());
    if (r.has(code)) r.delete(code); else r.add(code);
    this.basculesAcc.set(r);
  }

  protected repliee(code: string): boolean {
    return this.g().repliables && this.repliees().has(code);
  }

  protected basculer(code: string): void {
    if (!this.g().repliables) return;
    const r = new Set(this.repliees());
    if (r.has(code)) r.delete(code); else r.add(code);
    this.repliees.set(r);
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
    if (k === 'accordeons' || k === 'sousSections') this.basculesAcc.set(new Set());
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
