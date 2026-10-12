import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Api, Evenement, Supervision } from '../core/api';
import { I18N } from '../core/i18n';
import { erreurs } from '../core/session';
import { Icon } from '../shared/icon';
import { LIBELLES, NATURES } from '../shared/widgets';

/** Supervision : dossiers par statut et par type, délais (SLA), messages par format, encaissements, journal d'audit. */
@Component({
  selector: 'nx-supervision',
  imports: [RouterLink, DatePipe, Icon, ...I18N],
  template: `
    <div class="page-head">
      <div><h1>{{ 'Supervision' | t }}</h1><p class="muted">{{ 'Pilotage des procédures déployées : volumes, délais, échanges et encaissements.' | t }}</p></div>
      <button class="btn" (click)="charger()"><nx-icon name="refresh" /> {{ 'Actualiser' | t }}</button>
    </div>
    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
    @if (s(); as x) {
      <div class="grid k4">
        <div class="card pad kpi"><span class="ic" style="--c: var(--blue)"><nx-icon name="layers" /></span><div><b>{{ total() | num }}</b><span>{{ 'dossiers déposés' | t }}</span><small>{{ '{0} actifs' | t: x.sla.active }}</small></div></div>
        <div class="card pad kpi"><span class="ic" style="--c: var(--danger)"><nx-icon name="clock" /></span><div><b>{{ x.sla.overdue }}</b><span>{{ 'en retard' | t }}</span><small>{{ 'échéance dépassée' | t }}</small></div></div>
        <div class="card pad kpi"><span class="ic" style="--c: var(--green)"><nx-icon name="shield" /></span><div><b>{{ x.sla.avgHoursToIssue | num }} h</b><span>{{ 'délai moyen de délivrance' | t }}</span><small>{{ 'cible SFD : 48 h' | t }}</small></div></div>
        <div class="card pad kpi"><span class="ic" style="--c: var(--warn)"><nx-icon name="wallet" /></span><div><b>{{ x.payments.totals.paidAmount | num }}</b><span>{{ 'XAF encaissés' | t }}</span><small>{{ '{0} avis en attente' | t: x.payments.totals.pendingCount }}</small></div></div>
      </div>
      @if (x.incidents) { <div class="alert warn"><nx-icon name="alert" /> {{ '{0} incident(s) du moteur de workflow à traiter dans l\\'administration.' | t: x.incidents }}</div> }
      <div class="grid k2">
        <div class="card pad">
          <div class="card-title"><h2>{{ 'Dossiers par statut' | t }}</h2></div>
          @for (b of x.byStatus; track b.procedure + b.status) {
            <div class="barre"><span class="lib">{{ libelles[b.status] ?? b.status | t }} <span class="muted small">{{ b.procedure }}</span></span>
              <span class="jauge"><i [style.width.%]="b.count / max() * 100"></i></span><b>{{ b.count }}</b></div>
          } @empty { <div class="muted small">{{ 'Aucun dossier déposé.' | t }}</div> }
        </div>
        <div class="card pad">
          <div class="card-title"><h2>{{ 'Dossiers par type et nature' | t }}</h2></div>
          <table class="datagrid"><thead><tr><th>{{ 'Procédure' | t }}</th><th>{{ 'Type' | t }}</th><th>{{ 'Nature' | t }}</th><th>{{ 'Nombre' | t }}</th></tr></thead>
            <tbody>@for (t of x.byType; track t.procedure + t.type + t.nature) { <tr><td>{{ t.procedure }}</td><td>{{ t.type }}</td><td>{{ natures[t.nature] | t }}</td><td>{{ t.count }}</td></tr> }</tbody></table>
        </div>
        <div class="card pad">
          <div class="between card-title"><h2>{{ 'Encaissements par bénéficiaire' | t }}</h2><a routerLink="/paiements" class="small">{{ 'Détail' | t }}</a></div>
          @for (b of x.payments.byBeneficiary; track b.beneficiary) {
            <div class="barre"><span class="lib">{{ b.beneficiary }} <span class="muted small">{{ b.notices }} {{ 'avis' | t }}</span></span>
              <span class="jauge"><i [style.width.%]="b.amount / maxMontant() * 100"></i></span><b>{{ b.amount | num }}</b></div>
          } @empty { <div class="muted small">{{ 'Aucun encaissement.' | t }}</div> }
        </div>
        <div class="card pad">
          <div class="between card-title"><h2>{{ 'Messages par format' | t }}</h2><a routerLink="/messages" class="small">{{ 'Journal des messages' | t }}</a></div>
          <div class="formats">
            @for (m of x.messagesByFormat; track m.format + m.direction) {
              <div class="fmt"><b>{{ m.count }}</b><span class="chip info">{{ m.format }}</span><span class="muted small">{{ m.direction === 'IN' ? ('reçus' | t) : ('émis' | t) }}</span></div>
            } @empty { <div class="muted small">{{ 'Aucun message.' | t }}</div> }
          </div>
          <h3 class="sep">{{ 'Procédures déployées' | t }}</h3>
          @for (d of x.deployments; track d.id) { <div class="small">{{ d.labelFr }} — v{{ d.versionNo }} <span class="muted">· {{ d.deployedAt | date: 'dd/MM/yyyy' }}</span></div> }
        </div>
      </div>
      <div class="card pad">
        <div class="card-title"><h2>{{ 'Journal d\\'audit (chaîné)' | t }}</h2></div>
        <div class="scroll-x"><table class="datagrid">
          <thead><tr><th>{{ 'Date' | t }}</th><th>{{ 'Acteur' | t }}</th><th>{{ 'Action' | t }}</th><th>{{ 'Dossier' | t }}</th><th>{{ 'Empreinte' | t }}</th></tr></thead>
          <tbody>@for (e of journal(); track e.id) {
            <tr><td class="small">{{ e.occurredAt | date: 'dd/MM HH:mm:ss' }}</td><td>{{ e.actor }} <span class="muted small">{{ e.channel }}</span></td>
              <td><span class="code-tag">{{ e.actionCode }}</span></td><td class="mono small">{{ e.dossierNo ?? '' }}</td><td class="mono small muted">{{ e.rowHash.slice(0, 16) }}…</td></tr>
          }</tbody></table></div>
      </div>
    } @else if (!erreur()) { <div class="skeleton" style="height: 200px"></div> }
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 16px; }
    .barre { display: grid; grid-template-columns: minmax(150px, 1.2fr) 2fr 60px; gap: 10px; align-items: center; padding: 5px 0; }
    .barre b { text-align: right; font-variant-numeric: tabular-nums; }
    .jauge { height: 9px; border-radius: 99px; background: var(--chip); overflow: hidden; }
    .jauge i { display: block; height: 100%; background: var(--gradient); border-radius: 99px; }
    .formats { display: flex; gap: 10px; flex-wrap: wrap; }
    .fmt { display: flex; flex-direction: column; align-items: center; gap: 4px; padding: 10px 14px; border: 1px solid var(--line); border-radius: 12px; min-width: 90px; }
    .fmt b { font-size: 20px; }
  `],
})
export class SupervisionPage {
  private readonly api = inject(Api);
  protected readonly libelles = LIBELLES;
  protected readonly natures = NATURES;
  protected readonly s = signal<Supervision | null>(null);
  protected readonly journal = signal<Evenement[]>([]);
  protected readonly erreur = signal<string | null>(null);
  protected readonly total = computed(() => (this.s()?.byStatus ?? []).reduce((a, b) => a + b.count, 0));
  protected readonly max = computed(() => Math.max(1, ...(this.s()?.byStatus ?? []).map(b => b.count)));
  protected readonly maxMontant = computed(() => Math.max(1, ...(this.s()?.payments.byBeneficiary ?? []).map(b => Number(b.amount))));

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.api.supervision().subscribe({ next: s => this.s.set(s), error: e => this.erreur.set(erreurs(e).join(' ')) });
    this.api.journal(40).subscribe({ next: j => this.journal.set(j), error: () => this.journal.set([]) });
  }
}
