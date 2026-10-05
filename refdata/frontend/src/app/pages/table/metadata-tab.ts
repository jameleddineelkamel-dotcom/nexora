import { Component, computed, inject, input, output, signal } from '@angular/core';
import { ApiService, erreurs } from '../../core/api.service';
import { Category, MetadataField, PHASES, Phase, SOURCES, TableDef, TableSummary } from '../../core/models';
import { Icon } from '../../shared/icon';
import { Ring } from '../../shared/widgets';

const COLONNES_TABLE = ['description', 'standards', 'producer', 'updateAuthority', 'obtentionMode', 'updateMode'];
const GROUPES: { code: MetadataField['group']; titre: string; icone: string }[] = [
  { code: 'IDENTIFICATION', titre: 'Identification', icone: 'info' },
  { code: 'CYCLE_DE_VIE', titre: 'Cycle de vie du référentiel et qualité des données', icone: 'refresh' },
  { code: 'ADMINISTRATIF', titre: 'Métadonnées administratives, organismes associés', icone: 'landmark' },
];

/** Identité de la table et fiche de métadonnées ISO 19115 (modèle du rapport « Référentiel Commun »), extensible. */
@Component({
  selector: 'nx-metadata-tab',
  imports: [Icon, Ring],
  template: `
    <div class="stack">
      <div class="card pad">
        <div class="between card-title">
          <h2><nx-icon name="tag" /> Identité et classement</h2>
          <div class="row"><nx-ring [value]="completude()" [size]="48" /><span class="small muted">complétude<br />ISO 19115</span></div>
        </div>
        <div class="form-grid">
          <div class="field"><label>Nom (FR) *</label><input class="input" [value]="v('nameFr')" (input)="poser('nameFr', $any($event.target).value)" /></div>
          <div class="field"><label>Nom (EN)</label><input class="input" [value]="v('nameEn')" (input)="poser('nameEn', $any($event.target).value)" /></div>
          <div class="field"><label>N° de fiche</label><input class="input mono" [value]="v('number')" (input)="poser('number', $any($event.target).value)" /></div>
          <div class="field"><label>Catégorie *</label>
            <select class="input" [value]="v('categoryCode')" (change)="poser('categoryCode', $any($event.target).value)">
              @for (c of categories(); track c.code) { <option [value]="c.code" [selected]="c.code === v('categoryCode')">{{ c.parentCode ? '— ' : '' }}{{ c.labelFr }}</option> }
            </select></div>
          <div class="field"><label>Source</label>
            <select class="input" [value]="v('source')" (change)="poser('source', $any($event.target).value)">
              @for (s of sources; track s.code) { <option [value]="s.code" [selected]="s.code === v('source')">{{ s.label }}</option> }
            </select></div>
          <div class="field"><label>Table parente (hiérarchie)</label>
            <select class="input" [value]="v('parentTableCode')" (change)="poser('parentTableCode', $any($event.target).value)">
              <option value="">Aucune</option><option [value]="table().code" [selected]="v('parentTableCode') === table().code">Elle-même (hiérarchie interne)</option>
              @for (t of tables(); track t.code) { @if (t.code !== table().code) { <option [value]="t.code" [selected]="t.code === v('parentTableCode')">{{ t.code }}</option> } }
            </select></div>
          <div class="field full"><label>Modèle de référence de la chaîne logistique internationale (UN/CEFACT)</label>
            <div class="phases">
              @for (p of phases; track p.code) {
                <label class="phase" [class.on]="bsp().includes(p.code)" [attr.data-p]="p.code">
                  <input type="checkbox" [checked]="bsp().includes(p.code)" (change)="basculer(p.code)" />
                  <b>{{ p.label }}</b><span>{{ p.sub }} — {{ p.processes.join(', ') }}</span>
                </label>
              }
            </div></div>
        </div>
      </div>

      @for (g of groupes; track g.code) {
        <div class="card pad">
          <div class="card-title"><h2><nx-icon [name]="g.icone" /> {{ g.titre }}</h2></div>
          <div class="form-grid">
            @for (f of champs(g.code); track f.key) {
              <div class="field" [class.full]="f.longText">
                <label>{{ f.labelFr }} @if (!v(f.key)) { <span class="manque">à renseigner</span> }</label>
                @if (f.longText) { <textarea class="input" rows="3" [value]="v(f.key)" (input)="poser(f.key, $any($event.target).value)"></textarea> }
                @else { <input class="input" [value]="v(f.key)" (input)="poser(f.key, $any($event.target).value)" /> }
              </div>
            }
          </div>
        </div>
      }

      <div class="card pad">
        <div class="card-title"><h2><nx-icon name="sparkles" /> Métadonnées complémentaires</h2></div>
        <p class="muted small">Ajoutez librement d'autres métadonnées (ex. « Accord de partage », « URL de la source », « Version UN/CEFACT »).</p>
        @for (k of libres(); track k) {
          <div class="libre"><input class="input mono" [value]="k" readonly /><input class="input" [value]="v(k)" (input)="poser(k, $any($event.target).value)" />
            <button class="btn ghost icon" (click)="retirer(k)" aria-label="Retirer"><nx-icon name="x" /></button></div>
        }
        <form class="libre" (submit)="$event.preventDefault(); ajouter(cle.value); cle.value = ''">
          <input #cle class="input mono" placeholder="nouvelleMetadonnee" aria-label="Clé de la nouvelle métadonnée" />
          <button class="btn" type="submit"><nx-icon name="plus" /> Créer la métadonnée</button>
        </form>
      </div>

      <div class="card pad stack">
        <div class="field"><label>Motif de la modification</label><input class="input" [value]="motif()" (input)="motif.set($any($event.target).value)" /></div>
        @if (messages().length) { <div class="alert err"><ul>@for (m of messages(); track m) { <li>{{ m }}</li> }</ul></div> }
        @if (ok()) { <div class="alert ok"><nx-icon name="check" /> Fiche enregistrée. Les changements sont dans l'historique de la table.</div> }
        <div class="row">
          <button class="btn primary" (click)="enregistrer()" [disabled]="occupe()"><nx-icon name="check" /> Enregistrer la fiche</button>
          @if (table().status === 'ARCHIVED') {
            <button class="btn" (click)="statut('ACTIVE')"><nx-icon name="refresh" /> Réactiver la table</button>
          } @else {
            <button class="btn danger" (click)="statut('ARCHIVED')"><nx-icon name="archive" /> Archiver la table</button>
          }
        </div>
      </div>
    </div>
  `,
  styles: [`
    p { margin: 0 0 10px; }
    .manque { font-weight: 500; color: var(--warn); margin-left: 6px; }
    .phases { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 8px; }
    .phase { display: flex; flex-direction: column; gap: 2px; padding: 10px 12px 10px 34px; border: 1px solid var(--line); border-radius: 12px; cursor: pointer; position: relative; }
    .phase input { position: absolute; left: 12px; top: 13px; }
    .phase span { font-size: 12px; color: var(--muted); }
    .phase.on[data-p=BUY] { border-color: var(--buy); background: color-mix(in srgb, var(--buy) 8%, transparent); }
    .phase.on[data-p=SHIP] { border-color: var(--ship); background: color-mix(in srgb, var(--ship) 8%, transparent); }
    .phase.on[data-p=PAY] { border-color: var(--pay); background: color-mix(in srgb, var(--pay) 8%, transparent); }
    .libre { display: grid; grid-template-columns: 240px 1fr auto; gap: 8px; margin-bottom: 8px; }
    form.libre { grid-template-columns: 240px auto; }
  `],
})
export class MetadataTab {
  private readonly api = inject(ApiService);
  readonly table = input.required<TableDef>();
  readonly categories = input<Category[]>([]);
  readonly enregistree = output<TableDef>();

