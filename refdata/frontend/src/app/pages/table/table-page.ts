import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { ApiService, erreurs } from '../../core/api.service';
import { Category, SOURCES, TableDef } from '../../core/models';
import { I18N, I18n } from '../../core/i18n';
import { Icon } from '../../shared/icon';
import { Bsp, Ring } from '../../shared/widgets';
import { HistoryList } from '../../shared/history-list';
import { DataTab } from './data-tab';
import { StructureTab } from './structure-tab';
import { MetadataTab } from './metadata-tab';
import { IoTab } from './io-tab';
import { ApiTab } from './api-tab';

type Onglet = 'donnees' | 'structure' | 'metadonnees' | 'historique' | 'chargement' | 'api';

@Component({
  selector: 'nx-table-page',
  imports: [RouterLink, DatePipe, Icon, Bsp, Ring, HistoryList, DataTab, StructureTab, MetadataTab, IoTab, ApiTab, ...I18N],
  template: `
    @if (erreur()) {
      <div class="alert err"><nx-icon name="alert" /> {{ erreur() }} <a routerLink="/catalogue">{{ 'Retour au catalogue' | t }}</a></div>
    } @else if (table(); as t) {
      <nav class="fil small">
        <a routerLink="/catalogue">{{ 'Catalogue' | t }}</a> <nx-icon name="chevron" [size]="13" />
        @if (categorie(); as c) { <a [routerLink]="['/catalogue', c.code]">{{ c.labelFr | lib: c.labelEn }}</a> <nx-icon name="chevron" [size]="13" /> }
        <span class="muted">{{ t.code }}</span>
      </nav>

      <header class="card pad entete" [style.--c]="categorie()?.color">
        <div class="titre">
          <div class="row">
            <span class="code-tag">{{ t.code }}</span>
            @if (t.number) { <span class="chip">{{ 'Fiche {0}' | t: t.number }}</span> }
            <span class="chip">{{ libelleSource(t.source) | t }}</span>
            <span class="chip">{{ (t.contentType === 'COMPLEXE' ? 'Contenu complexe' : 'Contenu simple') | t }}</span>
            @if (t.status !== 'ACTIVE') { <span class="chip warn">{{ (t.status === 'ARCHIVED' ? 'Archivée' : 'Brouillon') | t }}</span> }
            <nx-bsp [phases]="t.bspPhases" />
          </div>
          <h1>{{ t.nameFr | lib: t.nameEn }}</h1>
          @if (sousTitre(); as s) { <p class="muted">{{ s }}</p> }
          @if (t.standards) { <p class="std"><nx-icon name="tag" [size]="14" /> {{ t.standards }}</p> }
        </div>
        <div class="chiffres">
          <div><b>{{ t.entryCount | num }}</b><span>{{ 'codes' | t }}</span></div>
          <div><b>{{ t.activeCount | num }}</b><span>{{ 'actifs' | t }}</span></div>
          <div><b>{{ t.columns.length }}</b><span>{{ 'colonnes' | t }}</span></div>
          <div class="anneau"><nx-ring [value]="t.completeness" [size]="52" /><span>ISO 19115</span></div>
        </div>
      </header>

      <div class="tabs" role="tablist">
        @for (o of onglets; track o.code) {
          <button class="tab" role="tab" [class.on]="actif() === o.code" [attr.aria-selected]="actif() === o.code" (click)="changer(o.code)">
            <nx-icon [name]="o.icone" [size]="16" /> {{ o.libelle | t }}
          </button>
        }
      </div>

      @switch (actif()) {
        @case ('donnees') { <nx-data-tab [table]="t" [recherche]="q() ?? ''" /> }
        @case ('structure') { <nx-structure-tab [table]="t" (enregistree)="recharger($event)" /> }
        @case ('metadonnees') { <nx-metadata-tab [table]="t" [categories]="categories()" (enregistree)="recharger($event)" /> }
        @case ('historique') { <nx-history-list [table]="t.code" /> }
        @case ('chargement') { <nx-io-tab [table]="t" (importe)="recharger()" /> }
        @case ('api') { <nx-api-tab [table]="t" /> }
      }
      <p class="muted small pied">{{ 'Créée le {0} · modifiée le {1}' | t: (t.createdAt | date: 'dd/MM/yyyy') : (t.updatedAt | date: 'dd/MM/yyyy HH:mm') }}</p>
    } @else {
      <div class="card pad"><div class="skeleton" style="height: 120px"></div></div>
    }
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 14px; }
    .fil { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
    .entete { display: flex; justify-content: space-between; gap: 20px; flex-wrap: wrap; border-left: 5px solid var(--c, var(--accent)); }
    .titre { flex: 1 1 420px; display: flex; flex-direction: column; gap: 6px; }
    .titre p { margin: 0; }
    .std { display: flex; gap: 6px; align-items: flex-start; white-space: pre-line; font-size: 13px; color: var(--muted); }
    .chiffres { display: flex; gap: 22px; align-items: center; }
    .chiffres div { display: flex; flex-direction: column; align-items: center; }
    .chiffres b { font-size: 22px; }
    .chiffres span { font-size: 12px; color: var(--muted); }
    .pied { margin-top: 8px; }
  `],
})
export class TablePage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);
  readonly code = input.required<string>();
  readonly onglet = input<Onglet | undefined>(undefined);
  readonly q = input<string | undefined>(undefined);
  protected readonly actif = computed<Onglet>(() => this.onglet() ?? 'donnees');

  protected readonly table = signal<TableDef | null>(null);
  protected readonly categories = signal<Category[]>([]);
  protected readonly erreur = signal<string | null>(null);
  protected readonly categorie = computed(() => this.categories().find(c => c.code === this.table()?.categoryCode) ?? null);
  /** Nom dans l'autre langue, sous le titre. */
  protected readonly sousTitre = computed(() => {
    const t = this.table();
    if (!t) return null;
    return this.i18n.langue() === 'en' && t.nameEn ? t.nameFr : t.nameEn;
  });
  protected readonly onglets: { code: Onglet; libelle: string; icone: string }[] = [
    { code: 'donnees', libelle: 'Données', icone: 'table' }, { code: 'structure', libelle: 'Structure', icone: 'layers' },
    { code: 'metadonnees', libelle: 'Métadonnées ISO 19115', icone: 'info' }, { code: 'historique', libelle: 'Historique', icone: 'history' },
    { code: 'chargement', libelle: 'Import / export', icone: 'upload' }, { code: 'api', libelle: 'API & SQL', icone: 'code' },
  ];

  constructor() {
    this.api.categories().subscribe(c => this.categories.set(c));
    effect(() => {
      const code = this.code();
      this.table.set(null);
      this.erreur.set(null);
      this.api.table(code).subscribe({ next: t => this.table.set(t), error: e => this.erreur.set(erreurs(e).join(' ')) });
    });
  }

  protected changer(o: Onglet): void {
    this.router.navigate([], { queryParams: { onglet: o === 'donnees' ? null : o, q: null }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  protected recharger(t?: TableDef): void {
    if (t) { this.table.set(t); return; }
    this.api.table(this.code()).subscribe(x => this.table.set(x));
  }

  protected libelleSource(s: string): string { return SOURCES.find(x => x.code === s)?.label ?? s; }
}
