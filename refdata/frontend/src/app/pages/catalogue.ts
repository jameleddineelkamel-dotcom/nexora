import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiService, erreurs } from '../core/api.service';
import { Category, Phase, SOURCES, TableSummary } from '../core/models';
import { I18N, I18n } from '../core/i18n';
import { AuthService, DROITS } from '../core/auth.service';
import { Icon } from '../shared/icon';
import { Bsp, Ring } from '../shared/widgets';

const plier = (s: string) => s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

@Component({
  selector: 'nx-catalogue',
  imports: [RouterLink, Icon, Bsp, Ring, ...I18N],
  template: `
    <div class="between">
      <div>
        <h1>{{ 'Catalogue des tables de référence' | t }}</h1>
        <p class="muted">{{ '{0} table(s) · rapport « Référentiel Commun » (ISO 19115), recommandations UN/CEFACT, normes CEMAC et nationales.' | t: filtrees().length }}</p>
      </div>
      @if (auth.a(droits.structure)) { <a routerLink="/nouvelle-table" class="btn primary"><nx-icon name="plus" /> {{ 'Nouvelle table' | t }}</a> }
    </div>

    <div class="mise-en-page">
      <aside class="card pad arbre">
        <button class="noeud" [class.on]="!category()" (click)="choisir(null)"><nx-icon name="layers" /> <span class="grow">{{ 'Toutes les catégories' | t }}</span>
          <span class="muted small">{{ tables().length }}</span></button>
        @for (c of racines(); track c.code) {
          <button class="noeud" [class.on]="category() === c.code" (click)="choisir(c.code)" [style.--c]="c.color">
            <span class="puce"><nx-icon [name]="c.icon ?? 'layers'" [size]="14" /></span> <span class="grow">{{ c.labelFr | lib: c.labelEn }}</span>
            <span class="muted small">{{ compte(c.code) }}</span></button>
          @for (s of enfants(c.code); track s.code) {
            <button class="noeud sous" [class.on]="category() === s.code" (click)="choisir(s.code)" [style.--c]="s.color">
              <span class="puce"><nx-icon [name]="s.icon ?? 'layers'" [size]="13" /></span> <span class="grow">{{ s.labelFr | lib: s.labelEn }}</span>
              <span class="muted small">{{ s.tableCount }}</span></button>
          }
        }
      </aside>

      <section class="stack">
        <div class="card pad filtres">
          <div class="field grow"><input class="input" [placeholder]="'Filtrer par nom, code, standard (ex. Rec. 20, ISO 4217, CEMAC)…' | t"
            [value]="texte()" (input)="texte.set($any($event.target).value)" [attr.aria-label]="'Filtrer' | t" /></div>
          <select class="input" (change)="source.set($any($event.target).value)" [attr.aria-label]="'Source' | t">
            <option value="">{{ 'Toutes sources' | t }}</option>
            @for (s of sources; track s.code) { <option [value]="s.code" [selected]="s.code === source()">{{ s.label | t }}</option> }
          </select>
          <div class="phases" role="group" aria-label="Buy-Ship-Pay">
            @for (p of ['BUY', 'SHIP', 'PAY']; track p) {
              <button class="btn sm" [class.on]="phase() === p" [attr.data-p]="p" (click)="phase.set(phase() === p ? '' : $any(p))">{{ p }}</button>
            }
          </div>
          <select class="input" (change)="contenu.set($any($event.target).value)" [attr.aria-label]="'Contenu' | t">
            <option value="">{{ 'Tout contenu' | t }}</option><option value="DATA">{{ 'Alimentées' | t }}</option><option value="EMPTY">{{ 'À alimenter' | t }}</option>
            <option value="SIMPLE">{{ 'Simples' | t }}</option><option value="COMPLEXE">{{ 'Complexes' | t }}</option>
          </select>
          <div class="vues">
            <button class="btn icon sm" [class.on]="vue() === 'cartes'" (click)="vue.set('cartes')" [attr.aria-label]="'Cartes' | t"><nx-icon name="grid" /></button>
            <button class="btn icon sm" [class.on]="vue() === 'liste'" (click)="vue.set('liste')" [attr.aria-label]="'Liste' | t"><nx-icon name="table" /></button>
          </div>
        </div>

        @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
        @if (chargement()) {
          <div class="grid k3">@for (i of [1, 2, 3, 4, 5, 6]; track i) { <div class="card pad"><div class="skeleton" style="height: 96px"></div></div> }</div>
        } @else if (vue() === 'cartes') {
          <div class="grid k3">
            @for (t of filtrees(); track t.code) {
              <a class="card pad carte" [routerLink]="['/tables', t.code]" [style.--c]="couleur(t.categoryCode)">
                <div class="between"><span class="code-tag">{{ t.code }}</span><nx-ring [value]="t.completeness" [size]="34" /></div>
                <h3>{{ t.nameFr | lib: t.nameEn }}</h3>
                <p class="muted small std">{{ premiereLigne(t.standards) || t.description || '—' }}</p>
                <div class="between pied">
                  <span class="row" style="gap: 6px">
                    @if (t.entryCount) { <span class="chip ok">{{ '{0} codes' | t: (t.entryCount | num) }}</span> }
                    @else { <span class="chip warn">{{ 'À alimenter' | t }}</span> }
                    <span class="chip">{{ (t.contentType === 'COMPLEXE' ? 'Complexe' : 'Simple') | t }}</span>
                  </span>
                  <nx-bsp [phases]="t.bspPhases" />
                </div>
              </a>
            } @empty { <div class="card empty">{{ 'Aucune table ne correspond à ces filtres.' | t }}</div> }
          </div>
        } @else {
          <div class="card scroll-x">
            <table class="datagrid">
              <thead><tr><th>N°</th><th>{{ 'Code' | t }}</th><th>{{ 'Nom' | t }}</th><th>{{ 'Standards' | t }}</th><th>{{ 'Source' | t }}</th><th>BSP</th><th>{{ 'Codes' | t }}</th><th>ISO 19115</th></tr></thead>
              <tbody>
                @for (t of filtrees(); track t.code) {
                  <tr (click)="ouvrir(t.code)">
                    <td class="muted">{{ t.number }}</td><td><span class="code-tag">{{ t.code }}</span></td><td><b>{{ t.nameFr | lib: t.nameEn }}</b></td>
                    <td class="muted small">{{ premiereLigne(t.standards) }}</td><td class="small">{{ libelleSource(t.source) | t }}</td>
                    <td><nx-bsp [phases]="t.bspPhases" /></td><td class="mono">{{ t.entryCount | num }}</td>
                    <td><nx-ring [value]="t.completeness" [size]="30" /></td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
    </div>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 16px; }
    p { margin: 4px 0 0; }
    .mise-en-page { display: grid; grid-template-columns: 270px 1fr; gap: 16px; align-items: start; }
    .arbre { position: sticky; top: 84px; display: flex; flex-direction: column; gap: 2px; padding: 10px; }
    .noeud { display: flex; align-items: center; gap: 9px; padding: 8px 10px; border: 0; border-radius: 9px; background: none; font: inherit;
      color: var(--text); text-align: left; cursor: pointer; }
    .noeud:hover { background: var(--chip); }
    .noeud.on { background: var(--accent-soft); color: var(--accent); font-weight: 650; }
    .noeud.sous { padding-left: 26px; font-size: 13px; }
    .puce { width: 24px; height: 24px; border-radius: 7px; display: grid; place-items: center; color: #fff; background: var(--c, var(--accent)); }
    .filtres { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; padding: 12px; }
    .filtres select { width: auto; }
    .phases, .vues { display: flex; gap: 4px; }
    .phases .btn.on[data-p=BUY] { background: var(--buy); color: #fff; border-color: var(--buy); }
    .phases .btn.on[data-p=SHIP] { background: var(--ship); color: #fff; border-color: var(--ship); }
    .phases .btn.on[data-p=PAY] { background: var(--pay); color: #fff; border-color: var(--pay); }
    .vues .btn.on { background: var(--accent-soft); color: var(--accent); }
    .carte { display: flex; flex-direction: column; gap: 8px; color: var(--text); border-top: 3px solid var(--c, var(--accent)); transition: transform .15s, box-shadow .15s; }
    .carte:hover { text-decoration: none; transform: translateY(-2px); box-shadow: var(--shadow-lg); }
    .carte h3 { font-size: 15px; }
    .std { margin: 0; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; min-height: 36px; }
    .pied { margin-top: auto; }
    @media (max-width: 980px) { .mise-en-page { grid-template-columns: 1fr; } .arbre { position: static; } }
  `],
})
export class CataloguePage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthService);
  protected readonly droits = DROITS;
  readonly category = input<string | null>(null);

  protected readonly categories = signal<Category[]>([]);
  protected readonly tables = signal<TableSummary[]>([]);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);
  protected readonly texte = signal('');
  protected readonly source = signal('');
  protected readonly phase = signal<Phase | ''>('');
  protected readonly contenu = signal('');
  protected readonly vue = signal<'cartes' | 'liste'>('cartes');
  protected readonly sources = SOURCES;

  protected readonly racines = computed(() => this.categories().filter(c => !c.parentCode));
  protected readonly filtrees = computed(() => {
    const cat = this.category();
    const membres = cat ? new Set([cat, ...this.categories().filter(c => c.parentCode === cat).map(c => c.code)]) : null;
    const q = plier(this.texte().trim());
    return this.tables().filter(t =>
      (!membres || membres.has(t.categoryCode)) && (!this.source() || t.source === this.source())
      && (!this.phase() || t.bspPhases.includes(this.phase() as Phase))
      && (!this.contenu() || (this.contenu() === 'DATA' ? t.entryCount > 0 : this.contenu() === 'EMPTY' ? t.entryCount === 0 : t.contentType === this.contenu()))
      && (!q || plier(`${t.code} ${t.nameFr} ${t.nameEn ?? ''} ${t.standards ?? ''} ${t.description ?? ''} ${t.number ?? ''}`).includes(q)))
      .sort((a, b) => (a.number ?? '').localeCompare(b.number ?? '', undefined, { numeric: true })
        || this.i18n.libelle(a.nameFr, a.nameEn).localeCompare(this.i18n.libelle(b.nameFr, b.nameEn)));
  });

  constructor() {
    this.api.categories().subscribe(c => this.categories.set(c));
    this.api.tables().subscribe({
      next: t => { this.tables.set(t); this.chargement.set(false); },
      error: e => { this.erreur.set(erreurs(e).join(' ')); this.chargement.set(false); },
    });
    effect(() => { this.category(); window.scrollTo({ top: 0 }); });
  }

  protected enfants(code: string): Category[] { return this.categories().filter(c => c.parentCode === code); }
  protected compte(code: string): number {
    const m = new Set([code, ...this.enfants(code).map(c => c.code)]);
    return this.tables().filter(t => m.has(t.categoryCode)).length;
  }
  protected couleur(code: string): string | null { return this.categories().find(c => c.code === code)?.color ?? null; }
  protected choisir(code: string | null): void { this.router.navigate(code ? ['/catalogue', code] : ['/catalogue']); }
  protected ouvrir(code: string): void { this.router.navigate(['/tables', code]); }
  protected premiereLigne(s: string | null): string { return (s ?? '').split('\n')[0]; }
  protected libelleSource(s: string): string { return SOURCES.find(x => x.code === s)?.label ?? s; }
}
