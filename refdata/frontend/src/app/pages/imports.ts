import { Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { ImportRun } from '../core/models';

@Component({
  selector: 'nx-imports',
  imports: [DatePipe, RouterLink],
  template: `
    <div>
      <h1>Journal des chargements</h1>
      <p class="muted">Chargements de listes de codes (fichiers, synchronisations) et chargement initial du Référentiel Commun.</p>
    </div>
    <div class="card scroll-x">
      <table class="datagrid">
        <thead><tr><th>Date</th><th>Lot</th><th>Table</th><th>Fichier</th><th>Mode</th><th>Lignes</th><th>Créés</th><th>Modifiés</th><th>Invalidés</th><th>Rejetés</th><th>Auteur</th></tr></thead>
        <tbody>
          @for (c of chargements(); track c.id) {
            <tr [routerLink]="['/tables', c.tableCode]" [queryParams]="{ onglet: 'chargement' }">
              <td class="small">{{ c.occurredAt | date: 'dd/MM/yyyy HH:mm' }}</td><td class="mono small">{{ c.id }}</td>
              <td><span class="code-tag">{{ c.tableCode }}</span></td><td class="small muted">{{ c.fileName }}</td>
              <td><span class="chip" [class.accent]="c.mode === 'SEED'">{{ modes[c.mode] ?? c.mode }}</span></td>
              <td class="mono">{{ c.total }}</td><td class="mono">{{ c.created }}</td><td class="mono">{{ c.updated }}</td>
              <td class="mono">{{ c.invalidated }}</td><td class="mono" [class.rouge]="c.rejected">{{ c.rejected }}</td><td class="small">{{ c.author }}</td>
            </tr>
          } @empty { <tr><td colspan="11" class="empty">Aucun chargement.</td></tr> }
        </tbody>
      </table>
    </div>
  `,
  styles: [':host { display: flex; flex-direction: column; gap: 16px; } p { margin: 4px 0 0; } .rouge { color: var(--danger); font-weight: 700; }'],
})
export class ImportsPage {
  private readonly api = inject(ApiService);
  protected readonly chargements = signal<ImportRun[]>([]);
  protected readonly modes: Record<string, string> = { SEED: 'Initial', MERGE: 'Fusion', REPLACE: 'Remplacement' };

  constructor() {
    this.api.chargements().subscribe(c => this.chargements.set(c));
  }
}
