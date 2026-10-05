import { Component, inject, input, output, signal } from '@angular/core';
import { ApiService, erreurs } from '../../core/api.service';
import { ColumnDef, TableDef } from '../../core/models';
import { Icon } from '../../shared/icon';
import { ColumnEditor } from '../../shared/column-editor';

@Component({
  selector: 'nx-structure-tab',
  imports: [Icon, ColumnEditor],
  template: `
    <div class="card pad stack">
      <div class="between">
        <div>
          <h2>Structure de la table</h2>
          <p class="muted small">Colonnes typées. Le <b>code</b> est la clé ; les <b>libellés</b> FR/EN servent aux listes ; les <b>attributs</b>
            font de la liste une liste « complexe ». Chaque modification est historisée et la vue SQL <code>{{ table().sqlView }}</code> est régénérée.</p>
        </div>
        <div class="legende small">
          <span><i style="background: var(--navy)"></i> Code</span><span><i style="background: var(--cyan)"></i> Libellés</span><span><i style="background: var(--green)"></i> Attributs</span>
        </div>
      </div>
      <nx-column-editor [(colonnes)]="colonnes" [tableCode]="table().code" />
      <div class="field"><label for="m-structure">Motif de la modification</label>
        <input id="m-structure" class="input" [value]="motif()" (input)="motif.set($any($event.target).value)" placeholder="Ex. ajout du code IATA (Rec. 16 Rév. 4)" /></div>
      @if (messages().length) {
        <div class="alert err"><nx-icon name="alert" /><div><ul>@for (m of messages(); track m) { <li>{{ m }}</li> }</ul>
          @if (aConfirmer()) { <button class="btn sm danger" (click)="enregistrer(true)">Confirmer le retrait et effacer ces valeurs</button> }</div></div>
      }
      @if (ok()) { <div class="alert ok"><nx-icon name="check" /> Structure enregistrée, vue SQL régénérée.</div> }
      <div class="row">
        <button class="btn primary" (click)="enregistrer(false)" [disabled]="occupe()"><nx-icon name="check" /> Enregistrer la structure</button>
        <button class="btn ghost" (click)="annuler()">Annuler les changements</button>
      </div>
    </div>
  `,
  styles: [`
    p { margin: 4px 0 0; }
    .legende { display: flex; gap: 12px; color: var(--muted); }
    .legende i { display: inline-block; width: 10px; height: 10px; border-radius: 3px; margin-right: 4px; }
  `],
})
export class StructureTab {
  private readonly api = inject(ApiService);
  readonly table = input.required<TableDef>();
  readonly enregistree = output<TableDef>();
  protected readonly colonnes = signal<ColumnDef[]>([]);
  protected readonly motif = signal('');
  protected readonly messages = signal<string[]>([]);
  protected readonly aConfirmer = signal(false);
  protected readonly occupe = signal(false);
  protected readonly ok = signal(false);

  ngOnInit(): void { this.annuler(); }

  protected annuler(): void {
    this.colonnes.set(this.table().columns.map(c => ({ ...c })));
    this.messages.set([]);
    this.aConfirmer.set(false);
  }

  protected enregistrer(force: boolean): void {
    const t = this.table();
    this.occupe.set(true);
    this.ok.set(false);
    this.api.modifierTable(t.code, { ...t, columns: this.colonnes() }, force, this.motif()).subscribe({
      next: r => { this.occupe.set(false); this.messages.set([]); this.aConfirmer.set(false); this.ok.set(true); this.enregistree.emit(r); },
      error: e => {
        this.occupe.set(false);
        const m = erreurs(e);
        this.messages.set(m);
        this.aConfirmer.set(m.some(x => x.includes('confirmez son retrait')));
      },
    });
  }
}