  protected readonly valeurs = signal<Record<string, string>>({});
  protected readonly bsp = signal<Phase[]>([]);
  protected readonly schema = signal<MetadataField[]>([]);
  protected readonly tables = signal<TableSummary[]>([]);
  protected readonly motif = signal('');
  protected readonly messages = signal<string[]>([]);
  protected readonly ok = signal(false);
  protected readonly occupe = signal(false);
  protected readonly groupes = GROUPES;
  protected readonly phases = PHASES;
  protected readonly sources = SOURCES;

  protected readonly libres = computed(() => {
    const connus = new Set([...this.schema().map(f => f.key), 'nameFr', 'nameEn', 'number', 'categoryCode', 'source', 'parentTableCode', 'ficheTitle']);
    return Object.keys(this.valeurs()).filter(k => !connus.has(k));
  });
  protected readonly completude = computed(() => {
    const s = this.schema();
    return s.length ? Math.round((s.filter(f => (this.valeurs()[f.key] ?? '').trim()).length * 100) / s.length) : this.table().completeness;
  });

  constructor() {
    this.api.schema().subscribe(s => this.schema.set(s.metadata));
    this.api.tables({ status: 'ALL' }).subscribe(t => this.tables.set(t));
  }

  ngOnInit(): void {
    const t = this.table();
    const v: Record<string, string> = { ...(t.metadata ?? {}) };
    for (const k of [...COLONNES_TABLE, 'nameFr', 'nameEn', 'number', 'categoryCode', 'source', 'parentTableCode'] as const) {
      v[k] = ((t as unknown as Record<string, unknown>)[k] as string) ?? '';
    }
    this.valeurs.set(v);
    this.bsp.set([...t.bspPhases]);
  }

