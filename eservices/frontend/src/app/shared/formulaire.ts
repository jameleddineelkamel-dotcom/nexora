import { Component, computed, inject, input, output, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { Field, Formulaire, Section } from '../core/api';
import { I18N, I18n } from '../core/i18n';
import { Icon } from './icon';
import { CodePicker, Localisation } from './widgets';

type Donnees = Record<string, unknown>;
type Ligne = Record<string, unknown>;

/**
 * Formulaire généré à partir du paquet publié : sections, groupes répétitifs (ex. produits), listes de codes du
 * Référentiel Commun, lieux de traitement, localisation intelligente. En amendement, les champs non modifiables sont
 * verrouillés et les valeurs modifiées sont surlignées avec la valeur précédente (RG2 UC-03).
 */
@Component({
  selector: 'nx-formulaire',
  imports: [NgTemplateOutlet, Icon, CodePicker, Localisation, ...I18N],
  template: `
    <div class="tabs mini" role="tablist">
      @for (s of sections(); track s.code) {
        <button type="button" class="tab" [class.on]="onglet() === s.code" (click)="onglet.set(s.code)" role="tab">
          {{ s.labelFr | lib: s.labelEn }} @if (erreursSection(s.code)) { <span class="badge-n">{{ erreursSection(s.code) }}</span> }
        </button>
      }
    </div>
    @for (s of sections(); track s.code) {
      @if (onglet() === s.code) {
        @if (!s.repeating) {
          <div class="form-grid">
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
          @if (!lectureSeule()) { <button type="button" class="btn" (click)="ajouterLigne(s)"><nx-icon name="plus" /> {{ 'Ajouter une ligne' | t }}</button> }
        }
      }
    }

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
    :host { display: flex; flex-direction: column; gap: 12px; }
    .req { color: var(--danger); }
    .err-txt { color: var(--danger); }
    .avant { color: var(--warn); }
    .repete td { vertical-align: top; min-width: 150px; }
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
  protected readonly onglet = signal<string>('');

  protected readonly sections = computed(() => {
    const s = [...this.form().sections].sort((a, b) => a.sortOrder - b.sortOrder);
    // La section « Demande » ne contient que des champs de renouvellement / amendement : masquée pour une demande initiale
    return s.filter(x => this.champs(x.code).length > 0 && !this.sectionsMasquees().includes(x.code));
  });

  constructor() {
    queueMicrotask(() => { if (!this.onglet() && this.sections().length) this.onglet.set(this.sections()[0].code); });
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
