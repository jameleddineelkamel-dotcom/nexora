import { Component, computed, effect, input, output, signal } from '@angular/core';
import { I18N } from '../core/i18n';
import { Icon } from './icon';

export interface Colonne {
  cle: string;
  libelle: string;
  type?: 'text' | 'number' | 'bool' | 'select' | 'list' | 'textarea';
  options?: { valeur: string; libelle: string }[];
  largeur?: number;
  mono?: boolean;
  aide?: string;
}

type Ligne = Record<string, unknown>;

/**
 * Éditeur de tableau du studio : lignes typées (texte, nombre, case, liste de valeurs, liste séparée par des virgules),
 * ajout, suppression, réordonnancement, puis enregistrement de la partie de version en une fois (avec motif).
 */
@Component({
  selector: 'nx-grille',
  imports: [Icon, ...I18N],
  template: `
    <div class="scroll-x">
      <table class="datagrid grille">
        <thead><tr>
          @for (c of colonnes(); track c.cle) { <th [style.min-width.px]="c.largeur ?? 120" [title]="c.aide ?? ''">{{ c.libelle | t }}</th> }
          @if (!lectureSeule()) { <th></th> }
        </tr></thead>
        <tbody>
          @for (l of lignes(); track $index; let i = $index) {
            <tr [class.sel]="selection() === i" (click)="selection.set(i); choisie.emit(l)">
              @for (c of colonnes(); track c.cle) {
                <td>
                  @switch (c.type ?? 'text') {
                    @case ('bool') { <input type="checkbox" [checked]="!!l[c.cle]" [disabled]="lectureSeule()" (change)="poser(i, c, $any($event.target).checked)" /> }
                    @case ('select') {
                      <select class="input" [disabled]="lectureSeule()" (change)="poser(i, c, $any($event.target).value)">
                        <option value=""></option>
                        @for (o of c.options ?? []; track o.valeur) { <option [value]="o.valeur" [selected]="l[c.cle] === o.valeur">{{ o.libelle | t }}</option> }
                      </select>
                    }
                    @case ('textarea') { <textarea class="input" rows="2" [disabled]="lectureSeule()" [value]="texte(l[c.cle])" (input)="poser(i, c, $any($event.target).value)"></textarea> }
                    @default {
                      <input class="input" [class.mono]="c.mono" [type]="c.type === 'number' ? 'number' : 'text'" [disabled]="lectureSeule()"
                        [value]="texte(l[c.cle])" (input)="poser(i, c, $any($event.target).value)" />
                    }
                  }
                </td>
              }
              @if (!lectureSeule()) {
                <td class="actions">
                  <button type="button" class="btn icon ghost sm" (click)="deplacer(i, -1)" [disabled]="i === 0" [attr.aria-label]="'Monter' | t"><nx-icon name="back" [size]="14" style="transform: rotate(90deg)" /></button>
                  <button type="button" class="btn icon ghost sm" (click)="deplacer(i, 1)" [disabled]="i === lignes().length - 1" [attr.aria-label]="'Descendre' | t"><nx-icon name="chevron" [size]="14" style="transform: rotate(90deg)" /></button>
                  <button type="button" class="btn icon ghost sm danger" (click)="retirer(i)" [attr.aria-label]="'Supprimer' | t"><nx-icon name="trash" [size]="14" /></button>
                </td>
              }
            </tr>
          } @empty { <tr><td [attr.colspan]="colonnes().length + 1" class="empty small">{{ 'Aucune ligne.' | t }}</td></tr> }
        </tbody>
      </table>
    </div>
    @if (!lectureSeule()) {
      <div class="row pied">
        <button type="button" class="btn" (click)="ajouter()"><nx-icon name="plus" /> {{ 'Ajouter' | t }}</button>
        <span class="grow"></span>
        <input class="input motif" [value]="motif()" (input)="motif.set($any($event.target).value)" [placeholder]="'Motif de la modification' | t" />
        <button type="button" class="btn primary" (click)="enregistrer.emit({ lignes: normaliser(), motif: motif() }); motif.set('')" [disabled]="!modifie()">
          <nx-icon name="save" /> {{ 'Enregistrer' | t }}</button>
      </div>
    }
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 10px; }
    .grille td { padding: 5px 6px; vertical-align: middle; }
    .grille .input { height: 32px; padding: 0 8px; font-size: 13px; }
    .grille textarea.input { height: auto; min-height: 32px; padding: 5px 8px; }
    .grille tbody tr { cursor: default; }
    .grille tr.sel td { background: var(--accent-soft); }
    .actions { white-space: nowrap; }
    .motif { max-width: 360px; }
  `],
})
export class Grille {
  readonly colonnes = input.required<Colonne[]>();
  readonly donnees = input.required<Ligne[]>();
  readonly modele = input<Ligne>({});
  readonly lectureSeule = input(false);
  readonly enregistrer = output<{ lignes: Ligne[]; motif: string }>();
  readonly choisie = output<Ligne>();
  protected readonly lignes = signal<Ligne[]>([]);
  protected readonly selection = signal<number | null>(null);
  protected readonly motif = signal('');
  private readonly origine = signal('');
  protected readonly modifie = computed(() => JSON.stringify(this.normaliser()) !== this.origine());

  constructor() {
    effect(() => {
      const d = this.donnees().map(l => ({ ...l }));
      this.lignes.set(d);
      this.origine.set(JSON.stringify(this.normaliserListe(d)));
    });
  }

  protected texte(v: unknown): string {
    if (v === null || v === undefined) return '';
    return Array.isArray(v) ? v.join(', ') : String(v);
  }

  protected poser(i: number, c: Colonne, v: unknown): void {
    const l = this.lignes().map(x => ({ ...x }));
    l[i][c.cle] = c.type === 'number' ? (v === '' ? null : Number(v)) : c.type === 'list' ? String(v).split(',').map(s => s.trim()).filter(Boolean) : v;
    this.lignes.set(l);
  }

  protected ajouter(): void {
    const ordre = (this.lignes().length + 1) * 10;
    this.lignes.set([...this.lignes(), { ...structuredClone(this.modele()), sortOrder: ordre }]);
    this.selection.set(this.lignes().length - 1);
  }

  protected retirer(i: number): void {
    this.lignes.set(this.lignes().filter((_, k) => k !== i));
  }

  protected deplacer(i: number, d: number): void {
    const l = [...this.lignes()];
    [l[i], l[i + d]] = [l[i + d], l[i]];
    this.lignes.set(l);
  }

  /** Ordre d'affichage recalculé (sortOrder de 10 en 10). */
  protected normaliser(): Ligne[] {
    return this.normaliserListe(this.lignes());
  }

  private normaliserListe(l: Ligne[]): Ligne[] {
    return l.map((x, i) => ('sortOrder' in x || this.colonnes().some(c => c.cle === 'sortOrder') ? { ...x, sortOrder: (i + 1) * 10 } : x));
  }
}
