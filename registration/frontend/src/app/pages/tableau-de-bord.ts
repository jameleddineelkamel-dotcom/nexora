import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../core/api';
import { AuthService, DROITS } from '../core/auth.service';
import { I18N } from '../core/i18n';
import { erreurs } from '../core/session';
import { Icon } from '../shared/icon';

/** Tableau de bord du registre des opérateurs économiques. */
@Component({
  selector: 'nx-tableau-de-bord',
  imports: [RouterLink, Icon, ...I18N],
  template: `
    <section class="hero">
      <div>
        <span class="eyebrow"><nx-icon name="building" [size]="14" /> {{ 'NEXORA · Registration' | t }}</span>
        <h1>{{ 'Registre des opérateurs économiques' | t }}</h1>
        <p>{{ 'Enrôlement au guichet unique : NIU, immatriculations, adresses, pièces réutilisées par toutes les procédures, comptes rattachés (titulaire, CDA). Les procédures complètent la fiche à leur validation (ex. FIMEX, RG2 UC-05).' | t }}</p>
        <div class="row"><a routerLink="/operateurs" class="btn primary"><nx-icon name="search" /> {{ 'Rechercher un opérateur' | t }}</a>
          @if (auth.a(d.gerer)) { <a routerLink="/nouvel-operateur" class="btn"><nx-icon name="plus" /> {{ 'Enrôler un opérateur' | t }}</a> }</div>
      </div>
    </section>
    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
    @if (tb(); as x) {
      <div class="grid k4">
        <div class="card pad kpi"><span class="ic" style="--c: var(--blue)"><nx-icon name="building" /></span><div><b>{{ x['parties'] }}</b><span>{{ 'opérateurs enrôlés' | t }}</span><small>{{ '{0} actifs' | t: x['active'] }}</small></div></div>
        <div class="card pad kpi"><span class="ic" style="--c: var(--cyan)"><nx-icon name="users" /></span><div><b>{{ x['legalEntities'] }}</b><span>{{ 'personnes morales' | t }}</span><small>{{ '{0} étrangers' | t: x['foreign'] }}</small></div></div>
        <div class="card pad kpi"><span class="ic" style="--c: var(--green)"><nx-icon name="archive" /></span><div><b>{{ x['documents'] }}</b><span>{{ 'pièces d\\'enrôlement' | t }}</span></div></div>
        <div class="card pad kpi"><span class="ic" style="--c: var(--warn)"><nx-icon name="user" /></span><div><b>{{ x['accounts'] }}</b><span>{{ 'comptes rattachés' | t }}</span></div></div>
      </div>
    }
  `,
  styles: [`:host { display: flex; flex-direction: column; gap: 18px; } .hero { grid-template-columns: 1fr; }`],
})
export class TableauDeBordPage {
  private readonly api = inject(Api);
  protected readonly auth = inject(AuthService);
  protected readonly d = DROITS;
  protected readonly tb = signal<Record<string, number> | null>(null);
  protected readonly erreur = signal<string | null>(null);

  constructor() {
    this.api.tableauDeBord().subscribe({ next: t => this.tb.set(t), error: e => this.erreur.set(erreurs(e, 'Le registre').join(' ')) });
  }
}
