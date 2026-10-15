import { Component, computed, inject, input, output, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { Field, Formulaire, Section } from '../core/api';
import { I18N, I18n } from '../core/i18n';
import { Icon } from './icon';
import { CodePicker, Localisation } from './widgets';

type Donnees = Record<string, unknown>;
type Ligne = Record<string, unknown>;
type Ihm = { disposition: 'CONTINU' | 'ONGLETS' | 'ETAPES'; colonnes: number | null; densite: 'COMPACTE' | 'NORMALE' | 'AEREE'; libelles: 'DESSUS' | 'COTE';
  navigation: boolean; repliables: boolean };
type Bloc = { s: Section; enfants: Section[] };

/**
 * Formulaire généré à partir du paquet publié : sections, groupes répétitifs (ex. produits), listes de codes du
 * Référentiel Commun, lieux de traitement, localisation intelligente. En amendement, les champs non modifiables sont
 * verrouillés et les valeurs modifiées sont surlignées avec la valeur précédente (RG2 UC-03). Le gabarit d'IHM du studio
 * choisit la disposition (continue, onglets, étapes), le nombre de colonnes, la densité et la position des libellés ;
 * les sous-sections s'affichent à l'intérieur de leur section parente.
 */
@Component({
  selector: 'nx-formulaire',
  imports: [NgTemplateOutlet, Icon, CodePicker, Localisation, ...I18N],
  host: { '[attr.data-densite]': 'ihm().densite', '[attr.data-libelles]': 'ihm().libelles' },
  template: `
    @if (ihm().disposition === 'CONTINU' && ihm().navigation && blocs().length > 2) {
      <nav class="nav-sec" [attr.aria-label]="'Sections du formulaire' | t">
        @for (b of blocs(); track b.s.code) {
          <button type="button" class="pastille" [class.err]="erreursBloc(b) > 0" (click)="aller(b.s.code)">
            {{ b.s.labelFr | lib: b.s.labelEn }} @if (erreursBloc(b)) { <span class="badge-n">{{ erreursBloc(b) }}</span> }
          </button>
        }
      </nav>
    }
    @if (ihm().disposition !== 'CONTINU' && blocs().length > 1) {
      <nav class="onglets-sec" [class.etapes]="ihm().disposition === 'ETAPES'" role="tablist" [attr.aria-label]="'Sections du formulaire' | t">
        @for (b of blocs(); track b.s.code; let i = $index) {
          <button type="button" role="tab" class="onglet-sec" [class.on]="i === actif()" [class.fait]="ihm().disposition === 'ETAPES' && i < actif()"
            [class.err]="erreursBloc(b) > 0" [attr.aria-selected]="i === actif()" (click)="actif.set(i)">
            @if (ihm().disposition === 'ETAPES') { <span class="num">{{ i + 1 }}</span> }
            {{ b.s.labelFr | lib: b.s.labelEn }} @if (erreursBloc(b)) { <span class="badge-n">{{ erreursBloc(b) }}</span> }
          </button>
        }
      </nav>
    }
    @for (b of blocsVisibles(); track b.s.code) {
      <section class="sec" [id]="ancre(b.s.code)">
        @if (blocs().length > 1 && ihm().disposition === 'CONTINU') {
          <h3 class="sec-titre" [class.repliable]="ihm().repliables" (click)="ihm().repliables && basculer(b.s.code)">
            @if (ihm().repliables) { <nx-icon name="chevron" [size]="14" [class.ferme]="replie(b.s.code)" /> }
            {{ b.s.labelFr | lib: b.s.labelEn }}
            @if (b.s.repeating) { <span class="muted small">· {{ lignes(b.s.code).length }} {{ 'ligne(s)' | t }}</span> }</h3>
        }
        @if (!replie(b.s.code)) {
          <ng-container *ngTemplateOutlet="corps; context: { s: b.s }" />
          @for (e of b.enfants; track e.code) {
            <div class="sous-sec" [id]="ancre(e.code)">
              <h4 class="sous-titre">{{ e.labelFr | lib: e.labelEn }}
                @if (e.repeating) { <span class="muted small">· {{ lignes(e.code).length }} {{ 'ligne(s)' | t }}</span> }</h4>
              <ng-container *ngTemplateOutlet="corps; context: { s: e }" />
            </div>
          }
        }
      </section>
    }
    @if (ihm().disposition === 'ETAPES' && blocs().length > 1) {
      <div class="pas">
        <button type="button" class="btn" [disabled]="actif() === 0" (click)="actif.set(actif() - 1)">{{ 'Précédent' | t }}</button>
        <span class="muted small">{{ 'Étape {0} sur {1}' | t: actif() + 1 : blocs().length }}</span>
        <button type="button" class="btn" [disabled]="actif() >= blocs().length - 1" (click)="actif.set(actif() + 1)">{{ 'Suivant' | t }}</button>
      </div>
    }

    <ng-template #corps let-s="s">
        @if (!s.repeating) {
          <div class="grille" [class.cols]="!!ihm().colonnes" [style.--cols]="ihm().colonnes">
            @for (f of champs(s.code); track f.code) {
              <div class="field" [class.full]="f.dataType === 'LONGTEXT' || f.dataType === 'GEOLOCATION'">
                <label [attr.for]="'c-' + f.code">{{ f.labelFr | lib: f.labelEn }} @if (obligatoire(f)) { <span class="req">*</span> }</label>
                <ng-container *ngTemplateOutlet="saisie; context: { f: f, v: valeur(f.code), ligne: null, i: -1 }" />
                @if (modifie(f.code)) { <span class="hint avant">{{ 'Avant amendement' | t }} : <b>{{ affichage(f, precedent()?.[f.code]) || '—' }}</b></span> }
                @else if (f.helpFr) { <span class="hint">{{ f.helpFr | lib: f.helpEn }}</span> }
                @if (erreur(f.code); as e) { <span class="hint err-txt">{{ e }}</span> }
              </div>
            }
          </div>
        } @else {
          <div class="scroll-x">
            <table class="datagrid repete">
              <thead><tr><th>#</th>@for (f of champs(s.code); track f.code) { <th>{{ f.labelFr | lib: f.labelEn }}@if (obligatoire(f)) { * }</th> } @if (!lectureSeule()) { <th></th> }</tr></thead>
              <tbody>
                @for (l of lignes(s.code); track $index; let i = $index) {
                  <tr>
                    <td class="muted">{{ i + 1 }}</td>
                    @for (f of champs(s.code); track f.code) {
                      <td><ng-container *ngTemplateOutlet="saisie; context: { f: f, v: l[f.code], ligne: s.code, i: i }" /></td>
                    }
                    @if (!lectureSeule()) { <td><button type="button" class="btn icon ghost danger" (click)="retirerLigne(s, i)" [attr.aria-label]="'Retirer la ligne' | t"><nx-icon name="trash" /></button></td> }
                  </tr>
                } @empty {
                  <tr><td [attr.colspan]="champs(s.code).length + 2" class="empty small">{{ 'Aucune ligne.' | t }} @if (s.minRows) { {{ 'Au moins {0} ligne(s) attendue(s).' | t: s.minRows }} }</td></tr>
                }
              </tbody>
            </table>
          </div>
          @if (erreur(s.code); as e) { <div class="alert err">{{ e }}</div> }
          @if (!lectureSeule()) { <button type="button" class="btn sm" (click)="ajouterLigne(s)"><nx-icon name="plus" [size]="15" /> {{ 'Ajouter une ligne' | t }}</button> }
        }
    </ng-template>

    <ng-template #saisie let-f="f" let-v="v" let-ligne="ligne" let-i="i">
      @switch (f.dataType) {
        @case ('CODE') {
          @if (f.codeValues?.length) {
            <select class="input" [id]="'c-' + f.code" [disabled]="verrou(f)" [class.err]="!!erreur(f.code)" [class.modifie]="ligne === null && modifie(f.code)"
              (change)="poser(f, $any($event.target).value, ligne, i)">
              <option value="">—</option>
              @for (c of f.codeValues; track c.code) { <option [value]="c.code" [selected]="v === c.code">{{ c.labelFr | lib: c.labelEn }}</option> }
            </select>
          } @else if (f.refdataTable === '@LIEUX_TRAITEMENT') {
            <select class="input" [id]="'c-' + f.code" [disabled]="verrou(f)" [class.err]="!!erreur(f.code)" (change)="poser(f, $any($event.target).value, ligne, i)">
              <option value="">—</option>
              @for (l of form().locations; track l.code) { <option [value]="l.code" [selected]="v === l.code">{{ l.labelFr | lib: l.labelEn }} — {{ l.unitLabel }}</option> }
            </select>
          } @else if (f.refdataTable) {
            <nx-code-picker [table]="f.refdataTable" [value]="$any(v) ?? null" (valueChange)="poser(f, $event ?? '', ligne, i)" [disabled]="verrou(f)"
              [erreur]="!!erreur(f.code)" [label]="f.labelFr" />
          } @else {
            <input class="input" [id]="'c-' + f.code" [value]="v ?? ''" [disabled]="verrou(f)" (input)="poser(f, $any($event.target).value, ligne, i)" />
          }
        }
        @case ('GEOLOCATION') { <nx-localisation [value]="$any(v) ?? null" (valueChange)="poser(f, $event ?? '', ligne, i)" [disabled]="verrou(f)" /> }
        @case ('LONGTEXT') { <textarea class="input" [id]="'c-' + f.code" [value]="v ?? ''" [disabled]="verrou(f)" (input)="poser(f, $any($event.target).value, ligne, i)"></textarea> }
        @case ('BOOLEAN') {
          <select class="input" [id]="'c-' + f.code" [disabled]="verrou(f)" (change)="poser(f, $any($event.target).value, ligne, i)">
            <option value="">—</option><option value="true" [selected]="v === 'true' || v === true">{{ 'Oui' | t }}</option>
            <option value="false" [selected]="v === 'false' || v === false">{{ 'Non' | t }}</option>
          </select>
        }
        @default {
          <input class="input" [id]="'c-' + f.code" [type]="type(f)" [value]="v ?? ''" [disabled]="verrou(f)" [attr.maxlength]="f.maxLength"
            [attr.inputmode]="f.dataType === 'DECIMAL' || f.dataType === 'NUMBER' || f.dataType === 'YEAR' ? 'decimal' : null"
            [class.mono]="f.dataType !== 'TEXT'" [class.err]="!!erreur(f.code)" [class.modifie]="ligne === null && modifie(f.code)"
            (input)="poser(f, $any($event.target).value, ligne, i)" />
        }
      }
    </ng-template>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 14px; }
    /* Navigation entre sections (formulaire continu) */
    .nav-sec { position: sticky; top: 0; z-index: 2; display: flex; flex-wrap: wrap; gap: 6px; padding: 6px 0 8px; background: var(--surface); border-bottom: 1px solid var(--line); }
    .pastille { display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px; border: 1px solid var(--line); border-radius: 999px;
      background: var(--surface-2); color: var(--text); font: inherit; font-size: 12.5px; cursor: pointer; }
    .pastille:hover { border-color: var(--accent); color: var(--accent); }
    .pastille.err { border-color: var(--danger); color: var(--danger); }
    /* Sections enchaînées, grille dense */
    .sec { display: flex; flex-direction: column; gap: 8px; scroll-margin-top: 56px; }
    .sec + .sec { padding-top: 12px; border-top: 1px dashed var(--line); }
    .sec-titre { margin: 0; font-size: 13.5px; font-weight: 700; color: var(--accent); text-transform: none; }
    .grille { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(190px, 100%), 1fr)); gap: 8px 12px; }
    .grille.cols { grid-template-columns: repeat(var(--cols), minmax(0, 1fr)); }
    @media (max-width: 760px) { .grille.cols { grid-template-columns: 1fr; } }
    /* Sous-sections : à l'intérieur de la section parente */
    .sous-sec { display: flex; flex-direction: column; gap: 6px; margin-top: 4px; padding-left: 10px; border-left: 3px solid var(--accent-soft); scroll-margin-top: 56px; }
    .sous-titre { margin: 0; font-size: 12.5px; font-weight: 700; color: var(--text); }
    .sec-titre.repliable { cursor: pointer; display: flex; align-items: center; gap: 6px; }
    .sec-titre nx-icon { transition: transform .15s; transform: rotate(90deg); }
    .sec-titre nx-icon.ferme { transform: none; }
    /* Onglets et étapes */
    .onglets-sec { display: flex; flex-wrap: wrap; gap: 4px; border-bottom: 1px solid var(--line); }
    .onglet-sec { display: inline-flex; align-items: center; gap: 6px; padding: 7px 12px; border: 0; border-bottom: 2px solid transparent; margin-bottom: -1px;
      background: none; color: var(--muted); font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; }
    .onglet-sec.on { color: var(--accent); border-bottom-color: var(--accent); }
    .onglet-sec.err { color: var(--danger); }
    .etapes .num { display: inline-grid; place-items: center; width: 20px; height: 20px; border-radius: 50%; background: var(--surface-2); font-size: 11px; }
    .etapes .on .num { background: var(--accent); color: #fff; }
    .etapes .fait .num { background: var(--ok-soft); color: var(--ok); }
    .pas { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
    /* Densité et position des libellés (gabarit d'IHM) */
    :host([data-densite="NORMALE"]) .grille { gap: 12px 16px; }
    :host([data-densite="NORMALE"]) ::ng-deep .grille .input { height: 38px; font-size: 14px; }
    :host([data-densite="AEREE"]) .grille { gap: 16px 20px; }
    :host([data-densite="AEREE"]) ::ng-deep .grille .input { height: 42px; font-size: 14.5px; }
    :host([data-densite="AEREE"]) .sec { gap: 12px; }
    :host([data-libelles="COTE"]) .grille .field { display: grid; grid-template-columns: minmax(90px, 40%) 1fr; align-items: center; column-gap: 8px; }
    :host([data-libelles="COTE"]) .grille .field > .hint { grid-column: 2; }
    .grille .full { grid-column: 1 / -1; }
    .grille .field { gap: 3px; }
    .grille .field > label { font-size: 11.5px; }
    .grille .hint { font-size: 11px; line-height: 1.3; }
    :host ::ng-deep .grille .input, :host ::ng-deep .repete .input { height: 34px; padding: 4px 9px; font-size: 13.5px; }
    :host ::ng-deep .repete td { padding: 4px 6px; }
    .req { color: var(--danger); }
    .err-txt { color: var(--danger); }
    .avant { color: var(--warn); }
    .repete td { vertical-align: top; min-width: 130px; }
    .repete td:first-child { min-width: 30px; }
    .repete tbody tr { cursor: default; }
  `],
})
export class FormulaireDynamique {
  private readonly i18n = inject(I18n);
  readonly form = input.required<Formulaire>();
  readonly donnees = input.required<Donnees>();
  readonly precedent = input<Donnees | null>(null);
  readonly nature = input('INITIAL');
  /** Type de demande : les champs conditionnés « typeDemande in (…) » ne s'affichent que pour ces types. */
  readonly typeDemande = input<string | null>(null);
  readonly lectureSeule = input(false);
  /** Sections non affichées (ex. INSTRUCTION, saisie par l'organisme instructeur, absente du formulaire du demandeur). */
  readonly sectionsMasquees = input<string[]>([]);
  readonly erreurs = input<{ champ: string; message: string; ligne: number | null }[]>([]);
  readonly modification = output<Donnees>();
  /** Préfixe des ancres de sections, propre à chaque formulaire de la page (demandeur, instruction). */
  private readonly prefixe = 'sec-' + Math.random().toString(36).slice(2, 8) + '-';

