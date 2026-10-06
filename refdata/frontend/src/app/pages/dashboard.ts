import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ApiService, erreurs } from '../core/api.service';
import { Category, Dashboard, OPERATIONS, PHASES } from '../core/models';
import { I18N } from '../core/i18n';
import { Icon } from '../shared/icon';
import { Ring, Spark } from '../shared/widgets';

@Component({
  selector: 'nx-dashboard',
  imports: [RouterLink, DatePipe, Icon, Ring, Spark, ...I18N],
  template: `
    <section class="hero">
      <div>
        <span class="eyebrow"><nx-icon name="sparkles" [size]="14" /> {{ 'NEXORA · Référentiel Commun du commerce extérieur' | t }}</span>
        <h1>{{ 'Des codes communs pour toute la chaîne logistique' | t }}</h1>
        <p>{{ 'Listes de codes internationales (ISO, UN/CEFACT, OMD), régionales (CEMAC) et nationales, décrites selon l\\'ISO 19115, historisées et exposées aux autres services de la plateforme.' | t }}</p>
        <div class="row">
          <a routerLink="/catalogue" class="btn primary"><nx-icon name="grid" /> {{ 'Explorer le catalogue' | t }}</a>
          <a routerLink="/nouvelle-table" class="btn"><nx-icon name="plus" /> {{ 'Créer une table' | t }}</a>
        </div>
      </div>
      <div class="flux" aria-hidden="true">
        @for (p of phases; track p.code) {
          <div class="etape" [attr.data-p]="p.code"><b>{{ p.label }}</b><span>{{ '{0} tables' | t: (d()?.bsp?.[p.code] ?? '–') }}</span></div>
          @if (!$last) { <nx-icon name="chevron" [size]="20" /> }
        }
      </div>
    </section>

    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }

    <div class="grid k4 kpis">
      <div class="card pad kpi"><span class="ic" style="--c: var(--blue)"><nx-icon name="table" /></span>
        <div><b>{{ d()?.tables | num }}</b><span>{{ 'tables de référence' | t }}</span><small>{{ '{0} alimentées' | t: (d()?.tablesWithData ?? 0) }}</small></div></div>
      <div class="card pad kpi"><span class="ic" style="--c: var(--cyan)"><nx-icon name="tag" /></span>
        <div><b>{{ d()?.entries | num }}</b><span>{{ 'codes' | t }}</span><small>{{ '{0} invalidés' | t: (d()?.invalidEntries ?? 0) }}</small></div></div>
      <div class="card pad kpi"><span class="ic" style="--c: var(--green)"><nx-icon name="history" /></span>
        <div><b>{{ d()?.changesLast7Days | num }}</b><span>{{ 'modifications' | t }}</span><small>{{ 'sur 7 jours' | t }}</small></div></div>
      <div class="card pad kpi"><nx-ring [value]="d()?.averageCompleteness ?? 0" [size]="46" />
        <div><b>{{ d()?.averageCompleteness ?? '–' }} %</b><span>{{ 'complétude ISO 19115' | t }}</span><small>{{ 'moyenne des fiches' | t }}</small></div></div>
    </div>

    <div class="grid k2">
      <div class="card pad">
        <div class="between card-title"><h2>{{ 'Catégories' | t }}</h2><a routerLink="/catalogue" class="small">{{ 'Tout voir' | t }}</a></div>
        <div class="cats">
          @for (c of racines(); track c.code) {
            <a class="cat" [routerLink]="['/catalogue', c.code]" [style.--c]="c.color">
              <span class="ic"><nx-icon [name]="c.icon ?? 'layers'" /></span>
              <span class="grow"><b>{{ c.labelFr | lib: c.labelEn }}</b><span class="muted small">{{ '{0} tables · {1} codes' | t: total(c).tables : (total(c).entries | num) }}</span></span>
              <nx-icon name="chevron" />
            </a>
          }
        </div>
      </div>
      <div class="card pad">
        <div class="between card-title"><h2>{{ 'Activité' | t }}</h2><span class="muted small">{{ '30 derniers jours (hors chargement initial)' | t }}</span></div>
        <nx-spark [values]="activite()" />
        <div class="recent">
          @for (h of d()?.recent ?? []; track h.id) {
            <div class="evt">
              <span class="chip" [class.ok]="h.operation === 'CREATION'" [class.warn]="h.operation === 'INVALIDATION'"
                [class.accent]="h.operation === 'MODIFICATION'">{{ ops[h.operation] | t }}</span>
              <span class="grow small"><b>{{ h.entityCode }}</b><span class="muted"> · {{ h.tableCode }}</span></span>
              <span class="muted small">{{ h.author }} · {{ h.occurredAt | date: 'dd/MM HH:mm' }}</span>
            </div>
          } @empty { <div class="muted small">{{ 'Aucune modification récente.' | t }}</div> }
        </div>
        <a routerLink="/historique" class="small">{{ 'Historique complet' | t }}</a>
      </div>
    </div>

    <div class="grid k2">
      <div class="card pad">
        <div class="card-title"><h2>{{ 'Tables les plus volumineuses' | t }}</h2></div>
        @for (t of d()?.largestTables ?? []; track t.code) {
          <a class="barre" [routerLink]="['/tables', t.code]">
            <span class="grow"><b>{{ t.nameFr | lib: t.nameEn }}</b> <span class="code-tag">{{ t.code }}</span></span>
            <span class="mono">{{ t.entryCount | num }}</span>
            <i [style.width.%]="largeur(t.entryCount)"></i>
          </a>
        }
      </div>
      <div class="card pad">
        <div class="card-title"><h2>{{ 'Fiches de métadonnées à compléter' | t }}</h2></div>
        <p class="muted small" style="margin: -6px 0 10px">{{ 'Indicateur de qualité : part des 26 rubriques ISO 19115 renseignées.' | t }}</p>
        @for (t of d()?.toComplete ?? []; track t.code) {
          <a class="barre" [routerLink]="['/tables', t.code]" [queryParams]="{ onglet: 'metadonnees' }">
            <nx-ring [value]="t.completeness" [size]="32" />
            <span class="grow"><b>{{ t.nameFr | lib: t.nameEn }}</b> <span class="code-tag">{{ t.code }}</span></span>
            <nx-icon name="edit" />
          </a>
        }
      </div>
    </div>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 18px; }
    .hero { position: relative; overflow: hidden; display: grid; grid-template-columns: 1.4fr 1fr; gap: 24px; align-items: center;
      padding: 28px 30px; border-radius: 20px; color: #fff; background: radial-gradient(circle at 85% 20%, rgba(25,195,230,.45), transparent 40%),
      radial-gradient(circle at 70% 110%, rgba(122,201,67,.45), transparent 45%), linear-gradient(120deg, #0b2a6f, #1565e0); box-shadow: var(--shadow-lg); }
    .hero h1 { font-size: 26px; margin: 8px 0; }
    .hero p { margin: 0 0 16px; opacity: .9; max-width: 640px; }
    .hero .btn:not(.primary) { background: rgba(255,255,255,.12); color: #fff; border-color: rgba(255,255,255,.3); }
    .hero .btn.primary { background: #fff; color: var(--navy); }
    .eyebrow { display: inline-flex; gap: 6px; align-items: center; font-size: 12px; font-weight: 600; padding: 4px 10px; border-radius: 999px; background: rgba(255,255,255,.14); }
    .flux { display: flex; align-items: center; justify-content: center; gap: 6px; flex-wrap: wrap; }
    .etape { display: flex; flex-direction: column; align-items: center; justify-content: center; width: 104px; height: 104px; border-radius: 26px;
      background: rgba(255,255,255,.13); border: 1px solid rgba(255,255,255,.25); backdrop-filter: blur(4px); }
    .etape b { font-size: 20px; }
    .etape span { font-size: 12px; opacity: .85; }
    .etape[data-p=BUY] { box-shadow: inset 0 -4px 0 #6fa8ff; } .etape[data-p=SHIP] { box-shadow: inset 0 -4px 0 #19c3e6; } .etape[data-p=PAY] { box-shadow: inset 0 -4px 0 #7ac943; }
    .kpi { display: flex; gap: 14px; align-items: center; }
    .kpi b { display: block; font-size: 24px; line-height: 1.1; }
    .kpi span { display: block; font-weight: 600; }
    .kpi small { color: var(--muted); }
    .kpi .ic { width: 46px; height: 46px; border-radius: 13px; display: grid; place-items: center; color: var(--c);
      background: color-mix(in srgb, var(--c) 14%, transparent); }
    .cats { display: flex; flex-direction: column; gap: 6px; }
    .cat { display: flex; align-items: center; gap: 12px; padding: 10px 12px; border-radius: 12px; color: var(--text); border: 1px solid var(--line); }
    .cat:hover { text-decoration: none; border-color: var(--c); background: color-mix(in srgb, var(--c) 6%, transparent); }
    .cat .ic { width: 36px; height: 36px; border-radius: 10px; display: grid; place-items: center; color: #fff; background: var(--c); }
    .cat b { display: block; }
    .recent { display: flex; flex-direction: column; gap: 8px; margin: 12px 0; }
    .evt { display: flex; gap: 10px; align-items: center; }
    .barre { position: relative; display: flex; align-items: center; gap: 10px; padding: 9px 10px; border-radius: 10px; color: var(--text); overflow: hidden; }
    .barre:hover { background: var(--surface-2); text-decoration: none; }
    .barre i { position: absolute; left: 0; bottom: 0; height: 3px; border-radius: 3px; background: var(--gradient); }
    @media (max-width: 900px) { .hero { grid-template-columns: 1fr; padding: 22px; } .etape { width: 84px; height: 84px; } }
  `],
})
export class DashboardPage {
  private readonly api = inject(ApiService);
  protected readonly d = signal<Dashboard | null>(null);
  protected readonly categories = signal<Category[]>([]);
  protected readonly erreur = signal<string | null>(null);
  protected readonly phases = PHASES;
  protected readonly ops = OPERATIONS;
  protected readonly racines = computed(() => this.categories().filter(c => !c.parentCode));
  protected readonly activite = computed(() => (this.d()?.activity ?? []).map(a => a.total));

  constructor() {
    forkJoin([this.api.dashboard(), this.api.categories()]).subscribe({
      next: ([d, c]) => { this.d.set(d); this.categories.set(c); },
      error: e => this.erreur.set(erreurs(e).join(' ')),
    });
  }

  /** Totaux d'une catégorie racine, sous-catégories comprises. */
  protected total(c: Category): { tables: number; entries: number } {
    const enfants = this.categories().filter(x => x.parentCode === c.code);
    return {
      tables: c.tableCount + enfants.reduce((s, x) => s + x.tableCount, 0),
      entries: c.entryCount + enfants.reduce((s, x) => s + x.entryCount, 0),
    };
  }

  protected largeur(n: number): number {
    const max = Math.max(...(this.d()?.largestTables ?? []).map(t => t.entryCount), 1);
    return Math.max(2, (Math.log10(n + 1) / Math.log10(max + 1)) * 100);
  }
}
