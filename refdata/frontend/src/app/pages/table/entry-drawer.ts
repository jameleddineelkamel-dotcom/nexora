import { Component, computed, inject, input, output, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ApiService, erreurs } from '../../core/api.service';
import { ColumnDef, Entry, TableDef } from '../../core/models';
import { Icon } from '../../shared/icon';
import { RefPicker } from '../../shared/ref-picker';
import { HistoryList } from '../../shared/history-list';

/** Formulaire d'un code, généré à partir de la structure (colonnes typées) de la table. */
@Component({
  selector: 'nx-entry-drawer',
  imports: [DatePipe, Icon, RefPicker, HistoryList],
  template: `
    <div class="overlay" (click)="fermer.emit()"></div>
    <aside class="drawer" role="dialog" [attr.aria-label]="nouveau() ? 'Nouveau code' : 'Code ' + entree()?.code">
      <header class="between">
        <div>
          <h2>{{ nouveau() ? 'Nouveau code' : entree()!.code }}</h2>
          <span class="muted small">{{ table().nameFr }}</span>
        </div>
        <div class="row">
          @if (!nouveau()) {
            <span class="chip" [class.ok]="entree()!.status === 'ACTIVE'" [class.warn]="entree()!.status === 'INVALID'">
              {{ entree()!.status === 'ACTIVE' ? 'Actif' : 'Invalidé' }}</span>
          }
          <button class="btn icon ghost" (click)="fermer.emit()" aria-label="Fermer"><nx-icon name="x" /></button>
        </div>
      </header>
      <div class="body">
        @if (!nouveau()) {
          <div class="tabs mini">
            <button class="tab" [class.on]="vue() === 'form'" (click)="vue.set('form')">Détail</button>
            <button class="tab" [class.on]="vue() === 'hist'" (click)="vue.set('hist')"><nx-icon name="history" [size]="15" /> Historique du code</button>
          </div>
        }
        @if (vue() === 'hist') {
          <nx-history-list [table]="table().code" [code]="entree()!.code" />
        } @else {
          <form class="stack" (submit)="$event.preventDefault(); enregistrer()">
            <div class="field">
              <label for="f-code">{{ colCode()?.labelFr ?? 'Code' }} *</label>
              <input id="f-code" class="input mono" [value]="code()" (input)="code.set($any($event.target).value)" [readonly]="!nouveau()"
                [attr.maxlength]="colCode()?.maxLength" />
              @if (colCode()?.xmlTag) { <span class="hint">Balise {{ balise(colCode()!.xmlTag) }} @if (colCode()!.untded) { · UNTDED {{ colCode()!.untded }} }</span> }
            </div>
            @if (colFr()) {
              <div class="field"><label for="f-fr">{{ colFr()!.labelFr }} @if (colFr()!.required) { * }</label>
                <input id="f-fr" class="input" [value]="fr()" (input)="fr.set($any($event.target).value)" [attr.maxlength]="colFr()!.maxLength" /></div>
            }
            @if (colEn()) {
              <div class="field"><label for="f-en">{{ colEn()!.labelFr }}</label>
                <input id="f-en" class="input" [value]="en()" (input)="en.set($any($event.target).value)" [attr.maxlength]="colEn()!.maxLength" /></div>
            }
            @if (table().parentTableCode) {
              <div class="field"><label>Code parent ({{ table().parentTableCode }})</label>
                <nx-ref-picker [table]="table().parentTableCode!" [(value)]="parent" label="Code parent" /></div>
            }
            @if (attributs().length) {
              <h3 class="sep">Attributs</h3>
              <div class="form-grid">
                @for (c of attributs(); track c.key) {
                  <div class="field" [class.full]="c.dataType === 'TEXT' || c.dataType === 'CODE_REF'">
                    <label [attr.for]="'a-' + c.key">{{ c.labelFr }} @if (c.required) { * } @if (c.unit) { <span class="muted">({{ c.unit }})</span> }</label>
                    @switch (c.dataType) {
                      @case ('TEXT') { <textarea [id]="'a-' + c.key" class="input" [value]="val(c)" (input)="poser(c, $any($event.target).value)"></textarea> }
                      @case ('DATE') { <input [id]="'a-' + c.key" class="input" type="date" [value]="val(c)" (input)="poser(c, $any($event.target).value)" /> }
                      @case ('BOOLEAN') {
                        <select [id]="'a-' + c.key" class="input" [value]="val(c)" (change)="poser(c, $any($event.target).value)">
                          <option value="">—</option><option value="true">Oui</option><option value="false">Non</option></select>
                      }
                      @case ('CODE_REF') { <nx-ref-picker [table]="c.refTableCode!" [value]="val(c) || null" (valueChange)="poser(c, $event ?? '')" [label]="c.labelFr" /> }
                      @default {
                        <input [id]="'a-' + c.key" class="input" [class.mono]="c.dataType !== 'STRING'"
                          [attr.inputmode]="c.dataType === 'INTEGER' || c.dataType === 'DECIMAL' ? 'decimal' : null"
                          [value]="val(c)" (input)="poser(c, $any($event.target).value)" [attr.maxlength]="c.maxLength" />
                      }
                    }
                    @if (c.definition || c.xmlTag) { <span class="hint">{{ c.definition }} @if (c.xmlTag) { <code>{{ balise(c.xmlTag) }}</code> }</span> }
                  </div>
                }
              </div>
            }
            <h3 class="sep">Validité</h3>
            <div class="form-grid">
              <div class="field"><label for="f-du">Début de validité</label><input id="f-du" class="input" type="date" [value]="du()" (input)="du.set($any($event.target).value)" /></div>
              <div class="field"><label for="f-au">Fin de validité</label><input id="f-au" class="input" type="date" [value]="au()" (input)="au.set($any($event.target).value)" /></div>
            </div>
            <div class="field"><label for="f-motif">Motif de la modification (historique)</label>
              <input id="f-motif" class="input" [value]="motif()" (input)="motif.set($any($event.target).value)" placeholder="Ex. mise à jour ISO 3166 du 2026-06-01" /></div>
            @if (messages().length) { <div class="alert err"><nx-icon name="alert" /><ul>@for (m of messages(); track m) { <li>{{ m }}</li> }</ul></div> }
            @if (!nouveau()) {
              <p class="muted small">Version {{ entree()!.version }} · créé le {{ entree()!.createdAt | date: 'dd/MM/yyyy' }} · modifié le {{ entree()!.updatedAt | date: 'dd/MM/yyyy HH:mm' }}</p>
            }
            <button type="submit" hidden></button>
          </form>
        }
      </div>
      @if (vue() === 'form') {
        <footer>
          @if (!nouveau() && entree()!.status === 'ACTIVE') {
            @if (confirmation()) {
              <input class="input date" type="date" [value]="dateInvalidation()" (input)="dateInvalidation.set($any($event.target).value)" aria-label="Date d'invalidation" />
              <button class="btn danger" (click)="invalider()" [disabled]="occupe()"><nx-icon name="ban" /> Confirmer l'invalidation</button>
            } @else {
              <button class="btn danger" (click)="confirmation.set(true)"><nx-icon name="ban" /> Invalider</button>
            }
          }
          @if (!nouveau() && entree()!.status === 'INVALID') {
            <button class="btn" (click)="reactiver()" [disabled]="occupe()"><nx-icon name="refresh" /> Réactiver</button>
          }
          <span class="grow"></span>
          <button class="btn" (click)="fermer.emit()">Annuler</button>
          <button class="btn primary" (click)="enregistrer()" [disabled]="occupe()"><nx-icon name="check" /> Enregistrer</button>
        </footer>
      }
    </aside>
  `,
  styles: [`
    .sep { margin-top: 6px; padding-top: 12px; border-top: 1px solid var(--line); }
    .tabs.mini { margin: -6px 0 14px; }
    .date { width: 160px; }
    footer .alert { flex-basis: 100%; }
  `],
})
export class EntryDrawer {
  private readonly api = inject(ApiService);
  readonly table = input.required<TableDef>();
  readonly entree = input<Entry | null>(null);
  readonly fermer = output<void>();
  readonly enregistre = output<Entry>();

