import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Api, Operateur } from '../core/api';
import { I18N } from '../core/i18n';
import { erreurs } from '../core/session';
import { Icon } from '../shared/icon';

/** Recherche des opérateurs économiques (NIU, raison sociale, RCCM, ville). */
@Component({
  selector: 'nx-operateurs',
  imports: [Icon, ...I18N],
  template: `
    <div class="page-head"><div><h1>{{ 'Opérateurs économiques' | t }}</h1><p class="muted">{{ 'Recherche sur le NIU, la raison sociale, le RCCM ou la ville.' | t }}</p></div></div>
    <div class="card pad row"><nx-icon name="search" />
      <input class="input grow" [value]="q()" (input)="q.set($any($event.target).value)" (keydown.enter)="charger()" [placeholder]="'NIU, raison sociale, RCCM…' | t" />
      <button class="btn" (click)="charger()">{{ 'Rechercher' | t }}</button></div>
    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
    <div class="card scroll-x"><table class="datagrid">
      <thead><tr><th>NIU</th><th>{{ 'Raison sociale' | t }}</th><th>{{ 'Personnalité' | t }}</th><th>{{ 'Ville' | t }}</th><th>RCCM</th><th>{{ 'Statut' | t }}</th></tr></thead>
      <tbody>@for (o of liste(); track o.niu) {
        <tr (click)="router.navigate(['/operateurs', o.niu])"><td class="mono"><b>{{ o.niu }}</b></td><td>{{ o.legalName }}</td>
          <td>{{ $any(o)['partyTypeCode'] }} @if ($any(o)['nationality'] === 'E') { <span class="chip warn">{{ 'étranger' | t }}</span> }</td>
          <td>{{ $any(o)['city'] }}</td><td class="mono small">{{ $any(o)['rccm'] }}</td>
          <td><span class="chip" [class.ok]="o.status === 'ACTIVE'" [class.danger]="o.status !== 'ACTIVE'">{{ (o.status === 'ACTIVE' ? 'Actif' : 'Suspendu') | t }}</span></td></tr>
      } @empty { <tr><td colspan="6" class="empty">{{ 'Aucun opérateur.' | t }}</td></tr> }</tbody></table></div>
  `,
  styles: [`:host { display: flex; flex-direction: column; gap: 16px; }`],
})
export class OperateursPage {
  private readonly api = inject(Api);
  private readonly route = inject(ActivatedRoute);
  protected readonly router = inject(Router);
  protected readonly q = signal('');
  protected readonly liste = signal<Operateur[]>([]);
  protected readonly erreur = signal<string | null>(null);

  constructor() {
    this.route.queryParamMap.subscribe(p => { this.q.set(p.get('q') ?? ''); this.charger(); });
  }

  protected charger(): void {
    this.api.rechercher(this.q()).subscribe({ next: l => { this.liste.set(l); this.erreur.set(null); }, error: e => this.erreur.set(erreurs(e, 'Le registre').join(' ')) });
  }
}
