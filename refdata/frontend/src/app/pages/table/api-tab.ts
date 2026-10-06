import { Component, computed, inject, input, signal } from '@angular/core';
import { TableDef } from '../../core/models';
import { Icon } from '../../shared/icon';
import { I18N, I18n } from '../../core/i18n';

/** Points d'accès pour les autres microservices NEXORA (Party Management, Procédures, e-Services, Services DATA). */
@Component({
  selector: 'nx-api-tab',
  imports: [Icon, ...I18N],
  template: `
    <div class="grid k2">
      <div class="card pad stack">
        <h2><nx-icon name="code" /> {{ 'Services REST' | t }}</h2>
        <p class="muted small">{{ 'Interopérabilité (exigence e-Guce+_REF-07) : les autres services de la plateforme interrogent le référentiel par ces points d\\'accès.' | t }}</p>
        @for (a of appels(); track a.titre) {
          <div class="appel">
            <div class="between"><b>{{ a.titre | t }}</b><button class="btn ghost sm" (click)="copier(a.url)"><nx-icon [name]="copie() === a.url ? 'check' : 'file'" [size]="14" /> {{ 'Copier' | t }}</button></div>
            <code class="url"><span class="verbe">{{ a.verbe }}</span> {{ a.url }}</code>
            <span class="muted small">{{ a.desc | t }}</span>
          </div>
        }
      </div>
      <div class="stack">
        <div class="card pad stack">
          <h2><nx-icon name="database" /> {{ 'Table physique PostgreSQL' | t }}</h2>
          <p class="muted small">{{ 'Vraie table, tenue à jour en temps réel, pour les autres systèmes et les extractions DataWarehouse / Big Data (exigence e-Guce+_REF-06) : colonnes typées nommées d\\'après les balises XML, clé primaire sur le code.' | t }}</p>
          <pre>{{ sql() }}</pre>
        </div>
        <div class="card pad stack">
          <h2><nx-icon name="tag" /> {{ 'Dictionnaire de données' | t }}</h2>
          <div class="scroll-x"><table class="datagrid">
            <thead><tr><th>{{ 'Colonne SQL' | t }}</th><th>{{ 'Balise XML' | t }}</th><th>UNTDED</th><th>{{ 'Type' | t }}</th></tr></thead>
            <tbody>@for (c of table().columns; track c.key) {
              <tr><td class="mono">{{ c.key.toLowerCase() }}</td><td class="mono small">{{ c.xmlTag ?? '—' }}</td><td class="mono small">{{ c.untded ?? '—' }}</td>
                <td class="small">{{ c.dataType }}@if (c.maxLength) { ..{{ c.maxLength }} }@if (c.refTableCode) { → {{ c.refTableCode }} }</td></tr>
            }</tbody>
          </table></div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    p { margin: 0; }
    .appel { display: flex; flex-direction: column; gap: 4px; padding: 10px 0; border-bottom: 1px solid var(--line); }
    .url { display: block; padding: 8px 10px; border-radius: 8px; background: var(--surface-2); overflow-x: auto; white-space: nowrap; }
    .verbe { color: var(--ok); font-weight: 700; }
    pre { margin: 0; padding: 12px; border-radius: 10px; background: #0b1730; color: #d5e3ff; font: 12.5px/1.5 "Cascadia Code", Consolas, monospace; overflow-x: auto; }
    .datagrid tbody tr { cursor: default; }
  `],
})
export class ApiTab {
  readonly table = input.required<TableDef>();
  protected readonly copie = signal<string | null>(null);
  private readonly i18n = inject(I18n);
  private readonly base = `${location.origin}/api/v1`;

  protected readonly appels = computed(() => {
    const c = this.table().code;
    const exemple = encodeURIComponent('');
    return [
      { titre: 'Liste des codes valides', verbe: 'GET', url: `${this.base}/lookup/${c}?lang=fr&validAt=${new Date().toISOString().slice(0, 10)}`, desc: 'Codes actifs à une date, libellés en fr ou en (listes déroulantes, contrôles de saisie).' },
      { titre: 'Vérifier un code', verbe: 'GET', url: `${this.base}/lookup/${c}/resolve?code=${exemple}&lang=en`, desc: 'Renvoie le libellé si le code est valide, sinon 404.' },
      { titre: 'Recherche paginée', verbe: 'GET', url: `${this.base}/tables/${c}/entries?q=&status=ACTIVE&page=0&size=50`, desc: 'Recherche sans accents, filtres de statut, de validité et de hiérarchie.' },
      { titre: 'Définition et métadonnées', verbe: 'GET', url: `${this.base}/tables/${c}`, desc: 'Structure, fiche ISO 19115, phases Buy-Ship-Pay.' },
      { titre: 'Export', verbe: 'GET', url: `${this.base}/tables/${c}/export?format=json`, desc: 'csv, json ou xlsx — synchronisation des systèmes partenaires.' },
      { titre: 'Historique', verbe: 'GET', url: `${this.base}/history?table=${c}`, desc: 'Qui, quand, quoi, pourquoi, par quel canal.' },
    ];
  });

  protected readonly sql = computed(() => {
    const t = this.table();
    const cols = t.columns.map(c => '  ' + c.key.toLowerCase()).join(',\n');
    return `-- ${t.code} — ${this.i18n.libelle(t.nameFr, t.nameEn)}\nSELECT\n${cols},\n  ref_valid_from, ref_valid_to, ref_status\nFROM ${t.sqlView}\nWHERE ref_status = 'ACTIVE';`;
  });

  protected copier(url: string): void {
    navigator.clipboard?.writeText(url).then(() => { this.copie.set(url); setTimeout(() => this.copie.set(null), 1500); });
  }
}