  protected champs(g: string): MetadataField[] { return this.schema().filter(f => f.group === g); }
  protected v(k: string): string { return this.valeurs()[k] ?? ''; }
  protected poser(k: string, val: string): void { this.valeurs.set({ ...this.valeurs(), [k]: val }); this.ok.set(false); }
  protected basculer(p: Phase): void { this.bsp.set(this.bsp().includes(p) ? this.bsp().filter(x => x !== p) : [...this.bsp(), p]); }
  protected ajouter(k: string): void {
    const cle = k.trim().replace(/[^A-Za-z0-9_]/g, '');
    if (cle && !(cle in this.valeurs())) this.poser(cle, '');
  }
  protected retirer(k: string): void {
    const v = { ...this.valeurs() };
    delete v[k];
    this.valeurs.set(v);
  }

  protected enregistrer(): void {
    const v = this.valeurs();
    const t = this.table();
    const metadata: Record<string, string> = {};
    const exclus = new Set([...COLONNES_TABLE, 'nameFr', 'nameEn', 'number', 'categoryCode', 'source', 'parentTableCode']);
    for (const [k, x] of Object.entries(v)) if (!exclus.has(k)) metadata[k] = x;
    const corps: Partial<TableDef> = {
      ...t, nameFr: v['nameFr'], nameEn: v['nameEn'] || null, number: v['number'] || null, categoryCode: v['categoryCode'],
      source: v['source'] as TableDef['source'], parentTableCode: v['parentTableCode'] || null, bspPhases: this.bsp(), metadata,
      description: v['description'] || null, standards: v['standards'] || null, producer: v['producer'] || null,
      updateAuthority: v['updateAuthority'] || null, obtentionMode: v['obtentionMode'] || null, updateMode: v['updateMode'] || null,
    };
    this.occupe.set(true);
    this.api.modifierTable(t.code, corps, false, this.motif()).subscribe({
      next: r => { this.occupe.set(false); this.messages.set([]); this.ok.set(true); this.enregistree.emit(r); },
      error: e => { this.occupe.set(false); this.messages.set(erreurs(e)); },
    });
  }

  protected statut(s: string): void {
    this.api.statutTable(this.table().code, s).subscribe({
      next: r => this.enregistree.emit(r), error: e => this.messages.set(erreurs(e)),
    });
  }
}
