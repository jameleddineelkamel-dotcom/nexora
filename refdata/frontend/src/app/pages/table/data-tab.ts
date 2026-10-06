import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { ApiService, erreurs } from '../../core/api.service';
import { ColumnDef, Entry, Page, TableDef } from '../../core/models';
import { I18N, I18n } from '../../core/i18n';
import { Icon } from '../../shared/icon';
import { EntryDrawer } from './entry-drawer';

@Component({
  selector: 'nx-data-tab',
  imports: [Icon, EntryDrawer, ...I18N],
  template: `
    <div class="card pad barre-outils">
      <div class="recherche">
        <nx-icon name="search" />
        <input [value]="q()" (input)="chercher($any($event.target).value)" [placeholder]="'Rechercher un code ou un libellé (sans accents)…' | t" [attr.aria-label]="'Rechercher' | t" />
      </div>
      <div class="statuts" role="group" [attr.aria-label]="'Statut' | t">
        @for (s of statuts; track s.code) {
          <button class="btn sm" [class.on]="statut() === s.code" (click)="statut.set(s.code); page.set(0)">{{ s.label | t }}</button>
        }
      </div>
      <label class="date" [title]="'Afficher les codes valides à cette date (voyage dans le temps)' | t">
        <nx-icon name="calendar" [size]="16" /> {{ 'Valide au' | t }}
        <input class="input" type="date" [value]="date()" (input)="date.set($any($event.target).value); page.set(0)" />
      </label>
      <span class="grow"></span>
      <button class="btn primary" (click)="ouvrir(null)" [disabled]="table().status === 'ARCHIVED'"><nx-icon name="plus" /> {{ 'Nouveau code' | t }}</button>
    </div>

    @if (hierarchique()) {
      <nav class="fil small">
        <button class="btn ghost sm" (click)="racine()"><nx-icon name="tree" [size]="15" /> {{ 'Racine' | t }}</button>
        @for (p of chemin(); track p; let i = $index) {
          <nx-icon name="chevron" [size]="13" /><button class="btn ghost sm" (click)="remonter(i)">{{ p }}</button>
        }
        <label class="check muted"><input type="checkbox" [checked]="aplati()" (change)="aplati.set($any($event.target).checked); page.set(0)" /> {{ 'Vue à plat' | t }}</label>
      </nav>
    }

    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
    <div class="card scroll-x">
      <table class="datagrid">
        <thead><tr>
          <th (click)="trier('code')">{{ libelleCol(colonnes().code, 'Code') }} {{ fleche('code') }}</th>
          @if (colonnes().fr) { <th (click)="trier('labelFr')">{{ libelleCol(colonnes().fr) }} {{ fleche('labelFr') }}</th> }
          @if (colonnes().en) { <th (click)="trier('labelEn')">{{ libelleCol(colonnes().en) }} {{ fleche('labelEn') }}</th> }
          @for (c of colonnes().attrs; track c.key) { <th>{{ libelleCol(c) }}</th> }
          @if (table().parentTableCode && !hierarchique()) { <th>{{ 'Parent' | t }}</th> }
          <th (click)="trier('validTo')">{{ 'Validité' | t }} {{ fleche('validTo') }}</th>
          <th>{{ 'Statut' | t }}</th>
        </tr></thead>
        <tbody>
          @for (e of resultat()?.items ?? []; track e.code) {
            <tr [class.invalid]="e.status === 'INVALID'" (click)="ouvrir(e)">
              <td><span class="code-tag">{{ e.code }}</span>
                @if (hierarchique() && e.childCount) {
                  <button class="btn ghost sm enfants" (click)="$event.stopPropagation(); descendre(e.code)" [title]="'Voir les sous-codes' | t">
                    <nx-icon name="tree" [size]="14" /> {{ e.childCount }}</button>
                }
              </td>
              @if (colonnes().fr) { <td>{{ e.labelFr }}</td> }
              @if (colonnes().en) { <td class="muted">{{ e.labelEn }}</td> }
              @for (c of colonnes().attrs; track c.key) { <td class="small attr">{{ valeur(e, c) }}</td> }
              @if (table().parentTableCode && !hierarchique()) { <td class="mono small">{{ e.parentCode }}</td> }
              <td class="small muted nowrap">{{ periode(e) }}</td>
              <td><span class="chip" [class.ok]="e.status === 'ACTIVE'" [class.warn]="e.status === 'INVALID'">{{ (e.status === 'ACTIVE' ? 'Actif' : 'Invalidé') | t }}</span></td>
            </tr>
          } @empty {
            <tr><td [attr.colspan]="10" class="empty">
              @if (chargement()) { {{ 'Chargement…' | t }} }
              @else if (table().entryCount === 0) {
                {{ 'Cette table n\\'est pas encore alimentée. Ajoutez des codes un par un ou chargez un fichier depuis l\\'onglet « Import / export ».' | t }}
              } @else { {{ 'Aucun code ne correspond à ces critères.' | t }} }
            </td></tr>
          }
        </tbody>
      </table>
    </div>
    @if (resultat(); as r) {
      <div class="between pagination">
        <span class="muted small">{{ '{0} code(s) · page {1} / {2}' | t: (r.total | num) : r.page + 1 : pages() }}</span>
        <div class="row">
          <button class="btn sm" (click)="page.set(page() - 1)" [disabled]="page() === 0"><nx-icon name="back" [size]="15" /> {{ 'Précédent' | t }}</button>
          <button class="btn sm" (click)="page.set(page() + 1)" [disabled]="page() + 1 >= pages()">{{ 'Suivant' | t }} <nx-icon name="chevron" [size]="15" /></button>
        </div>
      </div>
    }

    @if (edition() !== undefined) {
      <nx-entry-drawer [table]="table()" [entree]="edition() ?? null" (fermer)="edition.set(undefined)" (enregistre)="enregistre($event)" />
    }
    @if (toast()) { <div class="toast"><nx-icon name="check" /> {{ toast() }}</div> }
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 12px; }
    .barre-outils { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 12px; }
    .recherche { display: flex; align-items: center; gap: 8px; flex: 1 1 280px; height: 38px; padding: 0 12px; border: 1px solid var(--line);
      border-radius: var(--radius-sm); background: var(--surface); color: var(--muted); }
    .recherche:focus-within { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
    .recherche input { flex: 1; border: 0; outline: none; background: none; font: inherit; color: var(--text); }
    .statuts { display: flex; gap: 4px; }
    .statuts .on { background: var(--accent-soft); color: var(--accent); border-color: var(--accent); }
    .date { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--muted); font-weight: 600; }
    .date .input { width: 150px; }
    .fil { display: flex; align-items: center; gap: 2px; flex-wrap: wrap; }
    .fil .check { margin-left: auto; }
    .enfants { margin-left: 6px; height: 24px; }
    .attr { max-width: 280px; overflow: hidden; text-overflow: ellipsis; }
    .nowrap { white-space: nowrap; }
    th { cursor: pointer; }
  `],
})
export class DataTab {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(I18n);
  readonly table = input.required<TableDef>();
  readonly recherche = input('');