  protected readonly nouveau = computed(() => !this.entree());
  protected readonly colCode = computed(() => this.col('CODE'));
  protected readonly colFr = computed(() => this.col('LABEL_FR'));
  protected readonly colEn = computed(() => this.col('LABEL_EN'));
  protected readonly attributs = computed(() => this.table().columns.filter(c => c.role === 'ATTRIBUTE'));
  protected readonly vue = signal<'form' | 'hist'>('form');

  protected readonly code = signal('');
  protected readonly fr = signal('');
  protected readonly en = signal('');
  protected readonly parent = signal<string | null>(null);
  protected readonly du = signal('');
  protected readonly au = signal('');
  protected readonly motif = signal('');
  protected readonly valeurs = signal<Record<string, string>>({});
  protected readonly messages = signal<string[]>([]);
  protected readonly occupe = signal(false);
  protected readonly confirmation = signal(false);
  protected readonly dateInvalidation = signal(new Date().toISOString().slice(0, 10));

  ngOnInit(): void {
    const e = this.entree();
    if (!e) return;
    this.code.set(e.code);
    this.fr.set(e.labelFr ?? '');
    this.en.set(e.labelEn ?? '');
    this.parent.set(e.parentCode);
    this.du.set(e.validFrom ?? '');
    this.au.set(e.validTo ?? '');
    const v: Record<string, string> = {};
    for (const [k, x] of Object.entries(e.attributes ?? {})) v[k] = x === null || x === undefined ? '' : String(x);
    this.valeurs.set(v);
  }

