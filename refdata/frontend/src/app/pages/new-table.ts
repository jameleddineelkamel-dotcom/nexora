import { Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiService, erreurs } from '../core/api.service';
import { Category, ColumnDef, PHASES, Phase, Proposition, SOURCES, Source, TableDef } from '../core/models';
import { Icon } from '../shared/icon';
import { ColumnEditor } from '../shared/column-editor';
import { I18N } from '../core/i18n';

const VIDE: ColumnDef[] = [
  { key: 'code', labelFr: 'Code', role: 'CODE', dataType: 'STRING', maxLength: 35, required: true },
  { key: 'labelFr', labelFr: 'Libellé (FR)', role: 'LABEL_FR', dataType: 'STRING', maxLength: 512, required: true },
  { key: 'labelEn', labelFr: 'Libellé (EN)', role: 'LABEL_EN', dataType: 'STRING', maxLength: 512, required: false },
];

/** Assistant de création dynamique d'une table de référence (vierge ou proposée à partir d'un fichier). */
@Component({
  selector: 'nx-new-table',
  imports: [RouterLink, Icon, ColumnEditor, ...I18N],
  template: `
    <div class="between">
      <div><h1>{{ 'Nouvelle table de référence' | t }}</h1>
        <p class="muted">{{ 'La table est créée à chaud : structure, fiche ISO 19115, table physique et historique, sans redéploiement.' | t }}</p></div>
      <a routerLink="/catalogue" class="btn ghost"><nx-icon name="back" /> {{ 'Catalogue' | t }}</a>
    </div>

    <ol class="etapes">
      @for (e of etapes; track e; let i = $index) {
        <li [class.on]="etape() === i" [class.fait]="etape() > i"><span>{{ etape() > i ? '✓' : i + 1 }}</span> {{ e | t }}</li>
      }
    </ol>

    @switch (etape()) {
      @case (0) {
        <div class="grid k2">
          <button class="card pad choix" (click)="partirDeZero()">
            <span class="ic"><nx-icon name="plus" [size]="26" /></span>
            <h2>{{ 'Partir de zéro' | t }}</h2><p class="muted">{{ 'Code, libellé FR, libellé EN : vous ajoutez ensuite vos attributs.' | t }}</p>
          </button>
          <label class="card pad choix" [class.survol]="survol()" (dragover)="$event.preventDefault(); survol.set(true)" (dragleave)="survol.set(false)"
            (drop)="$event.preventDefault(); survol.set(false); analyser($any($event).dataTransfer.files[0])">
            <input type="file" accept=".csv,.txt,.json,.xlsx" hidden (change)="analyser($any($event.target).files[0])" />
            <span class="ic ia"><nx-icon name="sparkles" [size]="26" /></span>
            <h2>{{ 'À partir d\\'un fichier' | t }}</h2>
            <p class="muted">{{ 'Déposez un CSV, JSON ou Excel : NEXORA propose la structure (types, code, libellés) et détecte les colonnes qui référencent une table existante (pays, devises, unités…).' | t }}</p>
            @if (analyse()) { <span class="chip accent">{{ 'Analyse en cours…' | t }}</span> }
          </label>
        </div>
        @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
      }
      @case (1) {
        <div class="card pad stack">
          @if (proposition(); as p) {
            <div class="alert info"><nx-icon name="sparkles" /> <div>{{ 'Structure proposée à partir de {0} ({1} lignes)' | t: fichier()?.name : p.rowCount }}
              @if (refs().length) { — {{ 'références détectées' | t }} : @for (r of refs(); track r[0]) { <span class="code-tag">{{ r[0] }} → {{ r[1] }}</span> } }</div></div>
          }
          <div class="form-grid">
            <div class="field"><label>{{ 'Code de la table' | t }} *</label><input class="input mono" [value]="code()" (input)="code.set($any($event.target).value.toUpperCase())" placeholder="REF_…" />
              <span class="hint">{{ 'Préfixe REF_ obligatoire ; devient aussi la table physique referentiel.{0}' | t: (code().toLowerCase() || 'ref_…') }}</span></div>
            <div class="field"><label>{{ 'Nom (FR)' | t }} *</label><input class="input" [value]="nom()" (input)="nom.set($any($event.target).value)" /></div>
            <div class="field"><label>{{ 'Nom (EN)' | t }}</label><input class="input" [value]="nomEn()" (input)="nomEn.set($any($event.target).value)" /></div>
            <div class="field"><label>{{ 'Catégorie' | t }} *</label>
              <select class="input" [value]="categorie()" (change)="choisirCategorie($any($event.target).value)">
                <option value="">{{ 'Choisir…' | t }}</option>
                @for (c of categories(); track c.code) { <option [value]="c.code" [selected]="c.code === categorie()">{{ c.parentCode ? '— ' : '' }}{{ c.labelFr | lib: c.labelEn }}</option> }
              </select></div>
            <div class="field"><label>{{ 'Source' | t }}</label>
              <select class="input" [value]="source()" (change)="source.set($any($event.target).value)">
                @for (s of sources; track s.code) { <option [value]="s.code" [selected]="s.code === source()">{{ s.label | t }}</option> }
              </select></div>
            <div class="field"><label>{{ 'Standards / références' | t }}</label><input class="input" [value]="standards()" (input)="standards.set($any($event.target).value)"
              [placeholder]="'Ex. UN/CEFACT Rec. 21 ; ISO 6346' | t" /></div>
            <div class="field full"><label>{{ 'Description' | t }}</label><textarea class="input" [value]="description()" (input)="description.set($any($event.target).value)"></textarea></div>
            <div class="field full"><label>{{ 'Phases Buy-Ship-Pay' | t }}</label>
              <div class="row">@for (p of phases; track p.code) {
                <label class="check"><input type="checkbox" [checked]="bsp().includes(p.code)" (change)="basculer(p.code)" /> <b>{{ p.label }}</b> <span class="muted small">{{ p.sub | t }}</span></label>
              }</div></div>
          </div>
          <div class="row"><button class="btn" (click)="etape.set(0)"><nx-icon name="back" /> {{ 'Retour' | t }}</button>
            <button class="btn primary" (click)="etape.set(2)" [disabled]="!code() || !nom() || !categorie()">{{ 'Structure' | t }} <nx-icon name="chevron" /></button></div>
        </div>
      }
      @case (2) {
        <div class="card pad stack">
          <h2>{{ 'Colonnes' | t }}</h2>
          <nx-column-editor [(colonnes)]="colonnes" />
          @if (proposition(); as p) {
            <details><summary class="small">{{ 'Aperçu du fichier ({0} premières lignes)' | t: p.sample.length }}</summary>
              <div class="scroll-x"><table class="datagrid"><thead><tr>@for (h of entetes(); track h) { <th>{{ h }}</th> }</tr></thead>
                <tbody>@for (l of p.sample; track $index) { <tr>@for (h of entetes(); track h) { <td class="small">{{ l[h] }}</td> }</tr> }</tbody></table></div></details>
          }
          <div class="row"><button class="btn" (click)="etape.set(1)"><nx-icon name="back" /> {{ 'Retour' | t }}</button>
            <button class="btn primary" (click)="etape.set(3)">{{ 'Vérifier' | t }} <nx-icon name="chevron" /></button></div>
        </div>
      }
      @case (3) {
        <div class="card pad stack">
          <h2>{{ 'Récapitulatif' | t }}</h2>
          <div class="recap">
            <span class="muted">{{ 'Table' | t }}</span><span><span class="code-tag">{{ code() }}</span> {{ nom() }}</span>
            <span class="muted">{{ 'Classement' | t }}</span><span>{{ libelleCategorie() }} · {{ source() }} · {{ bsp().join(' / ') || ('aucune phase' | t) }}</span>
            <span class="muted">{{ 'Contenu' | t }}</span><span>{{ attributs() ? ('Complexe ({0} attribut(s))' | t: attributs()) : ('Simple (code / libellés)' | t) }}</span>
            <span class="muted">{{ 'Colonnes' | t }}</span><span>@for (c of colonnes(); track c.key) { <span class="chip">{{ c.labelFr }} · {{ c.dataType }}{{ c.refTableCode ? ' → ' + c.refTableCode : '' }}</span> }</span>
          </div>
          @if (fichier() && proposition()) {
            <label class="check"><input type="checkbox" [checked]="importer()" (change)="importer.set($any($event.target).checked)" />
              {{ 'Charger aussi les {0} lignes de « {1} » après la création' | t: proposition()!.rowCount : fichier()!.name }}</label>
          }
          <div class="field"><label>{{ 'Motif (historique)' | t }}</label><input class="input" [value]="motif()" (input)="motif.set($any($event.target).value)"
            [placeholder]="'Ex. nouvelle liste demandée par le MINCOMMERCE' | t" /></div>
          @if (messages().length) { <div class="alert err"><ul>@for (m of messages(); track m) { <li>{{ m }}</li> }</ul></div> }
          <div class="row"><button class="btn" (click)="etape.set(2)"><nx-icon name="back" /> {{ 'Retour' | t }}</button>
            <button class="btn primary" (click)="creer()" [disabled]="occupe()"><nx-icon name="check" /> {{ 'Créer la table' | t }}</button></div>
        </div>
      }
    }
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 16px; }
    p { margin: 4px 0 0; }
    .etapes { display: flex; gap: 8px; list-style: none; padding: 0; margin: 0; flex-wrap: wrap; }
    .etapes li { display: flex; align-items: center; gap: 8px; padding: 6px 14px 6px 6px; border-radius: 999px; background: var(--surface); border: 1px solid var(--line);
      color: var(--muted); font-weight: 600; font-size: 13px; }
    .etapes li span { width: 24px; height: 24px; border-radius: 50%; display: grid; place-items: center; background: var(--chip); font-size: 12px; }
    .etapes li.on { color: var(--text); border-color: var(--accent); }
    .etapes li.on span { background: var(--gradient); color: #fff; }
    .etapes li.fait span { background: var(--ok); color: #fff; }
    .choix { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; text-align: left; cursor: pointer; font: inherit; color: var(--text);
      transition: transform .15s, border-color .15s; min-height: 200px; }
    .choix:hover, .choix.survol { transform: translateY(-2px); border-color: var(--accent); }
    .ic { width: 52px; height: 52px; border-radius: 15px; display: grid; place-items: center; background: var(--navy); color: #fff; }
    .ic.ia { background: var(--gradient); }
    .recap { display: grid; grid-template-columns: 140px 1fr; gap: 10px 16px; align-items: start; }
    .recap .chip { margin: 0 4px 4px 0; }
    @media (max-width: 600px) { .recap { grid-template-columns: 1fr; gap: 2px; } .recap .muted { margin-top: 8px; } }
  `],
})
export class NewTablePage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  protected readonly etapes = ['Point de départ', 'Identité', 'Structure', 'Création'];
  protected readonly etape = signal(0);
  protected readonly categories = signal<Category[]>([]);
  protected readonly phases = PHASES;
  protected readonly sources = SOURCES;

  protected readonly code = signal('REF_');
  protected readonly nom = signal('');
  protected readonly nomEn = signal('');
  protected readonly categorie = signal('');
  protected readonly source = signal<Source>('NATIONALE');
  protected readonly standards = signal('');
  protected readonly description = signal('');
  protected readonly bsp = signal<Phase[]>(['SHIP']);
  protected readonly colonnes = signal<ColumnDef[]>(VIDE.map(c => ({ ...c })));
  protected readonly motif = signal('');

  protected readonly fichier = signal<File | null>(null);
  protected readonly proposition = signal<Proposition | null>(null);
  protected readonly importer = signal(true);
  protected readonly survol = signal(false);
  protected readonly analyse = signal(false);
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly messages = signal<string[]>([]);

  protected readonly refs = computed(() => Object.entries(this.proposition()?.references ?? {}));
  protected readonly entetes = computed(() => Object.keys(this.proposition()?.sample[0] ?? {}));
  protected readonly attributs = computed(() => this.colonnes().filter(c => c.role === 'ATTRIBUTE').length);
  protected readonly libelleCategorie = computed(() => this.categories().find(c => c.code === this.categorie())?.labelFr ?? '');

  constructor() {
    this.api.categories().subscribe(c => this.categories.set(c));
  }

  protected partirDeZero(): void {
    this.proposition.set(null);
    this.fichier.set(null);
    this.colonnes.set(VIDE.map(c => ({ ...c })));
    this.etape.set(1);
  }

  protected analyser(f: File | undefined): void {
    if (!f) return;
    this.fichier.set(f);
    this.analyse.set(true);
    this.erreur.set(null);
    this.api.proposer(f).subscribe({
      next: p => {
        this.proposition.set(p);
        this.colonnes.set(p.columns);
        this.code.set(p.suggestedCode);
        this.nom.set(p.suggestedName);
        this.analyse.set(false);
        this.etape.set(1);
      },
      error: e => { this.erreur.set(erreurs(e).join(' ')); this.analyse.set(false); },
    });
  }

  protected choisirCategorie(code: string): void {
    this.categorie.set(code);
    const c = this.categories().find(x => x.code === code);
    if (c) this.source.set(c.source);
  }

  protected basculer(p: Phase): void {
    this.bsp.set(this.bsp().includes(p) ? this.bsp().filter(x => x !== p) : [...this.bsp(), p]);
  }

  protected creer(): void {
    const t: Partial<TableDef> = {
      code: this.code(), nameFr: this.nom(), nameEn: this.nomEn() || null, categoryCode: this.categorie(), source: this.source(),
      standards: this.standards() || null, description: this.description() || null, bspPhases: this.bsp(), columns: this.colonnes(),
      metadata: { metadataStandard: 'ISO-19115:2003', charset: 'UTF8', languages: 'Français, Anglais' }, status: 'ACTIVE',
    };
    this.occupe.set(true);
    this.messages.set([]);
    this.api.creerTable(t, this.motif()).subscribe({
      next: cree => {
        const f = this.fichier();
        if (f && this.proposition() && this.importer()) {
          this.api.importer(cree.code, f, 'MERGE', false, this.motif() || 'Chargement initial de la table').subscribe({
            next: () => this.router.navigate(['/tables', cree.code]),
            error: () => this.router.navigate(['/tables', cree.code], { queryParams: { onglet: 'chargement' } }),
          });
        } else {
          this.router.navigate(['/tables', cree.code], { queryParams: { onglet: 'structure' } });
        }
      },
      error: e => { this.messages.set(erreurs(e)); this.occupe.set(false); },
    });
  }
}