  protected readonly q = signal('');
  protected readonly statut = signal('ALL');
  protected readonly date = signal('');
  protected readonly page = signal(0);
  protected readonly tri = signal<{ cle: string; desc: boolean }>({ cle: 'code', desc: false });
  protected readonly chemin = signal<string[]>([]);
  protected readonly aplati = signal(false);
  protected readonly resultat = signal<Page<Entry> | null>(null);
  protected readonly chargement = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly edition = signal<Entry | null | undefined>(undefined);
  protected readonly toast = signal<string | null>(null);
  protected readonly statuts = [{ code: 'ALL', label: 'Tous' }, { code: 'ACTIVE', label: 'Actifs' }, { code: 'INVALID', label: 'Invalidés' }];
  private minuterie?: ReturnType<typeof setTimeout>;

  /** Hiérarchie interne (ex. moyens de transport, subdivisions) : navigation par niveau. */
  protected readonly hierarchique = computed(() => this.table().parentTableCode === this.table().code && !this.q() && !this.aplati());
  protected readonly pages = computed(() => Math.max(1, Math.ceil((this.resultat()?.total ?? 0) / 50)));
  /** Colonnes affichées : les attributs renseignés sur la page courante passent en premier (5 au plus). */
  protected readonly colonnes = computed(() => {
    const cols = this.table().columns;
    const items = this.resultat()?.items ?? [];
    const attrs = cols.filter(c => c.role === 'ATTRIBUTE');
    const remplis = attrs.filter(c => items.some(e => e.attributes?.[c.key] !== undefined && e.attributes?.[c.key] !== ''));
    const vides = items.length ? [] : attrs.filter(c => !remplis.includes(c));
    const en = cols.find(c => c.role === 'LABEL_EN');
    return {
      code: cols.find(c => c.role === 'CODE'), fr: cols.find(c => c.role === 'LABEL_FR'),
      en: en && (!items.length || items.some(e => e.labelEn)) ? en : undefined,
      attrs: [...remplis, ...vides].slice(0, 5),
    };
  });

