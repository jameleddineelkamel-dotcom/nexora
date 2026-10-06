import { Component, computed, inject, input, output, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ApiService, erreurs } from '../../core/api.service';
import { Attachment, Category, MetadataField, PHASES, Phase, SOURCES, TableDef, TableSummary } from '../../core/models';
import { I18N, I18n } from '../../core/i18n';
import { AuthService, DROITS } from '../../core/auth.service';
import { Icon } from '../../shared/icon';
import { Ring } from '../../shared/widgets';

const COLONNES_TABLE = ['description', 'standards', 'producer', 'updateAuthority', 'obtentionMode', 'updateMode'];
const GROUPES: { code: MetadataField['group']; titre: string; icone: string }[] = [
  { code: 'IDENTIFICATION', titre: 'Identification', icone: 'info' },
  { code: 'CYCLE_DE_VIE', titre: 'Cycle de vie du référentiel et qualité des données', icone: 'refresh' },
  { code: 'ADMINISTRATIF', titre: 'Métadonnées administratives, organismes associés', icone: 'landmark' },
];

/** Identité de la table, fiche de métadonnées ISO 19115 (extensible) et pièces jointes. */
@Component({
  selector: 'nx-metadata-tab',
  imports: [DatePipe, Icon, Ring, ...I18N],
  template: `
    <fieldset class="lecture stack" [disabled]="!modifiable()">
      <div class="card pad">
        <div class="between card-title">
          <h2><nx-icon name="tag" /> {{ 'Identité et classement' | t }}</h2>
          <div class="row"><nx-ring [value]="completude()" [size]="48" /><span class="small muted">{{ 'complétude' | t }}<br />ISO 19115</span></div>
        </div>
        <div class="form-grid">
          <div class="field"><label>{{ 'Nom (FR)' | t }} *</label><input class="input" [value]="v('nameFr')" (input)="poser('nameFr', $any($event.target).value)" /></div>
          <div class="field"><label>{{ 'Nom (EN)' | t }}</label><input class="input" [value]="v('nameEn')" (input)="poser('nameEn', $any($event.target).value)" /></div>
          <div class="field"><label>{{ 'N° de fiche' | t }}</label><input class="input mono" [value]="v('number')" (input)="poser('number', $any($event.target).value)" /></div>
          <div class="field"><label>{{ 'Catégorie' | t }} *</label>
            <select class="input" (change)="poser('categoryCode', $any($event.target).value)">
              @for (c of categories(); track c.code) { <option [value]="c.code" [selected]="c.code === v('categoryCode')">{{ c.parentCode ? '— ' : '' }}{{ c.labelFr | lib: c.labelEn }}</option> }
            </select></div>
          <div class="field"><label>{{ 'Source' | t }}</label>
            <select class="input" (change)="poser('source', $any($event.target).value)">
              @for (s of sources; track s.code) { <option [value]="s.code" [selected]="s.code === v('source')">{{ s.label | t }}</option> }
            </select></div>
          <div class="field"><label>{{ 'Table parente (hiérarchie)' | t }}</label>
            <select class="input" (change)="poser('parentTableCode', $any($event.target).value)">
              <option value="">{{ 'Aucune' | t }}</option>
              <option [value]="table().code" [selected]="v('parentTableCode') === table().code">{{ 'Elle-même (hiérarchie interne)' | t }}</option>
              @for (t of tables(); track t.code) { @if (t.code !== table().code) { <option [value]="t.code" [selected]="t.code === v('parentTableCode')">{{ t.code }}</option> } }
            </select></div>
          <div class="field full"><label>{{ 'Modèle de référence de la chaîne logistique internationale (UN/CEFACT)' | t }}</label>
            <div class="phases">
              @for (p of phases; track p.code) {
                <label class="phase" [class.on]="bsp().includes(p.code)" [attr.data-p]="p.code">
                  <input type="checkbox" [checked]="bsp().includes(p.code)" (change)="basculer(p.code)" />
                  <b>{{ p.label }}</b><span>{{ p.sub | t }} — {{ processus(p.processes) }}</span>
                </label>
              }
            </div></div>
        </div>
      </div>

      @for (g of groupes; track g.code) {
        <div class="card pad">
          <div class="card-title"><h2><nx-icon [name]="g.icone" /> {{ g.titre | t }}</h2></div>
          <div class="form-grid">
            @for (f of champs(g.code); track f.key) {
              <div class="field" [class.full]="f.longText">
                <label>{{ f.labelFr | t }} @if (!v(f.key)) { <span class="manque">{{ 'à renseigner' | t }}</span> }</label>
                @if (f.longText) { <textarea class="input" rows="3" [value]="v(f.key)" (input)="poser(f.key, $any($event.target).value)"></textarea> }
                @else { <input class="input" [value]="v(f.key)" (input)="poser(f.key, $any($event.target).value)" /> }
              </div>
            }
          </div>
        </div>
      }

    </fieldset>
    <div class="stack">
      <div class="card pad">
        <div class="card-title"><h2><nx-icon name="file" /> {{ 'Pièces jointes' | t }}</h2></div>
        <p class="muted small">{{ 'Documents de référence de la table : norme, recommandation UN/CEFACT, note de mise à jour, accord de partage… (20 Mo au plus par fichier).' | t }}</p>
        <div class="pj-liste">
          @for (p of pieces(); track p.id) {
            <div class="pj">
              <span class="pj-ic"><nx-icon [name]="icone(p)" /></span>
              <div class="grow">
                <a [href]="lien(p)" [attr.download]="p.fileName"><b>{{ p.fileName }}</b></a>
                <div class="small muted">{{ taille(p.sizeBytes) }} · {{ p.createdBy }} · {{ p.createdAt | date: 'dd/MM/yyyy HH:mm' }}@if (p.description) { · {{ p.description }} }</div>
              </div>
              <a class="btn ghost icon sm" [href]="lien(p)" [attr.download]="p.fileName" [attr.aria-label]="'Télécharger' | t"><nx-icon name="download" /></a>
              @if (modifiable()) { <button class="btn ghost icon sm danger" (click)="retirer(p)" [attr.aria-label]="'Retirer' | t"><nx-icon name="x" /></button> }
            </div>
          } @empty { <p class="muted small">{{ 'Aucune pièce jointe.' | t }}</p> }
        </div>
        @if (modifiable()) { <div class="pj-ajout">
          <label class="depot" [class.survol]="survol()" (dragover)="$event.preventDefault(); survol.set(true)" (dragleave)="survol.set(false)"
            (drop)="$event.preventDefault(); survol.set(false); choisir($any($event).dataTransfer.files[0])">
            <input type="file" hidden (change)="choisir($any($event.target).files[0])" />
            <nx-icon name="upload" />
            @if (fichier(); as f) { <b>{{ f.name }}</b> } @else { <span>{{ 'Déposez un fichier ou cliquez pour parcourir' | t }}</span> }
          </label>
          <input class="input" [value]="descriptionPj()" (input)="descriptionPj.set($any($event.target).value)" [placeholder]="'Description (facultatif)' | t" />
          <button class="btn" (click)="joindre()" [disabled]="!fichier() || envoi()"><nx-icon name="plus" /> {{ 'Joindre' | t }}</button>
        </div> }
        @if (erreurPj()) { <div class="alert err">{{ erreurPj() }}</div> }
      </div>

    </div>
    <fieldset class="lecture stack" [disabled]="!modifiable()">
      <div class="card pad">
        <div class="card-title"><h2><nx-icon name="sparkles" /> {{ 'Métadonnées complémentaires' | t }}</h2></div>
        <p class="muted small">{{ 'Ajoutez librement d\\'autres métadonnées (ex. « Accord de partage », « URL de la source », « Version UN/CEFACT »).' | t }}</p>
        @for (k of libres(); track k) {
          <div class="libre"><input class="input mono" [value]="k" readonly /><input class="input" [value]="v(k)" (input)="poser(k, $any($event.target).value)" />
            <button class="btn ghost icon" (click)="retirerCle(k)" [attr.aria-label]="'Retirer' | t"><nx-icon name="x" /></button></div>
        }
        @if (modifiable()) { <form class="libre" (submit)="$event.preventDefault(); ajouter(cle.value); cle.value = ''">
          <input #cle class="input mono" [placeholder]="'nouvelleMetadonnee' | t" [attr.aria-label]="'Clé de la nouvelle métadonnée' | t" />
          <button class="btn" type="submit"><nx-icon name="plus" /> {{ 'Créer la métadonnée' | t }}</button>
        </form> }
      </div>

    </fieldset>
    @if (modifiable() || auth.a(droits.structure)) {
      <div class="card pad stack">
        @if (modifiable()) {
        <div class="field"><label>{{ 'Motif de la modification' | t }}</label><input class="input" [value]="motif()" (input)="motif.set($any($event.target).value)" /></div>
        }
        @if (messages().length) { <div class="alert err"><ul>@for (m of messages(); track m) { <li>{{ m }}</li> }</ul></div> }
        @if (ok()) { <div class="alert ok"><nx-icon name="check" /> {{ 'Fiche enregistrée. Les changements sont dans l\\'historique de la table.' | t }}</div> }
        <div class="row">
          @if (modifiable()) { <button class="btn primary" (click)="enregistrer()" [disabled]="occupe()"><nx-icon name="check" /> {{ 'Enregistrer la fiche' | t }}</button> }
          @if (!auth.a(droits.structure)) { } @else if (table().status === 'ARCHIVED') {
            <button class="btn" (click)="statut('ACTIVE')"><nx-icon name="refresh" /> {{ 'Réactiver la table' | t }}</button>
          } @else {
            <button class="btn danger" (click)="statut('ARCHIVED')"><nx-icon name="archive" /> {{ 'Archiver la table' | t }}</button>
          }
        </div>
      </div>
    }
  `,
  styles: [`
    p { margin: 0 0 10px; }
    .manque { font-weight: 500; color: var(--warn); margin-left: 6px; }
    .phases { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(220px, 100%), 1fr)); gap: 8px; }
    .phase { display: flex; flex-direction: column; gap: 2px; padding: 10px 12px 10px 34px; border: 1px solid var(--line); border-radius: 12px; cursor: pointer; position: relative; }
    .phase input { position: absolute; left: 12px; top: 13px; }
    .phase span { font-size: 12px; color: var(--muted); }
    .phase.on[data-p=BUY] { border-color: var(--buy); background: color-mix(in srgb, var(--buy) 8%, transparent); }
    .phase.on[data-p=SHIP] { border-color: var(--ship); background: color-mix(in srgb, var(--ship) 8%, transparent); }
    .phase.on[data-p=PAY] { border-color: var(--pay); background: color-mix(in srgb, var(--pay) 8%, transparent); }
    .libre { display: grid; grid-template-columns: 240px 1fr auto; gap: 8px; margin-bottom: 8px; }
    form.libre { grid-template-columns: 240px auto; }
    .pj-liste { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
    .pj { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border: 1px solid var(--line); border-radius: 10px; }
    .pj-ic { width: 34px; height: 34px; border-radius: 9px; display: grid; place-items: center; background: var(--accent-soft); color: var(--accent); }
    .pj-ajout { display: grid; grid-template-columns: 1.2fr 1fr auto; gap: 8px; align-items: center; }
    .depot { display: flex; align-items: center; gap: 8px; height: 38px; padding: 0 12px; border: 1px dashed var(--line); border-radius: var(--radius-sm);
      cursor: pointer; color: var(--muted); overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
    .depot:hover, .depot.survol { border-color: var(--accent); color: var(--accent); background: var(--accent-soft); }
    @media (max-width: 760px) { .pj-ajout, .libre, form.libre { grid-template-columns: 1fr; } }
  `],
})
export class MetadataTab {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthService);
  protected readonly droits = DROITS;
  /** Fiche modifiable avec le droit « métadonnées » ; sinon lecture seule. */
  protected readonly modifiable = computed(() => this.auth.a(DROITS.metadonnees));
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

  // Pièces jointes
  protected readonly pieces = signal<Attachment[]>([]);
  protected readonly fichier = signal<File | null>(null);
  protected readonly descriptionPj = signal('');
  protected readonly survol = signal(false);
  protected readonly envoi = signal(false);
  protected readonly erreurPj = signal<string | null>(null);

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
    this.chargerPieces();
  }

  protected champs(g: string): MetadataField[] { return this.schema().filter(f => f.group === g); }
  protected v(k: string): string { return this.valeurs()[k] ?? ''; }
  protected poser(k: string, val: string): void { this.valeurs.set({ ...this.valeurs(), [k]: val }); this.ok.set(false); }
  protected basculer(p: Phase): void { this.bsp.set(this.bsp().includes(p) ? this.bsp().filter(x => x !== p) : [...this.bsp(), p]); }
  protected processus(p: string[]): string { return p.map(x => this.i18n.t(x)).join(', '); }
  protected ajouter(k: string): void {
    const cle = k.trim().replace(/[^A-Za-z0-9_]/g, '');
    if (cle && !(cle in this.valeurs())) this.poser(cle, '');
  }
  protected retirerCle(k: string): void {
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

  // ---------- Pièces jointes ----------
  private chargerPieces(): void { this.api.piecesJointes(this.table().code).subscribe(p => this.pieces.set(p)); }
  protected choisir(f: File | undefined): void { if (f) { this.fichier.set(f); this.erreurPj.set(null); } }
  protected lien(p: Attachment): string { return this.auth.lien(this.api.urlPieceJointe(this.table().code, p.id)); }

  protected joindre(): void {
    const f = this.fichier();
    if (!f) return;
    this.envoi.set(true);
    this.api.joindre(this.table().code, f, this.descriptionPj()).subscribe({
      next: () => { this.fichier.set(null); this.descriptionPj.set(''); this.envoi.set(false); this.chargerPieces(); },
      error: e => { this.erreurPj.set(erreurs(e).join(' ')); this.envoi.set(false); },
    });
  }

  protected retirer(p: Attachment): void {
    this.api.retirerPieceJointe(this.table().code, p.id).subscribe({
      next: () => this.chargerPieces(), error: e => this.erreurPj.set(erreurs(e).join(' ')),
    });
  }

  protected taille(o: number): string {
    const [b, k, m] = this.i18n.langue() === 'fr' ? ['o', 'Ko', 'Mo'] : ['B', 'KB', 'MB'];
    return o < 1024 ? `${o} ${b}` : o < 1048576 ? `${(o / 1024).toFixed(0)} ${k}` : `${(o / 1048576).toFixed(1)} ${m}`;
  }

  protected icone(p: Attachment): string {
    return /pdf|word|text|document/.test(p.contentType ?? '') ? 'file' : /sheet|excel|csv/.test(p.contentType ?? '') ? 'table' : 'file';
  }
}
