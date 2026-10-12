import { Component, inject, signal } from '@angular/core';
import { DatePipe, JsonPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Api, Evenement } from '../core/api';
import { I18N } from '../core/i18n';
import { erreurs } from '../core/session';

/** Journal d'audit chaîné du studio (ajout seul, empreintes prev_hash / row_hash). */
@Component({
  selector: 'nx-journal',
  imports: [RouterLink, DatePipe, JsonPipe, ...I18N],
  template: `
    <div class="page-head"><div><h1>{{ 'Journal d\\'audit' | t }}</h1><p class="muted">{{ 'Chaque événement est chaîné au précédent par son empreinte SHA-256 ; le journal est en ajout seul (RG-GOV-03).' | t }}</p></div></div>
    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
    <div class="card scroll-x"><table class="datagrid">
      <thead><tr><th>{{ 'Date' | t }}</th><th>{{ 'Acteur' | t }}</th><th>{{ 'Action' | t }}</th><th>{{ 'Procédure' | t }}</th><th>{{ 'Détails' | t }}</th><th>{{ 'Empreinte' | t }}</th></tr></thead>
      <tbody>@for (e of journal(); track e.id) {
        <tr><td class="small">{{ e.occurredAt | date: 'dd/MM/yyyy HH:mm:ss' }}</td><td>{{ e.actor }} <span class="muted small">{{ e.channel }}</span></td>
          <td><span class="code-tag">{{ e.actionCode }}</span> @if (e.policyDecision) { <span class="chip">{{ e.policyDecision }}</span> }</td>
          <td>@if (e.versionId) { <a [routerLink]="['/versions', e.versionId]">{{ e.procedureCode }}</a> } @else { {{ e.procedureCode }} }</td>
          <td class="small mono">{{ e.details | json }}@if (e.reason) { <div class="muted">{{ e.reason }}</div> }</td><td class="mono small muted">{{ e.rowHash.slice(0, 16) }}…</td></tr>
      }</tbody></table></div>
  `,
  styles: [`:host { display: flex; flex-direction: column; gap: 16px; } .datagrid tbody tr { cursor: default; } td.mono { max-width: 420px; overflow-wrap: anywhere; }`],
})
export class JournalPage {
  private readonly api = inject(Api);
  protected readonly journal = signal<Evenement[]>([]);
  protected readonly erreur = signal<string | null>(null);

  constructor() {
    this.api.journal(500).subscribe({ next: j => this.journal.set(j), error: e => this.erreur.set(erreurs(e).join(' ')) });
  }
}
