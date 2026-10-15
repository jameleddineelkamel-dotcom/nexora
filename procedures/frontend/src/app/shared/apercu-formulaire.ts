import { Component, computed, inject, input, output, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { Field, Section } from '../core/api';
import { I18N, I18n } from '../core/i18n';
import { GabaritIhm, ReglageChamp } from '../pages/version/ihm';
import { Icon } from './icon';

type Donnees = Record<string, unknown>;
type Ligne = Record<string, unknown>;
type Bloc = { s: Section; enfants: Section[] };

/**
 * Aperçu en direct du formulaire de demande, reproduction du rendu e-Services (eservices/frontend shared/formulaire.ts) :
 * disposition continue / onglets / étapes, sous-sections en blocs ou accordéons, colonnes, densité, libellés, largeur
 * des champs, texte indicatif, affichage conditionnel et champs propres à un type de demande. Les listes du Référentiel
 * Commun ne sont pas chargées : elles sont figurées par une zone de choix. Un clic sur un champ le sélectionne.
 */
@Component({
  selector: 'nx-apercu-formulaire',
  imports: [NgTemplateOutlet, Icon, ...I18N],
  host: { '[attr.data-densite]': 'ihm().densite', '[attr.data-libelles]': 'ihm().libelles' },
  template: `
    @if (ihm().disposition === 'CONTINU' && ihm().navigation && blocs().length > 2) {
      <nav class="nav-sec">
        @for (b of blocs(); track b.s.code) { <button type="button" class="pastille" (click)="aller(b.s.code)">{{ b.s.labelFr | lib: b.s.labelEn }}</button> }
      </nav>
    }
    @if (ihm().disposition !== 'CONTINU' && blocs().length > 1) {
      <nav class="onglets-sec" [class.etapes]="ihm().disposition === 'ETAPES'" role="tablist">
        @for (b of blocs(); track b.s.code; let i = $index) {
          <button type="button" role="tab" class="onglet-sec" [class.on]="i === actifBorne()" [class.fait]="ihm().disposition === 'ETAPES' && i < actifBorne()"
            [attr.aria-selected]="i === actifBorne()" (click)="actif.set(i)">
            @if (ihm().disposition === 'ETAPES') { <span class="num">{{ i + 1 }}</span> }
            {{ b.s.labelFr | lib: b.s.labelEn }}
          </button>
        }
      </nav>
    }
    @for (b of blocsVisibles(); track b.s.code) {
      <section class="sec" [id]="ancre(b.s.code)">
        @if (blocs().length > 1 && ihm().disposition === 'CONTINU') {
          <h3 class="sec-titre" [class.repliable]="ihm().repliables" (click)="ihm().repliables && basculer(b.s.code)">
            @if (ihm().repliables) { <nx-icon name="chevron" [size]="14" [class.ferme]="replie(b.s.code)" /> }
            {{ b.s.labelFr | lib: b.s.labelEn }}</h3>
        }
        @if (!replie(b.s.code)) {
          <ng-container *ngTemplateOutlet="corps; context: { s: b.s }" />
          @for (e of b.enfants; track e.code; let j = $index) {
            @if (ihm().sousSections === 'ACCORDEONS') {
              <div class="accordeon" [class.ouvert]="ouvert(e.code, j)" [id]="ancre(e.code)">
                <button type="button" class="acc-tete" [attr.aria-expanded]="ouvert(e.code, j)" (click)="basculerAccordeon(e.code, j)">
                  <span>{{ e.labelFr | lib: e.labelEn }}</span>
                  @if (e.repeating) { <span class="muted small">· {{ lignes(e.code).length }} {{ 'ligne(s)' | t }}</span> }
                  <nx-icon name="chevron" [size]="16" class="acc-chevron" />
                </button>
                @if (ouvert(e.code, j)) { <div class="acc-corps"><ng-container *ngTemplateOutlet="corps; context: { s: e }" /></div> }
              </div>
            } @else {
              <div class="sous-sec" [id]="ancre(e.code)">
                <h4 class="sous-titre">{{ e.labelFr | lib: e.labelEn }}</h4>
                <ng-container *ngTemplateOutlet="corps; context: { s: e }" />
              </div>
            }
          }
        }
      </section>
    } @empty { <div class="empty small">{{ 'Aucun champ à afficher.' | t }}</div> }
    @if (ihm().disposition === 'ETAPES' && blocs().length > 1) {
      <div class="pas">
        <button type="button" class="btn" [disabled]="actifBorne() === 0" (click)="actif.set(actifBorne() - 1)">{{ 'Précédent' | t }}</button>
        <span class="muted small">{{ 'Étape {0} sur {1}' | t: actifBorne() + 1 : blocs().length }}</span>
        <button type="button" class="btn" [disabled]="actifBorne() >= blocs().length - 1" (click)="actif.set(actifBorne() + 1)">{{ 'Suivant' | t }}</button>
      </div>
    }

    <ng-template #corps let-s="s">
      @if (!s.repeating) {
        <div class="grille" [class.cols]="!!ihm().colonnes" [style.--cols]="ihm().colonnes">
          @for (f of champs(s.code); track f.code) { @if (visible(f, null)) {
            <div class="field" [class.full]="pleine(f)" [class.large]="!pleine(f) && largeur(f) > 1" [style.--span]="largeur(f)"
              [class.choisi]="selection() === f.code" (click)="choisi.emit(f.code)">
              <label [attr.for]="'ap-' + f.code">{{ f.labelFr | lib: f.labelEn }} @if (f.mandatory === 'OUI') { <span class="req">*</span> }</label>
              <ng-container *ngTemplateOutlet="saisie; context: { f: f, v: donnees()[f.code], ligne: null, i: -1 }" />
              @if (f.helpFr) { <span class="hint">{{ f.helpFr | lib: f.helpEn }}</span> }
            </div>
          } }
        </div>
      } @else {
        <div class="scroll-x">
          <table class="datagrid repete">
            <thead><tr><th>#</th>@for (f of champs(s.code); track f.code) {
              <th [class.choisi]="selection() === f.code" (click)="choisi.emit(f.code)">{{ f.labelFr | lib: f.labelEn }}@if (f.mandatory === 'OUI') { * }</th> }<th></th></tr></thead>
            <tbody>
              @for (l of lignes(s.code); track $index; let i = $index) {
                <tr>
                  <td class="muted">{{ i + 1 }}</td>
                  @for (f of champs(s.code); track f.code) {
                    <td>@if (visible(f, l)) { <ng-container *ngTemplateOutlet="saisie; context: { f: f, v: l[f.code], ligne: s.code, i: i }" /> }</td>
                  }
                  <td><button type="button" class="btn icon ghost danger" (click)="retirerLigne(s.code, i)" [attr.aria-label]="'Retirer la ligne' | t"><nx-icon name="trash" /></button></td>
                </tr>
              } @empty {
                <tr><td [attr.colspan]="champs(s.code).length + 2" class="empty small">{{ 'Aucune ligne.' | t }} @if (s.minRows) { {{ 'Au moins {0} ligne(s) attendue(s).' | t: s.minRows }} }</td></tr>
              }
            </tbody>
          </table>
        </div>
        <button type="button" class="btn sm" (click)="ajouterLigne(s.code)"><nx-icon name="plus" [size]="15" /> {{ 'Ajouter une ligne' | t }}</button>
      }
    </ng-template>

    <ng-template #saisie let-f="f" let-v="v" let-ligne="ligne" let-i="i">
      @switch (f.dataType) {
        @case ('CODE') {
          @if (f.codeValues?.length) {
            <select class="input" [id]="'ap-' + f.code" (change)="poser(f, $any($event.target).value, ligne, i)">
              <option value="">—</option>
              @for (c of f.codeValues; track c.code) { <option [value]="c.code" [selected]="v === c.code">{{ c.labelFr | lib: c.labelEn }}</option> }
            </select>
          } @else if (f.refdataTable) {
            <div class="input liste" [title]="f.refdataTable"><span class="muted">{{ indication(f) || (f.refdataTable === '@LIEUX_TRAITEMENT' ? ('Lieux de traitement' | t) : f.refdataTable) }}</span><nx-icon name="selector" [size]="14" /></div>
          } @else {
            <input class="input" [id]="'ap-' + f.code" [value]="v ?? ''" [attr.placeholder]="indication(f)" (input)="poser(f, $any($event.target).value, ligne, i)" />
          }
        }
        @case ('GEOLOCATION') { <div class="input liste"><span class="muted">{{ 'Localisation (carte)' | t }}</span><nx-icon name="pin" [size]="14" /></div> }
        @case ('LONGTEXT') { <textarea class="input" [id]="'ap-' + f.code" [value]="v ?? ''" [attr.placeholder]="indication(f)" (input)="poser(f, $any($event.target).value, ligne, i)"></textarea> }
        @case ('BOOLEAN') {
          <select class="input" [id]="'ap-' + f.code" (change)="poser(f, $any($event.target).value, ligne, i)">
            <option value="">—</option><option value="true" [selected]="v === 'true'">{{ 'Oui' | t }}</option>
            <option value="false" [selected]="v === 'false'">{{ 'Non' | t }}</option>
          </select>
        }
        @default {
          <input class="input" [id]="'ap-' + f.code" [type]="type(f)" [value]="v ?? ''" [attr.maxlength]="f.maxLength" [attr.placeholder]="indication(f)"
            [class.mono]="f.dataType !== 'TEXT'" (input)="poser(f, $any($event.target).value, ligne, i)" />
        }
      }
    </ng-template>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 14px; }
    .nav-sec { display: flex; flex-wrap: wrap; gap: 6px; padding: 6px 0 8px; border-bottom: 1px solid var(--line); }
    .pastille { display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px; border: 1px solid var(--line); border-radius: 999px;
      background: var(--surface-2); color: var(--text); font: inherit; font-size: 12.5px; cursor: pointer; }
    .sec { display: flex; flex-direction: column; gap: 8px; }
    .sec + .sec { padding-top: 12px; border-top: 1px dashed var(--line); }
    .sec-titre { margin: 0; font-size: 13.5px; font-weight: 700; color: var(--accent); }
    .grille { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(190px, 100%), 1fr)); gap: 8px 12px; }
    .grille.cols { grid-template-columns: repeat(var(--cols), minmax(0, 1fr)); }
    @media (max-width: 760px) { .grille.cols { grid-template-columns: 1fr; } .grille .field.large { grid-column: auto; } }
    .sous-sec { display: flex; flex-direction: column; gap: 6px; margin-top: 4px; padding-left: 10px; border-left: 3px solid var(--accent-soft); }
    .sous-titre { margin: 0; font-size: 12.5px; font-weight: 700; color: var(--text); }
    .accordeon { border-radius: 8px; background: var(--surface-2); }
    .acc-tete { display: flex; align-items: center; gap: 8px; width: 100%; padding: 12px 16px; border: 0; background: none; font: inherit;
      font-size: 13px; font-weight: 600; color: var(--text); text-align: left; cursor: pointer; border-radius: 8px; }
    .acc-tete:hover { background: var(--chip); }
    .acc-chevron { margin-left: auto; transform: rotate(90deg); transition: transform .15s; }
    .accordeon.ouvert .acc-chevron { transform: rotate(-90deg); }
    .acc-corps { display: flex; flex-direction: column; gap: 8px; padding: 4px 16px 14px; }
    .sec-titre.repliable { cursor: pointer; display: flex; align-items: center; gap: 6px; }
    .sec-titre nx-icon { transition: transform .15s; transform: rotate(90deg); }
    .sec-titre nx-icon.ferme { transform: none; }
    .onglets-sec { display: flex; flex-wrap: wrap; gap: 4px; border-bottom: 1px solid var(--line); }
    .onglet-sec { display: inline-flex; align-items: center; gap: 6px; padding: 7px 12px; border: 0; border-bottom: 2px solid transparent; margin-bottom: -1px;
      background: none; color: var(--muted); font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; }
    .onglet-sec.on { color: var(--accent); border-bottom-color: var(--accent); }
    .etapes .num { display: inline-grid; place-items: center; width: 20px; height: 20px; border-radius: 50%; background: var(--surface-2); font-size: 11px; }
    .etapes .on .num { background: var(--accent); color: #fff; }
    .etapes .fait .num { background: var(--ok-soft); color: var(--ok); }
    .pas { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
    :host([data-densite="NORMALE"]) .grille { gap: 12px 16px; }
    :host([data-densite="NORMALE"]) .grille .input { height: 38px; font-size: 14px; }
    :host([data-densite="AEREE"]) .grille { gap: 16px 20px; }
    :host([data-densite="AEREE"]) .grille .input { height: 42px; font-size: 14.5px; }
    :host([data-densite="AEREE"]) .sec { gap: 12px; }
    :host([data-libelles="COTE"]) .grille .field { display: grid; grid-template-columns: minmax(90px, 40%) 1fr; align-items: center; column-gap: 8px; }
    :host([data-libelles="COTE"]) .grille .field > .hint { grid-column: 2; }
    .grille .full { grid-column: 1 / -1; }
    .grille .large { grid-column: span var(--span); }
    .grille .field { gap: 3px; border-radius: 6px; cursor: pointer; }
    .grille .field > label { font-size: 11.5px; }
    .grille .hint { font-size: 11px; line-height: 1.3; }
    .grille .input, .repete .input { height: 34px; padding: 4px 9px; font-size: 13.5px; }
    .grille textarea.input { height: auto; min-height: 60px; }
    .liste { display: flex; align-items: center; justify-content: space-between; gap: 6px; overflow: hidden; white-space: nowrap; }
    .choisi { outline: 2px solid var(--accent); outline-offset: 3px; }
    .repete td { vertical-align: top; min-width: 130px; padding: 4px 6px; }
    .repete td:first-child { min-width: 30px; }
    .repete th { cursor: pointer; }
    .req { color: var(--danger); }
  `],
})
export class ApercuFormulaire {
  private readonly i18n = inject(I18n);
  readonly sections = input.required<Section[]>();
  readonly fields = input.required<Field[]>();
  readonly ihm = input.required<GabaritIhm>();
  readonly reglages = input<Record<string, ReglageChamp>>({});
  /** Type de demande simulé : les champs conditionnés « typeDemande in (…) » ne s'affichent que pour ces types. */
  readonly typeDemande = input<string | null>(null);
  /** Sections absentes du formulaire du demandeur (INSTRUCTION) et champs masqués pour la nature simulée (renouvellement…). */
  readonly sectionsMasquees = input<string[]>([]);
  readonly champsMasques = input<string[]>([]);
  /** Champ sélectionné dans le constructeur (mis en évidence). */
  readonly selection = input<string | null>(null);
  readonly choisi = output<string>();
  /** Saisie d'essai dans l'aperçu (sert à l'affichage conditionnel) ; jamais enregistrée. */
  protected readonly donnees = signal<Donnees>({});
  protected readonly actif = signal(0);
  private readonly replies = signal<ReadonlySet<string>>(new Set());
  private readonly etatsAcc = signal<ReadonlyMap<string, boolean>>(new Map());
  private readonly prefixe = 'ap-sec-' + Math.random().toString(36).slice(2, 8) + '-';

