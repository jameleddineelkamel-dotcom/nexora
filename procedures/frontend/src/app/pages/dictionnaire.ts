import { Component, computed, inject, signal } from '@angular/core';
import { Api, Bie } from '../core/api';
import { I18N } from '../core/i18n';
import { erreurs } from '../core/session';
import { Icon } from '../shared/icon';

type Abie = { code: string; den: string; definition: string; depth: number; root: boolean };

/**
 * Dictionnaire du Supply Chain Reference Data Model UN/CEFACT D23B : catalogue des ABIE (filtré pendant la saisie),
 * recherche des BIE (DEN, propriété, définition), détail d'un ABIE et chemins depuis le message maître.
 */
@Component({
  selector: 'nx-dictionnaire',
  imports: [Icon, ...I18N],
  template: `
    <div class="page-head"><div><h1>{{ 'Dictionnaire SCRDM' | t }}</h1>
      @if (resume(); as r) { <p class="muted">UN/CEFACT {{ r.family }} {{ r.release }} — {{ '{0} ABIE, {1} BBIE, {2} ASBIE' | t: r.abies : r.bbies : r.asbies }}</p> }</div></div>
    <div class="card pad row"><nx-icon name="search" />
      <input class="input grow" [value]="q()" (input)="saisir($any($event.target).value)" (keydown.enter)="chercher()" [placeholder]="'Nom normalisé (DEN), propriété ou définition…' | t" />
      <button class="btn" (click)="chercher()">{{ 'Rechercher les propriétés' | t }}</button>
      @if (resultats()) { <button class="btn ghost" (click)="saisir('')">{{ 'Catalogue des ABIE' | t }}</button> }</div>
    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
    <div class="grid k2">
      <div class="card scroll-x liste">
        @if (resultats(); as res) {
          <table class="datagrid"><thead><tr><th>DEN</th><th>{{ 'Type' | t }}</th><th>{{ 'Card.' | t }}</th></tr></thead>
            <tbody>@for (b of res; track b.abie + b.property) {
              <tr (click)="ouvrir(b.abie)" [class.sel]="abie()?.abie?.code === b.abie"><td><b>{{ b.den }}</b><div class="muted small mono">{{ b.abie }}/{{ b.property }}</div></td><td>{{ b.kind }}</td><td>{{ b.cardinality }}</td></tr>
            } @empty { <tr><td colspan="3" class="empty small">{{ 'Aucune propriété ne correspond.' | t }}</td></tr> }</tbody></table>
        } @else {
          <table class="datagrid"><thead><tr><th>{{ 'ABIE' | t }} ({{ abiesFiltres().length }})</th><th>{{ 'Niveau' | t }}</th></tr></thead>
            <tbody>@for (a of abiesFiltres(); track a.code) {
              <tr (click)="ouvrir(a.code)" [class.sel]="abie()?.abie?.code === a.code"><td><b>{{ a.den }}</b><div class="muted small mono">{{ a.code }}</div></td>
                <td>@if (a.root) { <span class="chip info">{{ 'racine' | t }}</span> } @else { {{ a.depth }} }</td></tr>
            } @empty { <tr><td colspan="2" class="empty small">{{ chargement() ? ('Chargement…' | t) : ('Aucun ABIE ne correspond ; « Rechercher les propriétés » cherche aussi dans les BBIE et ASBIE.' | t) }}</td></tr> }</tbody></table>
        }
      </div>
      @if (abie(); as a) {
        <div class="card pad stack">
          <h2>{{ a.abie.den }}</h2><p class="muted small">{{ a.abie.definition }}</p>
          <h3>{{ 'Chemins depuis la racine' | t }}</h3>
          @for (p of a.paths; track p) { <code class="chemin">{{ p }}</code> } @empty { <span class="muted small">{{ 'Racine du message maître.' | t }}</span> }
          <h3>{{ 'Propriétés' | t }}</h3>
          <div class="props">@for (b of a.properties; track b.property) {
            <div class="prop"><span class="chip" [class.info]="b.kind === 'ASBIE'">{{ b.kind }}</span> <b class="mono small">{{ b.property }}</b> <span class="muted small">{{ b.cardinality }}</span>
              @if (b.target) { <button class="btn sm ghost" (click)="ouvrir(b.target)">→ {{ b.target }}</button> }
              <div class="muted small">{{ b.den }}</div></div>
          }</div>
        </div>
      }
    </div>
  `,
  styles: [`:host { display: flex; flex-direction: column; gap: 16px; } .chemin { padding: 5px 8px; background: var(--surface-2); border-radius: 8px; overflow-wrap: anywhere; }
    .liste { max-height: 75vh; overflow-y: auto; } tr { cursor: pointer; } tr.sel td { background: var(--surface-2); }
    .props { display: flex; flex-direction: column; gap: 6px; max-height: 60vh; overflow-y: auto; } .prop { border-bottom: 1px solid var(--line); padding-bottom: 6px; }`],
})
export class DictionnairePage {
  private readonly api = inject(Api);
  protected readonly q = signal('');
  protected readonly abies = signal<Abie[]>([]);
  protected readonly chargement = signal(true);
  /** Résultats de la recherche des propriétés ; null = affichage du catalogue des ABIE. */
  protected readonly resultats = signal<Bie[] | null>(null);
  protected readonly resume = signal<{ family: string; release: string; abies: number; bbies: number; asbies: number } | null>(null);
  protected readonly abie = signal<{ abie: { code: string; den: string; definition: string }; properties: Bie[]; paths: string[] } | null>(null);
  protected readonly erreur = signal<string | null>(null);

  protected readonly abiesFiltres = computed(() => {
    const q = this.q().trim().toLowerCase();
    const tri = [...this.abies()].sort((a, b) => Number(b.root) - Number(a.root) || a.den.localeCompare(b.den));
    return q ? tri.filter(a => (a.code + ' ' + a.den + ' ' + (a.definition ?? '')).toLowerCase().includes(q)) : tri;
  });

  constructor() {
    this.api.rdm().subscribe({ next: r => this.resume.set(r), error: e => this.erreur.set(erreurs(e).join(' ')) });
    this.api.abies().subscribe({
      next: a => {
        this.abies.set(a);
        this.chargement.set(false);
        const racine = a.find(x => x.root);
        if (racine) this.ouvrir(racine.code);
      },
      error: e => { this.chargement.set(false); this.erreur.set(erreurs(e).join(' ')); },
    });
  }

  protected saisir(v: string): void {
    this.q.set(v);
    this.resultats.set(null);
  }

  protected chercher(): void {
    if (!this.q().trim()) { this.resultats.set(null); return; }
    this.api.rechercherBie(this.q(), 100).subscribe({ next: r => this.resultats.set(r), error: e => this.erreur.set(erreurs(e).join(' ')) });
  }

  protected ouvrir(code: string): void {
    this.api.abie(code).subscribe({ next: a => this.abie.set(a), error: e => this.erreur.set(erreurs(e).join(' ')) });
  }
}
