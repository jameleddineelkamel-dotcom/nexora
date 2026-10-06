import { Component, inject, input, model, signal } from '@angular/core';
import { ApiService } from '../core/api.service';
import { ColumnDef, DataType, ROLES, Role, TYPES, TableSummary } from '../core/models';
import { Icon } from './icon';
import { I18N } from '../core/i18n';

/** Éditeur de la structure d'une table : colonnes typées, rôles, références et données UN/CEFACT (balise XML, UNTDED). */
@Component({
  selector: 'nx-column-editor',
  imports: [Icon, ...I18N],
  template: `
    <div class="colonnes">
      @for (c of colonnes(); track $index; let i = $index) {
        <div class="col card" [class.ouverte]="ouverte() === i" [attr.data-role]="c.role">
          <div class="ligne">
            <div class="ordre">
              <button class="btn ghost icon sm" (click)="deplacer(i, -1)" [disabled]="i === 0" [attr.aria-label]="'Monter' | t">↑</button>
              <button class="btn ghost icon sm" (click)="deplacer(i, 1)" [disabled]="i === colonnes().length - 1" [attr.aria-label]="'Descendre' | t">↓</button>
            </div>
            <input class="input" [value]="c.labelFr" (input)="libelle(i, $any($event.target).value)" [placeholder]="'Libellé de la colonne' | t" [attr.aria-label]="'Libellé' | t" />
            <input class="input mono cle" [value]="c.key" (input)="maj(i, { key: $any($event.target).value })" [placeholder]="'cle' | t" [attr.aria-label]="'Clé' | t" />
            <select class="input" [value]="c.role" (change)="maj(i, { role: $any($event.target).value })" [attr.aria-label]="'Rôle' | t">
              @for (r of roles; track r.code) { <option [value]="r.code" [selected]="r.code === c.role">{{ r.label | t }}</option> }
            </select>
            <select class="input" [value]="c.dataType" (change)="maj(i, { dataType: $any($event.target).value })" [attr.aria-label]="'Type' | t" [disabled]="c.role !== 'ATTRIBUTE'">
              @for (t of types; track t.code) { <option [value]="t.code" [selected]="t.code === c.dataType">{{ t.label | t }}</option> }
            </select>
            @if (c.dataType === 'CODE_REF') {
              <select class="input" [value]="c.refTableCode ?? ''" (change)="maj(i, { refTableCode: $any($event.target).value })" [attr.aria-label]="'Table référencée' | t">
                <option value="">{{ 'Table référencée…' | t }}</option>
                @for (t of tables(); track t.code) { <option [value]="t.code" [selected]="t.code === c.refTableCode">{{ t.code }}</option> }
              </select>
            } @else {
              <input class="input" type="number" min="1" [value]="c.maxLength ?? ''" (input)="maj(i, { maxLength: $any($event.target).value ? +$any($event.target).value : null })"
                [placeholder]="'Long. max' | t" [attr.aria-label]="'Longueur maximale' | t" [disabled]="!['STRING', 'TEXT'].includes(c.dataType)" />
            }
            <label class="check small"><input type="checkbox" [checked]="c.required" (change)="maj(i, { required: $any($event.target).checked })" [disabled]="c.role === 'CODE'" /> {{ 'Oblig.' | t }}</label>
            <button class="btn ghost icon sm" (click)="ouverte.set(ouverte() === i ? -1 : i)" [attr.aria-label]="'Détails' | t"><nx-icon name="info" [size]="16" /></button>
            <button class="btn ghost icon sm danger" (click)="retirer(i)" [disabled]="c.role === 'CODE'" [attr.aria-label]="'Retirer' | t"><nx-icon name="x" [size]="16" /></button>
          </div>
          @if (ouverte() === i) {
            <div class="details form-grid">
              <div class="field"><label>{{ 'Libellé anglais' | t }}</label><input class="input" [value]="c.labelEn ?? ''" (input)="maj(i, { labelEn: $any($event.target).value })" /></div>
              <div class="field"><label>{{ 'Balise XML' | t }}</label><input class="input mono" [value]="c.xmlTag ?? ''" (input)="maj(i, { xmlTag: $any($event.target).value })" placeholder="CountryIdAlpha2" /></div>
              <div class="field"><label>{{ 'Référence UNTDED / ISO 7372' | t }}</label><input class="input mono" [value]="c.untded ?? ''" (input)="maj(i, { untded: $any($event.target).value })" placeholder="3207" /></div>
              <div class="field"><label>{{ 'Cardinalité' | t }}</label><input class="input mono" [value]="c.cardinality ?? ''" (input)="maj(i, { cardinality: $any($event.target).value })" placeholder="(1,1)" /></div>
              <div class="field"><label>{{ 'Unité de mesure' | t }}</label><input class="input" [value]="c.unit ?? ''" (input)="maj(i, { unit: $any($event.target).value })" /></div>
              <div class="field"><label>{{ 'Format (expression régulière)' | t }}</label><input class="input mono" [value]="c.pattern ?? ''" (input)="maj(i, { pattern: $any($event.target).value })" placeholder="^[A-Z]{2}$" /></div>
              <div class="field full"><label>{{ 'Définition' | t }}</label><input class="input" [value]="c.definition ?? ''" (input)="maj(i, { definition: $any($event.target).value })" /></div>
              <div class="field full"><label>{{ 'Commentaire (règles de typologie…)' | t }}</label><input class="input" [value]="c.comment ?? ''" (input)="maj(i, { comment: $any($event.target).value })" /></div>
            </div>
          }
        </div>
      }
    </div>
    <button class="btn" (click)="ajouter()"><nx-icon name="plus" /> {{ 'Ajouter une colonne' | t }}</button>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 10px; align-items: flex-start; }
    .colonnes { display: flex; flex-direction: column; gap: 6px; width: 100%; }
    .col { padding: 8px; border-left: 4px solid var(--line); box-shadow: none; }
    .col[data-role=CODE] { border-left-color: var(--navy); }
    .col[data-role=LABEL_FR], .col[data-role=LABEL_EN] { border-left-color: var(--cyan); }
    .col[data-role=ATTRIBUTE] { border-left-color: var(--green); }
    .ligne { display: grid; grid-template-columns: auto 1.6fr 1.1fr 1fr 1fr 1fr auto auto auto; gap: 6px; align-items: center; }
    .ordre { display: flex; flex-direction: column; }
    .ordre .btn { height: 17px; width: 24px; font-size: 11px; }
    .details { padding: 12px 6px 4px 36px; }
    @media (max-width: 1100px) { .ligne { grid-template-columns: 1fr 1fr; } .ordre { display: none; } }
    @media (max-width: 520px) { .ligne { grid-template-columns: 1fr; } .details { padding-left: 4px; } }
  `],
})
export class ColumnEditor {
  private readonly api = inject(ApiService);
  readonly colonnes = model.required<ColumnDef[]>();
  readonly tableCode = input<string>('');
  protected readonly tables = signal<TableSummary[]>([]);
  protected readonly ouverte = signal(-1);
  protected readonly roles = ROLES;
  protected readonly types = TYPES;
  private clesManuelles = new Set<number>();