  /** Gabarit d'IHM de la procédure (réglage « ihm » du studio) ; sans gabarit : formulaire continu, grille automatique. */
  protected readonly ihm = computed<Ihm>(() => {
    const o = (this.form().settings.ihm ?? {}) as Partial<Record<keyof Ihm, unknown>>;
    const un = <T extends string>(v: unknown, l: T[], d: T): T => (l.includes(v as T) ? (v as T) : d);
    const n = Number(o.colonnes);
    return {
      disposition: un(o.disposition, ['CONTINU', 'ONGLETS', 'ETAPES'], 'CONTINU'), colonnes: n >= 1 && n <= 4 ? Math.round(n) : null,
      densite: un(o.densite, ['COMPACTE', 'NORMALE', 'AEREE'], 'COMPACTE'), libelles: un(o.libelles, ['DESSUS', 'COTE'], 'DESSUS'),
      navigation: o.navigation !== false, repliables: o.repliables === true,
    };
  });
  /** Onglet ou étape affiché (dispositions ONGLETS et ETAPES). */
  protected readonly actif = signal(0);
  private readonly replies = signal<ReadonlySet<string>>(new Set());

  protected readonly sections = computed(() => {
    const s = [...this.form().sections].sort((a, b) => a.sortOrder - b.sortOrder);
    // La section « Demande » ne contient que des champs de renouvellement / amendement : masquée pour une demande initiale
    return s.filter(x => this.champs(x.code).length > 0 && !this.sectionsMasquees().includes(x.code));
  });

