import { Component, effect, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ApiService, erreurs } from '../core/api.service';
import { HistoryEvent, OPERATIONS } from '../core/models';
import { Icon } from './icon';

const TYPES: Record<string, string> = { CATEGORY: 'Catégorie', TABLE: 'Table', COLUMN: 'Colonne', ENTRY: 'Code' };
const CHAMPS: Record<string, string> = {
  label_fr: 'Libellé FR', label_en: 'Libellé EN', parent_code: 'Parent', valid_from: 'Début de validité', valid_to: 'Fin de validité',
  status: 'Statut', name_fr: 'Nom', name_en: 'Nom EN', category_code: 'Catégorie', bsp_phases: 'Phases BSP', data_type: 'Type',
  max_length: 'Longueur', required: 'Obligatoire', role: 'Rôle', ref_table_code: 'Table référencée', sort_order: 'Ordre',
};

/** Frise de l'historique : qui, quand, par quel canal, pourquoi, et le détail avant / après. */
@Component({
  selector: 'nx-history-list',
  imports: [DatePipe, RouterLink, Icon],
  template: `
    @if (filtres()) {
      <div class="card pad filtres">
        <input class="input" placeholder="Table (REF_…)" [value]="fTable()" (change)="fTable.set($any($event.target).value.toUpperCase()); recharger()" aria-label="Table" />
        <input class="input" placeholder="Code" [value]="fCode()" (change)="fCode.set($any($event.target).value); recharger()" aria-label="Code" />
        <select class="input" (change)="fType.set($any($event.target).value); recharger()" aria-label="Objet">
          <option value="">Tous objets</option><option value="ENTRY">Codes</option><option value="TABLE">Tables</option>
          <option value="COLUMN">Colonnes</option><option value="CATEGORY">Catégories</option>
        </select>
        <select class="input" (change)="fOp.set($any($event.target).value); recharger()" aria-label="Opération">
          <option value="">Toutes opérations</option>
          @for (o of operations; track o[0]) { <option [value]="o[0]" [selected]="o[0] === fOp()">{{ o[1] }}</option> }
        </select>
        <select class="input" (change)="fCanal.set($any($event.target).value); recharger()" aria-label="Canal">
          <option value="">Tous canaux</option><option value="UI">Interface</option><option value="API">API</option>
          <option value="IMPORT">Import</option><option value="SEED">Chargement initial</option><option value="SQL">SQL direct</option>
        </select>
        <input class="input" placeholder="Auteur" [value]="fAuteur()" (change)="fAuteur.set($any($event.target).value); recharger()" aria-label="Auteur" />
        <input class="input" type="date" [value]="fDu()" (change)="fDu.set($any($event.target).value); recharger()" aria-label="Depuis le" />
      </div>
    }
    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
    <div class="frise">
      @for (h of evenements(); track h.id) {
        <article class="evt" [attr.data-op]="h.operation">
          <span class="point"></span>
          <div class="card pad">
            <div class="between">
              <div class="row">
                <span class="chip op">{{ ops[h.operation] }}</span>
                <span class="chip">{{ types[h.entityType] }}</span>
                @if (h.entityCode) {
                  @if (h.tableCode && h.entityType === 'ENTRY') { <a class="code-tag" [routerLink]="['/tables', h.tableCode]" [queryParams]="{ q: h.entityCode }">{{ h.entityCode }}</a> }
                  @else { <span class="code-tag">{{ h.entityCode }}</span> }
                }
                @if (h.tableCode && !table()) { <a [routerLink]="['/tables', h.tableCode]" class="small">{{ h.tableCode }}</a> }
              </div>
              <span class="muted small">{{ h.occurredAt | date: 'dd/MM/yyyy HH:mm:ss' }}</span>
            </div>
            <div class="meta small">
              <span><nx-icon name="user" [size]="13" /> {{ h.author }}</span>
              <span><nx-icon name="link" [size]="13" /> {{ canaux[h.channel] ?? h.channel }}</span>
              @if (h.correlationId) { <span><nx-icon name="upload" [size]="13" /> lot {{ h.correlationId }}</span> }
              @if (h.reason) { <span class="motif">« {{ h.reason }} »</span> }
            </div>
            @if (h.changes) {
              <div class="diff">
                @for (c of changements(h); track c.k) {
                  <span class="k">{{ c.k }}</span>
                  <span>@if (c.avant !== '∅') { <del>{{ c.avant }}</del> → } <ins>{{ c.apres }}</ins></span>
                }
              </div>
            } @else if (h.operation === 'CREATION' && h.entityType === 'ENTRY') {
              <div class="small muted resume">{{ h.snapshot['label_fr'] }}</div>
            }
          </div>
        </article>
      } @empty {
        @if (!chargement()) { <div class="card empty">Aucun événement dans l'historique pour ces critères.</div> }
      }
    </div>
    @if (total() > evenements().length) {
      <button class="btn" (click)="suite()" [disabled]="chargement()"><nx-icon name="refresh" /> Voir plus ({{ total() - evenements().length }} restants)</button>
    }
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 12px; }
    .filtres { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 8px; padding: 12px; }
    .frise { position: relative; display: flex; flex-direction: column; gap: 10px; padding-left: 22px; }
    .frise::before { content: ''; position: absolute; left: 7px; top: 6px; bottom: 6px; width: 2px; background: var(--line); }
    .evt { position: relative; }
    .point { position: absolute; left: -21px; top: 18px; width: 12px; height: 12px; border-radius: 50%; background: var(--accent); border: 3px solid var(--bg); }
    .evt[data-op=CREATION] .point, .evt[data-op=CREATION] .op { background: var(--ok); color: #fff; }
    .evt[data-op=MODIFICATION] .op { background: var(--accent-soft); color: var(--accent); }
    .evt[data-op=INVALIDATION] .point, .evt[data-op=INVALIDATION] .op { background: var(--warn); color: #fff; }
    .evt[data-op=REACTIVATION] .point, .evt[data-op=REACTIVATION] .op { background: var(--cyan); color: #fff; }
    .evt[data-op=SUPPRESSION] .point, .evt[data-op=SUPPRESSION] .op { background: var(--danger); color: #fff; }
    .meta { display: flex; gap: 14px; flex-wrap: wrap; color: var(--muted); margin-top: 8px; }
    .meta span { display: inline-flex; align-items: center; gap: 4px; }
    .motif { color: var(--text); font-style: italic; }
    .resume { margin-top: 6px; }
  `],
})
export class HistoryList {
  private readonly api = inject(ApiService);
  readonly table = input<string | null>(null);
  readonly code = input<string | null>(null);
  readonly filtres = input(false);
  readonly taille = input(30);