  constructor() {
    effect(() => {
      const r = this.recherche();
      untracked(() => { this.q.set(r); this.page.set(0); this.chemin.set([]); });
    });
    effect(() => {
      const f = this.filtre();
      const code = this.table().code;
      untracked(() => this.charger(code, f));
    });
  }

  private filtre(): Record<string, unknown> {
    return {
      q: this.q(), status: this.statut(), validAt: this.date(), page: this.page(), size: 50, sort: this.tri().cle, desc: this.tri().desc,
      parent: this.hierarchique() ? this.chemin().at(-1) : undefined, roots: this.hierarchique() && this.chemin().length === 0,
    };
  }

  private charger(code: string, f: Record<string, unknown>): void {
    this.chargement.set(true);
    this.api.entrees(code, f).subscribe({
      next: p => { this.resultat.set(p); this.chargement.set(false); this.erreur.set(null); },
      error: e => { this.erreur.set(erreurs(e).join(' ')); this.chargement.set(false); },
    });
  }

  protected libelleCol(c: ColumnDef | undefined, defaut = ''): string {
    return c ? this.i18n.libelle(c.labelFr, c.labelEn) : this.i18n.t(defaut);
  }

  protected chercher(v: string): void {
    clearTimeout(this.minuterie);
    this.minuterie = setTimeout(() => { this.q.set(v); this.page.set(0); }, 220);
  }

  protected trier(cle: string): void {
    const t = this.tri();
    this.tri.set({ cle, desc: t.cle === cle ? !t.desc : false });
  }

  protected fleche(cle: string): string {
    return this.tri().cle === cle ? (this.tri().desc ? '↓' : '↑') : '';
  }

  protected descendre(code: string): void { this.chemin.set([...this.chemin(), code]); this.page.set(0); }
  protected remonter(i: number): void { this.chemin.set(this.chemin().slice(0, i + 1)); this.page.set(0); }
  protected racine(): void { this.chemin.set([]); this.page.set(0); }

  protected ouvrir(e: Entry | null): void { this.edition.set(e); }

  protected enregistre(e: Entry): void {
    this.edition.set(undefined);
    this.toast.set(this.i18n.t('{0} enregistré', e.code));
    setTimeout(() => this.toast.set(null), 2200);
    this.charger(this.table().code, this.filtre());
  }

  protected valeur(e: Entry, c: ColumnDef): string {
    const v = e.attributes?.[c.key];
    if (v === null || v === undefined) return '';
    if (typeof v === 'boolean') return this.i18n.t(v ? 'Oui' : 'Non');
    return String(v);
  }

  protected periode(e: Entry): string {
    if (!e.validFrom && !e.validTo) return this.i18n.t('Permanente');
    return `${e.validFrom ? this.i18n.t('du {0}', e.validFrom) : ''} ${e.validTo ? this.i18n.t('au {0}', e.validTo) : ''}`.trim();
  }
}