  protected balise(tag: string | null | undefined): string { return tag ? '<' + tag + '>' : ''; }
  private col(role: string): ColumnDef | undefined { return this.table().columns.find(c => c.role === role); }
  protected val(c: ColumnDef): string { return this.valeurs()[c.key] ?? ''; }
  protected poser(c: ColumnDef, v: string): void { this.valeurs.set({ ...this.valeurs(), [c.key]: v }); }

  protected enregistrer(): void {
    const attributs: Record<string, string> = {};
    for (const [k, v] of Object.entries(this.valeurs())) if (v !== '') attributs[k] = v;
    const e: Partial<Entry> = {
      code: this.code().trim(), labelFr: this.fr() || null, labelEn: this.en() || null, parentCode: this.parent(),
      attributes: attributs, validFrom: this.du() || null, validTo: this.au() || null, version: this.entree()?.version,
    };
    this.occupe.set(true);
    const t = this.table().code;
    const req = this.nouveau() ? this.api.creerEntree(t, e, this.motif()) : this.api.modifierEntree(t, this.entree()!.code, e, this.motif());
    req.subscribe({ next: r => this.enregistre.emit(r), error: x => { this.messages.set(erreurs(x)); this.occupe.set(false); } });
  }

  protected invalider(): void {
    this.occupe.set(true);
    this.api.invalider(this.table().code, this.entree()!.code, this.dateInvalidation(), this.motif()).subscribe({
      next: r => this.enregistre.emit(r), error: x => { this.messages.set(erreurs(x)); this.occupe.set(false); },
    });
  }

  protected reactiver(): void {
    this.occupe.set(true);
    this.api.reactiver(this.table().code, this.entree()!.code, this.motif()).subscribe({
      next: r => this.enregistre.emit(r), error: x => { this.messages.set(erreurs(x)); this.occupe.set(false); },
    });
  }
}