  protected readonly evenements = signal<HistoryEvent[]>([]);
  protected readonly total = signal(0);
  protected readonly chargement = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly fTable = signal('');
  protected readonly fCode = signal('');
  protected readonly fType = signal('');
  protected readonly fOp = signal('');
  protected readonly fCanal = signal('');
  protected readonly fAuteur = signal('');
  protected readonly fDu = signal('');
  protected readonly ops = OPERATIONS;
  protected readonly operations = Object.entries(OPERATIONS);
  protected readonly types = TYPES;
  protected readonly canaux: Record<string, string> = { UI: 'Interface', API: 'API', IMPORT: 'Import', SEED: 'Chargement initial', SQL: 'SQL direct', SYNC: 'Synchronisation' };
  private page = 0;

  constructor() {
    effect(() => { this.table(); this.code(); this.recharger(); });
  }

  recharger(): void {
    this.page = 0;
    this.evenements.set([]);
    this.charger();
  }

  protected suite(): void {
    this.page++;
    this.charger();
  }

  private charger(): void {
    this.chargement.set(true);
    this.api.historique({
      table: this.table() ?? this.fTable(), code: this.code() ?? this.fCode(), type: this.code() ? 'ENTRY' : this.fType(),
      operation: this.fOp(), channel: this.fCanal(), author: this.fAuteur(), from: this.fDu(), page: this.page, size: this.taille(),
    }).subscribe({
      next: p => { this.evenements.set([...this.evenements(), ...p.items]); this.total.set(p.total); this.chargement.set(false); },
      error: e => { this.erreur.set(erreurs(e).join(' ')); this.chargement.set(false); },
    });
  }

  protected changements(h: HistoryEvent): { k: string; avant: string; apres: string }[] {
    return Object.entries(h.changes ?? {}).map(([k, [a, b]]) => ({
      k: k.startsWith('attributes.') ? k.slice(11) : k.startsWith('metadata.') ? 'Fiche · ' + k.slice(9) : CHAMPS[k] ?? k,
      avant: HistoryList.texte(a), apres: HistoryList.texte(b),
    }));
  }

  private static texte(v: unknown): string {
    if (v === null || v === undefined || v === '') return '∅';
    return typeof v === 'object' ? JSON.stringify(v) : String(v);
  }
}
