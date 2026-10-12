import { Component, inject, signal } from '@angular/core';
import { Api, Encaissements, Message, Procedure } from '../core/api';
import { I18N } from '../core/i18n';
import { erreurs } from '../core/session';
import { Icon } from '../shared/icon';
import { MessageDrawer } from './dossier/message-drawer';

/** Encaissements par bénéficiaire (MINCOMMERCE, CNCC, GUCE, DGI) et relevé de reversement au format CSV. */
@Component({
  selector: 'nx-paiements',
  imports: [Icon, MessageDrawer, ...I18N],
  template: `
    <div class="page-head">
      <div><h1>{{ 'Encaissements' | t }}</h1><p class="muted">{{ 'Répartition des frais payés entre les bénéficiaires ; le relevé est émis comme message CSV du catalogue (flux de reversement).' | t }}</p></div>
    </div>
    <div class="card pad row filtres">
      <div class="field"><label for="p">{{ 'Procédure' | t }}</label>
        <select id="p" class="input" (change)="procedure.set($any($event.target).value); charger()">
          <option value="">{{ 'Toutes' | t }}</option>
          @for (c of catalogue(); track c.code) { <option [value]="c.code" [selected]="procedure() === c.code">{{ c.labelFr | lib: c.labelEn }}</option> }
        </select></div>
      <div class="field"><label for="du">{{ 'Du' | t }}</label><input id="du" type="date" class="input" [value]="du()" (input)="du.set($any($event.target).value)" /></div>
      <div class="field"><label for="au">{{ 'Au' | t }}</label><input id="au" type="date" class="input" [value]="au()" (input)="au.set($any($event.target).value)" /></div>
      <button class="btn" (click)="charger()"><nx-icon name="search" /> {{ 'Filtrer' | t }}</button>
    </div>
    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
    @if (e(); as x) {
      <div class="grid k4">
        <div class="card pad kpi"><span class="ic" style="--c: var(--green)"><nx-icon name="wallet" /></span><div><b>{{ x.totals.paidAmount | num }}</b><span>{{ 'XAF encaissés' | t }}</span><small>{{ '{0} avis payés' | t: x.totals.paidCount }}</small></div></div>
        <div class="card pad kpi"><span class="ic" style="--c: var(--warn)"><nx-icon name="clock" /></span><div><b>{{ x.totals.pendingAmount | num }}</b><span>{{ 'XAF en attente' | t }}</span><small>{{ '{0} avis émis' | t: x.totals.pendingCount }}</small></div></div>
      </div>
      <div class="card scroll-x">
        <table class="datagrid">
          <thead><tr><th>{{ 'Bénéficiaire' | t }}</th><th>{{ 'Avis' | t }}</th><th>{{ 'Montant' | t }}</th><th></th></tr></thead>
          <tbody>
            @for (b of x.byBeneficiary; track b.beneficiary) {
              <tr><td><b>{{ b.beneficiary }}</b></td><td>{{ b.notices }}</td><td>{{ b.amount | num }} {{ b.currency }}</td>
                <td><button class="btn sm" (click)="releve(b.beneficiary)" [disabled]="!procedure()"><nx-icon name="download" [size]="15" /> {{ 'Relevé CSV' | t }}</button></td></tr>
            } @empty { <tr><td colspan="4" class="empty">{{ 'Aucun encaissement.' | t }}</td></tr> }
          </tbody>
        </table>
      </div>
      @if (!procedure()) { <p class="muted small">{{ 'Choisissez une procédure pour émettre un relevé.' | t }}</p> }
    }
    @if (message(); as m) { <nx-message-drawer [message]="m" (fermer)="message.set(null)" /> }
  `,
  styles: [`:host { display: flex; flex-direction: column; gap: 16px; } .filtres { align-items: flex-end; } .datagrid tbody tr { cursor: default; }`],
})
export class PaiementsPage {
  private readonly api = inject(Api);
  protected readonly procedure = signal('');
  protected readonly catalogue = signal<Procedure[]>([]);
  protected readonly du = signal('');
  protected readonly au = signal('');
  protected readonly e = signal<Encaissements | null>(null);
  protected readonly message = signal<Message | null>(null);
  protected readonly erreur = signal<string | null>(null);

  constructor() {
    this.api.catalogue().subscribe({ next: c => { this.catalogue.set(c); if (c.length === 1) { this.procedure.set(c[0].code); this.charger(); } } });
    this.charger();
  }

  protected charger(): void {
    this.api.encaissements({ procedure: this.procedure(), from: this.du(), to: this.au() }).subscribe({
      next: e => { this.e.set(e); this.erreur.set(null); }, error: e => this.erreur.set(erreurs(e).join(' ')),
    });
  }

  protected releve(b: string): void {
    this.api.releve(this.procedure(), b).subscribe({ next: m => this.message.set(m), error: e => this.erreur.set(erreurs(e).join(' ')) });
  }
}
