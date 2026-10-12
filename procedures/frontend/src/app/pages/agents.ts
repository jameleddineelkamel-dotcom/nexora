import { Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { AgentInfo, Api, Execution } from '../core/api';
import { AuthService, DROITS } from '../core/auth.service';
import { I18N } from '../core/i18n';
import { erreurs } from '../core/session';
import { Icon } from '../shared/icon';

/** Agents IA : rôle, produit, politique d'autonomie par action (§8.3), coupe-circuit (AC-AG-06) et exécutions tracées. */
@Component({
  selector: 'nx-agents',
  imports: [DatePipe, Icon, ...I18N],
  template: `
    <div class="page-head"><div><h1>{{ 'Agents IA' | t }}</h1>
      <p class="muted">{{ 'L\\'IA propose, les règles déterministes contrôlent, les experts valident, le système trace. Modèle utilisé : {0}.' | t: modele() }}</p></div></div>
    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
    <div class="grid k3">
      @for (a of agents(); track a.code) {
        <div class="card pad stack agent" [class.off]="!a.enabled">
          <div class="between"><div class="card-title"><nx-icon name="robot" /><h2>{{ a.labelFr | lib: a.labelEn }}</h2></div><span class="chip">{{ a.product }}</span></div>
          <p class="small">{{ a.description }}</p>
          <table class="politique small">@for (p of actions; track p) { <tr><td>{{ p }}</td><td><span class="chip" [class.danger]="a.policy[p] === 'human_only'" [class.ok]="a.policy[p]?.startsWith('L')">{{ a.policy[p] ?? '—' }}</span></td></tr> }</table>
          @if (auth.a(d.approuver) || auth.a(d.publier)) {
            <label class="check"><input type="checkbox" [checked]="a.enabled" (change)="activer(a, $any($event.target).checked)" /> {{ (a.enabled ? 'Activé' : 'Désactivé (coupe-circuit)') | t }}</label>
          }
        </div>
      }
    </div>
    <div class="card pad stack">
      <h2>{{ 'Dernières exécutions' | t }}</h2>
      <div class="scroll-x"><table class="datagrid"><thead><tr><th>#</th><th>{{ 'Agent' | t }}</th><th>{{ 'Version' | t }}</th><th>{{ 'Politique' | t }}</th><th>{{ 'Résultat' | t }}</th><th>{{ 'Par' | t }}</th><th>{{ 'Date' | t }}</th></tr></thead>
        <tbody>@for (r of executions(); track r.id) {
          <tr><td class="mono small">{{ r.id }}</td><td>{{ r.agentCode }}</td><td>{{ r.versionId }}</td>
            <td><span class="chip" [class.ok]="r.policyDecision === 'allow'" [class.danger]="r.policyDecision === 'deny'">{{ r.policyDecision }}</span></td>
            <td class="small">{{ r.status }} — {{ r.summary }}</td><td>{{ r.startedBy }}</td><td class="small">{{ r.startedAt | date: 'dd/MM HH:mm' }}</td></tr>
        }</tbody></table></div>
    </div>
  `,
  styles: [`:host { display: flex; flex-direction: column; gap: 16px; } .agent.off { opacity: .6; } .politique td { padding: 3px 6px 3px 0; } .datagrid tbody tr { cursor: default; }`],
})
export class AgentsPage {
  private readonly api = inject(Api);
  protected readonly auth = inject(AuthService);
  protected readonly d = DROITS;
  protected readonly actions = ['proposer', 'appliquer', 'soumettre', 'approuver', 'publier'];
  protected readonly agents = signal<AgentInfo[]>([]);
  protected readonly modele = signal('');
  protected readonly executions = signal<Execution[]>([]);
  protected readonly erreur = signal<string | null>(null);

  constructor() {
    this.api.agents().subscribe({ next: a => { this.agents.set(a.agents); this.modele.set(a.model.model); }, error: e => this.erreur.set(erreurs(e).join(' ')) });
    this.api.toutesExecutions().subscribe(r => this.executions.set(r.slice(0, 100)));
  }

  protected activer(a: AgentInfo, actif: boolean): void {
    this.api.activerAgent(a.code, actif).subscribe({ next: l => this.agents.set(l), error: e => this.erreur.set(erreurs(e).join(' ')) });
  }
}