  constructor() {
    this.api.tables().subscribe(t => this.tables.set(t));
  }

  protected maj(i: number, p: Partial<ColumnDef>): void {
    if (p.key !== undefined) this.clesManuelles.add(i);
    const cols = [...this.colonnes()];
    let c = { ...cols[i], ...p };
    if (p.role && p.role !== 'ATTRIBUTE') c = { ...c, dataType: 'STRING' as DataType, refTableCode: null };
    if (p.role === 'CODE') c.required = true;
    if (p.dataType && p.dataType !== 'CODE_REF') c.refTableCode = null;
    // Un rôle structurant (code, libellés) n'appartient qu'à une colonne
    if (p.role && p.role !== 'ATTRIBUTE') cols.forEach((x, j) => { if (j !== i && x.role === p.role) cols[j] = { ...x, role: 'ATTRIBUTE' as Role }; });
    cols[i] = c;
    this.colonnes.set(cols);
  }

  /** Propose une clé (camelCase sans accents) tant qu'elle n'a pas été saisie à la main. */
  protected libelle(i: number, v: string): void {
    const p: Partial<ColumnDef> = { labelFr: v };
    if (!this.clesManuelles.has(i) && !this.colonnes()[i].xmlTag) p.key = ColumnEditor.cle(v);
    const cols = [...this.colonnes()];
    cols[i] = { ...cols[i], ...p };
    this.colonnes.set(cols);
  }

  protected ajouter(): void {
    this.colonnes.set([...this.colonnes(), { key: '', labelFr: '', role: 'ATTRIBUTE', dataType: 'STRING', required: false }]);
    this.ouverte.set(-1);
  }

  protected retirer(i: number): void {
    this.colonnes.set(this.colonnes().filter((_, j) => j !== i));
  }

  protected deplacer(i: number, d: number): void {
    const cols = [...this.colonnes()];
    [cols[i], cols[i + d]] = [cols[i + d], cols[i]];
    this.colonnes.set(cols);
  }

  static cle(libelle: string): string {
    const mots = libelle.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^A-Za-z0-9]+/g, ' ').trim().split(' ').filter(Boolean);
    const k = mots.map((m, j) => (j ? m[0].toUpperCase() + m.slice(1).toLowerCase() : m.toLowerCase())).join('');
    return /^[A-Za-z]/.test(k) ? k.slice(0, 60) : k ? 'c' + k.slice(0, 59) : '';
  }
}