  /** Sections de premier niveau et leurs sous-sections visibles ; une sous-section dont le parent est absent remonte au premier niveau. */
  protected readonly blocs = computed<Bloc[]>(() => {
    const toutes = [...this.form().sections].sort((a, b) => a.sortOrder - b.sortOrder);
    const codes = new Set(toutes.map(s => s.code));
    const visibles = new Set(this.sections().map(s => s.code));
    const masquees = new Set(this.sectionsMasquees());
    return toutes.filter(s => !s.parentCode || !codes.has(s.parentCode))
      .filter(s => !masquees.has(s.code))
      .map(s => ({ s, enfants: toutes.filter(e => e.parentCode === s.code && visibles.has(e.code)) }))
      .filter(b => visibles.has(b.s.code) || b.enfants.length > 0);
  });

  protected readonly blocsVisibles = computed(() => {
    const b = this.blocs();
    if (this.ihm().disposition === 'CONTINU') return b;
    return b.length ? [b[Math.min(this.actif(), b.length - 1)]] : [];
  });

  protected erreursBloc(b: Bloc): number {
    return this.erreursSection(b.s.code) + b.enfants.reduce((n, e) => n + this.erreursSection(e.code), 0);
  }

  protected replie(code: string): boolean {
    return this.ihm().repliables && this.replies().has(code);
  }

