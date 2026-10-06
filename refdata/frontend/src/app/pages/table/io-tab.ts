import { Component, inject, input, output, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ApiService, erreurs } from '../../core/api.service';
import { ImportResult, ImportRun, TableDef } from '../../core/models';
import { Icon } from '../../shared/icon';
import { I18N } from '../../core/i18n';
import { AuthService, DROITS } from '../../core/auth.service';

/** Chargement des listes de codes (simulation puis application) et exports. */
@Component({
  selector: 'nx-io-tab',
  imports: [DatePipe, Icon, ...I18N],
  template: `
    <div class="grid k2">
      @if (auth.a(droits.donnees)) {
      <div class="card pad stack">
        <h2><nx-icon name="upload" /> {{ 'Charger une liste de codes' | t }}</h2>
        <label class="depot" [class.survol]="survol()" (dragover)="$event.preventDefault(); survol.set(true)" (dragleave)="survol.set(false)"
          (drop)="$event.preventDefault(); survol.set(false); choisir($any($event).dataTransfer.files[0])">
          <input type="file" accept=".csv,.txt,.json,.xlsx" hidden (change)="choisir($any($event.target).files[0])" />
          <nx-icon name="file" [size]="28" />
          @if (fichier(); as f) { <b>{{ f.name }}</b><span class="muted small">{{ (f.size / 1024).toFixed(0) }} Ko — {{ 'cliquez pour changer' | t }}</span> }
          @else { <b>{{ 'Déposez un fichier CSV, JSON ou Excel' | t }}</b><span class="muted small">{{ 'ou cliquez pour parcourir' | t }}</span> }
        </label>
        <div class="modes">
          <label class="mode" [class.on]="mode() === 'MERGE'"><input type="radio" name="mode" [checked]="mode() === 'MERGE'" (change)="mode.set('MERGE')" />
            <b>{{ 'Fusion' | t }}</b><span>{{ 'Crée les nouveaux codes et met à jour les existants ; les autres restent inchangés.' | t }}</span></label>
          <label class="mode" [class.on]="mode() === 'REPLACE'"><input type="radio" name="mode" [checked]="mode() === 'REPLACE'" (change)="mode.set('REPLACE')" />
            <b>{{ 'Remplacement' | t }}</b><span>{{ 'Le fichier fait foi : les codes actifs absents sont invalidés (jamais supprimés).' | t }}</span></label>
        </div>
        <div class="field"><label>{{ 'Motif (historique)' | t }}</label><input class="input" [value]="motif()" (input)="motif.set($any($event.target).value)"
          [placeholder]="'Ex. publication UN/CEFACT Rec. 21 Rév. 13' | t" /></div>
        <p class="muted small">{{ 'Les colonnes sont reconnues par clé, libellé, balise XML ou « Libellé [clé] » ; une colonne absente du fichier laisse la valeur existante intacte. Codes, références et types sont contrôlés ligne par ligne.' | t }}</p>
        <div class="row">
          <button class="btn" (click)="lancer(true)" [disabled]="!fichier() || occupe()"><nx-icon name="sparkles" /> {{ 'Simuler' | t }}</button>
          <button class="btn primary" (click)="lancer(false)" [disabled]="!simulation() || occupe() || table().status === 'ARCHIVED'"><nx-icon name="check" /> {{ 'Appliquer le chargement' | t }}</button>
        </div>
        @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
        @if (resultat(); as r) {
          <div class="alert" [class.ok]="!r.dryRun" [class.info]="r.dryRun">
            <div class="stack" style="gap: 8px; width: 100%">
              <b>{{ r.dryRun ? ('Simulation — rien n\\'a été enregistré' | t) : ('Chargement appliqué · lot {0}' | t: r.importId) }}</b>
              <div class="compteurs">
                <span><b>{{ r.total }}</b> {{ 'lignes' | t }}</span><span class="c-ok"><b>{{ r.created }}</b> {{ 'créés' | t }}</span><span><b>{{ r.updated }}</b> {{ 'modifiés' | t }}</span>
                <span><b>{{ r.unchanged }}</b> {{ 'inchangés' | t }}</span><span class="c-warn"><b>{{ r.invalidated }}</b> {{ 'invalidés' | t }}</span><span class="c-err"><b>{{ r.rejected }}</b> {{ 'rejetés' | t }}</span>
              </div>
              <details><summary class="small">{{ 'Correspondance des colonnes' | t }}</summary>
                <div class="diff">@for (m of correspondance(r); track m[0]) { <span class="k">{{ m[0] }}</span><span>→ {{ m[1] }}</span> }</div></details>
            </div>
          </div>
          @if (r.warnings.length) { <div class="alert warn"><ul>@for (w of r.warnings; track w) { <li>{{ w }}</li> }</ul></div> }
          @if (r.errors.length) { <div class="alert err erreurs"><ul>@for (w of r.errors; track w) { <li>{{ w }}</li> }</ul></div> }
        }
      </div>

      }
      <div class="stack">
        @if (auth.a(droits.exporter)) {
        <div class="card pad stack">
          <h2><nx-icon name="download" /> {{ 'Exporter' | t }}</h2>
          <p class="muted small">{{ 'Fichiers ré-importables. L\\'export Excel contient aussi la structure et la fiche ISO 19115.' | t }}</p>
          <div class="row">
            <select class="input statut" [value]="statut()" (change)="statut.set($any($event.target).value)" [attr.aria-label]="'Codes exportés' | t">
              <option value="ALL">{{ 'Tous les codes' | t }}</option><option value="ACTIVE">{{ 'Codes actifs' | t }}</option><option value="INVALID">{{ 'Codes invalidés' | t }}</option>
            </select>
          </div>
          <div class="row">
            <a class="btn" [href]="auth.lien(api.urlExport(table().code, 'xlsx', statut()))"><nx-icon name="table" /> Excel</a>
            <a class="btn" [href]="auth.lien(api.urlExport(table().code, 'csv', statut()))"><nx-icon name="file" /> CSV</a>
            <a class="btn" [href]="auth.lien(api.urlExport(table().code, 'json', statut()))"><nx-icon name="code" /> JSON</a>
          </div>
        </div>
        }
        <div class="card pad">
          <h2 class="card-title"><nx-icon name="history" /> {{ 'Journal des chargements' | t }}</h2>
          @for (c of journal(); track c.id) {
            <div class="lot">
              <div class="between"><span class="code-tag">{{ c.id }}</span><span class="muted small">{{ c.occurredAt | date: 'dd/MM/yyyy HH:mm' }}</span></div>
              <div class="small">{{ c.fileName }} · {{ (c.mode === 'SEED' ? 'chargement initial' : c.mode === 'REPLACE' ? 'remplacement' : 'fusion') | t }} · {{ c.author }}</div>
              <div class="small muted">+{{ c.created }} · ~{{ c.updated }} · ={{ c.unchanged }} · ⊘{{ c.invalidated }} · ✕{{ c.rejected }}</div>
            </div>
          } @empty { <p class="muted small">{{ 'Aucun chargement pour cette table.' | t }}</p> }
        </div>
      </div>
    </div>
  `,
  styles: [`
    p { margin: 0; }
    .depot { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 28px; border: 2px dashed var(--line); border-radius: var(--radius);
      cursor: pointer; text-align: center; color: var(--muted); transition: border-color .15s, background .15s; }
    .depot:hover, .depot.survol { border-color: var(--accent); background: var(--accent-soft); color: var(--accent); }
    .depot b { color: var(--text); }
    .modes { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
    .mode { display: flex; flex-direction: column; gap: 3px; padding: 10px 12px 10px 34px; border: 1px solid var(--line); border-radius: 12px; cursor: pointer; position: relative; }
    .mode input { position: absolute; left: 12px; top: 13px; }
    .mode span { font-size: 12px; color: var(--muted); }
    .mode.on { border-color: var(--accent); background: var(--accent-soft); }
    .compteurs { display: flex; gap: 14px; flex-wrap: wrap; color: var(--text); }
    .c-ok b { color: var(--ok); } .c-warn b { color: var(--warn); } .c-err b { color: var(--danger); }
    .erreurs { max-height: 260px; overflow-y: auto; }
    .statut { width: auto; }
    .lot { padding: 8px 0; border-bottom: 1px solid var(--line); display: flex; flex-direction: column; gap: 2px; }
    @media (max-width: 700px) { .modes { grid-template-columns: 1fr; } }
  `],
})
export class IoTab {
  protected readonly api = inject(ApiService);
  protected readonly auth = inject(AuthService);
  protected readonly droits = DROITS;
  readonly table = input.required<TableDef>();
  readonly importe = output<void>();
  protected readonly fichier = signal<File | null>(null);
  protected readonly mode = signal<'MERGE' | 'REPLACE'>('MERGE');
  protected readonly motif = signal('');
  protected readonly survol = signal(false);
  protected readonly occupe = signal(false);
  protected readonly simulation = signal(false);
  protected readonly resultat = signal<ImportResult | null>(null);
  protected readonly erreur = signal<string | null>(null);
  protected readonly statut = signal('ALL');
  protected readonly journal = signal<ImportRun[]>([]);

  ngOnInit(): void { this.chargerJournal(); }

  private chargerJournal(): void { this.api.chargements(this.table().code).subscribe(j => this.journal.set(j)); }

  protected choisir(f: File | undefined): void {
    if (!f) return;
    this.fichier.set(f);
    this.simulation.set(false);
    this.resultat.set(null);
    this.erreur.set(null);
  }

  protected lancer(simuler: boolean): void {
    const f = this.fichier();
    if (!f) return;
    this.occupe.set(true);
    this.erreur.set(null);
    this.api.importer(this.table().code, f, this.mode(), simuler, this.motif()).subscribe({
      next: r => {
        this.resultat.set(r);
        this.occupe.set(false);
        this.simulation.set(simuler);
        if (!simuler) { this.chargerJournal(); this.importe.emit(); }
      },
      error: e => { this.erreur.set(erreurs(e).join(' ')); this.occupe.set(false); },
    });
  }

  protected correspondance(r: ImportResult): [string, string][] {
    return Object.entries(r.mapping).map(([k, v]) => [k, v.startsWith('attr:') ? v.slice(5) : v]);
  }
}