  private readonly triees = computed(() => [...this.sections()].sort((a, b) => a.sortOrder - b.sortOrder));

  protected readonly blocs = computed<Bloc[]>(() => {
    const masquees = new Set(this.sectionsMasquees());
    const toutes = this.triees().filter(s => !masquees.has(s.code));
    const codes = new Set(toutes.map(s => s.code));
    const visibles = new Set(toutes.filter(s => this.champs(s.code).length > 0).map(s => s.code));
    return toutes.filter(s => !s.parentCode || !codes.has(s.parentCode))
      .map(s => ({ s, enfants: toutes.filter(e => e.parentCode === s.code && visibles.has(e.code)) }))
      .filter(b => visibles.has(b.s.code) || b.enfants.length > 0);
  });

  protected readonly actifBorne = computed(() => Math.max(0, Math.min(this.actif(), this.blocs().length - 1)));

  protected readonly blocsVisibles = computed(() => {
    const b = this.blocs();
    if (this.ihm().disposition === 'CONTINU') return b;
    return b.length ? [b[this.actifBorne()]] : [];
  });

  /** Affiche l'onglet ou l'étape contenant la section (suivi de la sélection du constructeur). */
  montrer(section: string): void {
    const s = this.sections().find(x => x.code === section);
    const haut = s?.parentCode && this.sections().some(x => x.code === s.parentCode) ? s.parentCode : section;
    const i = this.blocs().findIndex(b => b.s.code === haut);
    if (i >= 0) this.actif.set(i);
    if (s?.parentCode) this.etatsAcc.set(new Map(this.etatsAcc()).set(section, true));
  }