  protected basculer(code: string): void {
    const s = new Set(this.replies());
    if (s.has(code)) s.delete(code); else s.add(code);
    this.replies.set(s);
  }

  protected ancre(section: string): string {
    return this.prefixe + section;
  }

  /** Défilement vers une section (navigation par pastilles). */
  protected aller(section: string): void {
    document.getElementById(this.ancre(section))?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  private applicable(f: Field): boolean {
    const m = /^\s*typeDemande\s+in\s*\(([^)]*)\)\s*$/i.exec(f.conditionText ?? '');
    const t = this.typeDemande();
    return !m || !t || m[1].split(',').map(x => x.trim().replace(/'/g, '').toUpperCase()).includes(t.toUpperCase());
  }

  protected champs(section: string): Field[] {
    const st = this.form().settings;
    return this.form().fields.filter(f => f.sectionCode === section).filter(f => {
      if (!this.applicable(f)) return false;
      if (f.code === st.renewalYearField) return this.nature() === 'RENEWAL';
      if (f.code === st.previousField) return this.nature() !== 'INITIAL';
      return true;
    });
  }

  protected valeur(code: string): unknown {
    return this.donnees()[code];
  }

  protected lignes(section: string): Ligne[] {
    const v = this.donnees()[section];
    return Array.isArray(v) ? (v as Ligne[]) : [];
  }

  protected obligatoire(f: Field): boolean {
    return f.mandatory === 'OUI' || (f.mandatory === 'CONDITIONNEL' && (f.code === this.form().settings.previousField || f.code === this.form().settings.renewalYearField));
  }

  protected verrou(f: Field): boolean {
    if (this.lectureSeule()) return true;
    const st = this.form().settings;
    if ([st.applicantField, st.previousField].includes(f.code)) return true;
    return this.nature() === 'AMENDMENT' && !f.amendable;
  }

  protected modifie(code: string): boolean {
    const p = this.precedent();
    if (!p || this.nature() !== 'AMENDMENT') return false;
    return JSON.stringify(p[code] ?? '') !== JSON.stringify(this.donnees()[code] ?? '');
  }

  protected affichage(f: Field, v: unknown): string {
    if (v === null || v === undefined) return '';
    const c = f.codeValues?.find(x => x.code === v);
    return c ? this.i18n.libelle(c.labelFr, c.labelEn) : String(v);
  }

  protected type(f: Field): string {
    return f.dataType === 'DATE' ? 'date' : f.dataType === 'EMAIL' ? 'email' : f.dataType === 'PHONE' ? 'tel' : 'text';
  }

  protected erreur(code: string): string | null {
    const e = this.erreurs().filter(x => x.champ === code);
    return e.length ? e.map(x => x.message).join(' ') : null;
  }

  protected erreursSection(section: string): number {
    const codes = new Set([section, ...this.form().fields.filter(f => f.sectionCode === section).map(f => f.code)]);
    return this.erreurs().filter(e => codes.has(e.champ)).length;
  }

  protected poser(f: Field, v: string, section: string | null, i: number): void {
    const d: Donnees = { ...this.donnees() };
    if (section === null) d[f.code] = v;
    else {
      const l = this.lignes(section).map(x => ({ ...x }));
      l[i] = { ...l[i], [f.code]: v };
      d[section] = l;
    }
    this.modification.emit(d);
  }

  protected ajouterLigne(s: Section): void {
    this.modification.emit({ ...this.donnees(), [s.code]: [...this.lignes(s.code), {}] });
  }

  protected retirerLigne(s: Section, i: number): void {
    this.modification.emit({ ...this.donnees(), [s.code]: this.lignes(s.code).filter((_, k) => k !== i) });
  }
}