  protected ouvert(code: string, rang: number): boolean {
    const choix = this.etatsAcc().get(code);
    if (choix !== undefined) return choix;
    const a = this.ihm().accordeons;
    return a === 'TOUS' || (a === 'PREMIER' && rang === 0);
  }

  protected basculerAccordeon(code: string, rang: number): void {
    this.etatsAcc.set(new Map(this.etatsAcc()).set(code, !this.ouvert(code, rang)));
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

  protected aller(section: string): void {
    document.getElementById(this.ancre(section))?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  private applicable(f: Field): boolean {
    const m = /^\s*typeDemande\s+in\s*\(([^)]*)\)\s*$/i.exec(f.conditionText ?? '');
    const t = this.typeDemande();
    return !m || !t || m[1].split(',').map(x => x.trim().replace(/'/g, '').toUpperCase()).includes(t.toUpperCase());
  }

  protected champs(section: string): Field[] {
    const masques = this.champsMasques();
    return this.fields().filter(f => f.sectionCode === section && this.applicable(f) && !masques.includes(f.code));
  }

  protected largeur(f: Field): number {
    const n = Number(this.reglages()[f.code]?.largeur);
    return n >= 1 && n <= 4 ? Math.round(n) : 1;
  }

  protected pleine(f: Field): boolean {
    const r = this.reglages()[f.code]?.largeur;
    if (r === undefined || r === null) return f.dataType === 'LONGTEXT' || f.dataType === 'GEOLOCATION';
    return this.largeur(f) >= (this.ihm().colonnes || 4);
  }

  protected indication(f: Field): string | null {
    const r = this.reglages()[f.code];
    return r ? this.i18n.libelle(r.placeholderFr, r.placeholderEn) || null : null;
  }

  protected visible(f: Field, ligne: Ligne | null): boolean {
    const si = this.reglages()[f.code]?.visibleSi;
    if (!si?.champ) return true;
    const v = ligne && si.champ in ligne ? ligne[si.champ] : this.donnees()[si.champ];
    const valeur = v === null || v === undefined ? '' : String(v).trim();
    const valeurs = si.valeurs ?? [];
    return valeurs.length ? valeurs.some(x => String(x).trim().toUpperCase() === valeur.toUpperCase()) : valeur !== '';
  }

  protected lignes(section: string): Ligne[] {
    const v = this.donnees()[section];
    return Array.isArray(v) ? (v as Ligne[]) : [];
  }

  protected type(f: Field): string {
    return f.dataType === 'DATE' ? 'date' : f.dataType === 'EMAIL' ? 'email' : f.dataType === 'PHONE' ? 'tel' : 'text';
  }

  protected poser(f: Field, v: string, section: string | null, i: number): void {
    const d: Donnees = { ...this.donnees() };
    if (section === null) d[f.code] = v;
    else {
      const l = this.lignes(section).map(x => ({ ...x }));
      l[i] = { ...l[i], [f.code]: v };
      d[section] = l;
    }
    this.donnees.set(d);
  }

  protected ajouterLigne(section: string): void {
    this.donnees.set({ ...this.donnees(), [section]: [...this.lignes(section), {}] });
  }

  protected retirerLigne(section: string, i: number): void {
    this.donnees.set({ ...this.donnees(), [section]: this.lignes(section).filter((_, k) => k !== i) });
  }
}
