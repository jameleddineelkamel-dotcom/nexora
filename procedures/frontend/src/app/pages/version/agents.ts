import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { AgentInfo, Api, Execution, Proposition, VersionComplete } from '../../core/api';
import { I18N, I18n } from '../../core/i18n';
import { erreurs } from '../../core/session';
import { Icon } from '../../shared/icon';

/**
 * Agents IA de modélisation : l'orchestrateur enchaîne Document Agent (SFD), Data Agent (SCRDM, listes de codes),
 * Rules Agent (DRL), Single Window Agent (BPMN) et Compliance Agent. Chaque proposition est sourcée et revue par un
 * humain : accepter, corriger (JSON) ou rejeter avec motif ; rien n'est appliqué sans revue.
 */
@Component({
  selector: 'nx-onglet-agents',
  imports: [DatePipe, Icon, ...I18N],
  template: `
    <div class="card pad stack">
      <div class="card-title"><nx-icon name="robot" /><h2>{{ 'Lancer les agents' | t }}</h2>
        <span class="chip ia">{{ modele() }}</span></div>
      @if (!modifiable()) { <div class="alert info small">{{ 'Version non modifiable : seul le Compliance Agent peut s\\'exécuter (contrôle).' | t }}</div> }
      <div class="form-grid">
        <div class="field"><label for="ag">{{ 'Agent' | t }}</label><select id="ag" class="input" (change)="agent.set($any($event.target).value)">
          <option value="ORCHESTRATEUR">{{ 'AI Orchestrator — séquence complète' | t }}</option>
          @for (a of agents(); track a.code) { @if (a.code !== 'ORCHESTRATEUR') { <option [value]="a.code" [disabled]="!a.enabled">{{ a.labelFr }}@if (!a.enabled) { ({{ 'désactivé' | t }}) }</option> } }
        </select></div>
        <div class="field"><label for="sfd">{{ 'SFD et annexes (Word, PDF, draw.io, BPMN, Excel, HTML, Figma JSON, images)' | t }}</label><input id="sfd" type="file" class="input" multiple accept=".docx,.pdf,.txt,.md,.drawio,.bpmn,.xml,.xlsx,.html,.htm,.json,.png,.jpg,.jpeg" (change)="fichiers.set($any([...($any($event.target).files ?? [])]))" />
          @if (fichiers().length) { <small class="muted">{{ fichiers().length }} {{ 'fichier(s)' | t }} : {{ noms() }}</small> }</div>
        <div class="field full"><label for="ob">{{ 'Objectif' | t }}</label><input id="ob" class="input" [value]="objectif()" (input)="objectif.set($any($event.target).value)" [placeholder]="'Ex. Modéliser la procédure à partir de la SFD jointe' | t" /></div>
      </div>
      <div class="row"><button class="btn primary" (click)="lancer()" [disabled]="occupe()"><nx-icon name="sparkles" /> {{ (occupe() ? 'Exécution…' : 'Exécuter') | t }}</button>
        <span class="muted small">{{ 'Plafond d\\'autonomie : les agents proposent (L2) ; soumettre, approuver et publier restent humains.' | t }}</span></div>
      @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
    </div>

    <div class="card pad stack">
      <div class="between"><div class="card-title"><nx-icon name="inbox" /><h2>{{ 'Propositions à revoir ({0})' | t: enAttente().length }}</h2></div>
        @if (modifiable() && mappings() > 0) { <button class="btn sm" (click)="accepterMappings()"><nx-icon name="check" [size]="15" /> {{ 'Accepter les rapprochements SCRDM (confiance ≥ 0,8)' | t }}</button> }</div>
      <div class="row filtres">@for (k of types(); track k) { <button class="btn sm" [class.primary]="filtre() === k" (click)="filtre.set(filtre() === k ? '' : k)">{{ k }}</button> }</div>
      @for (p of visibles(); track p.id) {
        <div class="prop" [class.faite]="p.reviewStatus !== 'pending'">
          <div class="between"><div><span class="chip ia">{{ p.kind }}</span> <b>{{ p.title }}</b></div>
            <span class="small muted">{{ p.agentCode }} · {{ 'confiance' | t }} {{ (p.confidence ?? 0) * 100 | num }} % · {{ p.proposalType }}</span></div>
          @if (p.rationale) { <div class="small">{{ p.rationale }}</div> }
          @if (p.sourceExcerpt) { <blockquote class="small">{{ p.sourceExcerpt }}</blockquote> }
          <details class="small"><summary>{{ 'Contenu proposé' | t }}</summary>
            @if (p.kind === 'REPORT') {
              <ul>@for (c of $any(p.payload['findings']); track $index) { <li [class]="c.severity"><b>{{ c.rule }}</b> {{ c.message }}</li> }</ul>
            } @else { <pre class="code">{{ apercu(p) }}</pre> }
          </details>
          @if (p.reviewStatus === 'pending') {
            @if (correction() === p.id) {
              <textarea class="input code" style="min-height: 150px" [value]="texteCorrection()" (input)="texteCorrection.set($any($event.target).value)"></textarea>
            }
            <div class="row">
              <input class="input grow" [value]="commentaires()[p.id] ?? ''" (input)="commenter(p.id, $any($event.target).value)" [placeholder]="'Commentaire (obligatoire pour un rejet)' | t" />
              @if (correction() === p.id) { <button class="btn primary sm" (click)="revoir(p, 'correct')" [disabled]="!modifiable()"><nx-icon name="save" [size]="15" /> {{ 'Appliquer la correction' | t }}</button> }
              @else {
                <button class="btn primary sm" (click)="revoir(p, 'accept')" [disabled]="!modifiable() && !informatif(p)"><nx-icon name="check" [size]="15" /> {{ 'Accepter' | t }}</button>
                @if (!informatif(p)) { <button class="btn sm" (click)="corriger(p)" [disabled]="!modifiable()"><nx-icon name="edit" [size]="15" /> {{ 'Corriger' | t }}</button> }
              }
              <button class="btn sm danger" (click)="revoir(p, 'reject')"><nx-icon name="ban" [size]="15" /> {{ 'Rejeter' | t }}</button>
            </div>
          } @else {
            <div class="small muted">{{ statut(p.reviewStatus) | t }} {{ 'par' | t }} {{ p.reviewedBy }} · {{ p.reviewedAt | date: 'dd/MM HH:mm' }} @if (p.reviewComment) { — {{ p.reviewComment }} }</div>
          }
        </div>
      } @empty { <div class="muted small">{{ 'Aucune proposition.' | t }}</div> }
      <label class="check small"><input type="checkbox" [checked]="toutes()" (change)="toutes.set($any($event.target).checked); charger()" /> {{ 'Afficher aussi les propositions revues' | t }}</label>
    </div>

    <div class="card pad stack">
      <div class="card-title"><nx-icon name="history" /><h2>{{ 'Exécutions (ai_run)' | t }}</h2></div>
      <div class="scroll-x"><table class="datagrid">
        <thead><tr><th>#</th><th>{{ 'Agent' | t }}</th><th>{{ 'Action' | t }}</th><th>{{ 'Politique' | t }}</th><th>{{ 'Modèle' | t }}</th><th>{{ 'Résultat' | t }}</th><th>{{ 'Date' | t }}</th></tr></thead>
        <tbody>@for (r of executions(); track r.id) {
          <tr><td class="mono small">{{ r.id }}@if (r.parentRunId) { ↳{{ r.parentRunId }} }</td><td>{{ r.agentCode }}</td><td class="small">{{ r.actionCode }} · {{ r.autonomyLevel }}</td>
            <td><span class="chip" [class.ok]="r.policyDecision === 'allow'" [class.danger]="r.policyDecision === 'deny'">{{ r.policyDecision }}</span></td>
            <td class="small">{{ r.model }}</td><td class="small"><span class="chip" [class.danger]="r.status !== 'DONE'">{{ r.status }}</span> {{ r.summary }}</td>
            <td class="small">{{ r.startedAt | date: 'dd/MM HH:mm:ss' }}</td></tr>
        }</tbody></table></div>
    </div>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 16px; }
    .prop { border: 1px solid var(--line); border-left: 4px solid #7b4fe0; border-radius: 12px; padding: 12px; display: flex; flex-direction: column; gap: 8px; }
    .prop.faite { opacity: .65; border-left-color: var(--line); }
    blockquote { margin: 0; padding: 6px 10px; border-left: 3px solid var(--line); color: var(--muted); }
    li.error { color: var(--danger); } li.warning { color: var(--warn); }
    .datagrid tbody tr { cursor: default; }
  `],
})
export class OngletAgents {
  private readonly api = inject(Api);
  private readonly i18n = inject(I18n);
  readonly v = input.required<VersionComplete>();
  readonly modifiable = input(false);
  readonly actualiser = output<void>();
  protected readonly agents = signal<AgentInfo[]>([]);
  protected readonly modele = signal('heuristique');
  protected readonly agent = signal('ORCHESTRATEUR');
  protected readonly objectif = signal('');
  protected readonly fichiers = signal<File[]>([]);
  protected readonly noms = computed(() => this.fichiers().map(f => f.name).join(', '));
  protected readonly propositions = signal<Proposition[]>([]);
  protected readonly executions = signal<Execution[]>([]);
  protected readonly filtre = signal('');
  protected readonly toutes = signal(false);
  protected readonly correction = signal<number | null>(null);
  protected readonly texteCorrection = signal('');
  protected readonly commentaires = signal<Record<number, string>>({});
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly enAttente = computed(() => this.propositions().filter(p => p.reviewStatus === 'pending'));
  protected readonly types = computed(() => [...new Set(this.propositions().map(p => p.kind))].sort());
  protected readonly visibles = computed(() => this.propositions().filter(p => !this.filtre() || p.kind === this.filtre()));
  protected readonly mappings = computed(() => this.enAttente().filter(p => p.kind === 'FIELD_MAPPING').length);

  constructor() {
    this.api.agents().subscribe(a => { this.agents.set(a.agents); this.modele.set(a.model.model); });
    effect(() => { this.v(); this.charger(); });
  }

  protected charger(): void {
    const id = this.v().version.id;
    this.api.propositions(id, this.toutes() ? '' : 'pending').subscribe(p => this.propositions.set(p));
    this.api.executions(id).subscribe(r => this.executions.set(r));
  }

  protected lancer(): void {
    this.occupe.set(true);
    this.erreur.set(null);
    this.api.executer(this.v().version.id, this.agent(), this.objectif(), this.fichiers()).subscribe({
      next: () => { this.occupe.set(false); this.charger(); },
      error: e => { this.occupe.set(false); this.erreur.set(erreurs(e).join(' ')); },
    });
  }

  protected informatif(p: Proposition): boolean {
    return p.kind === 'FINDING' || p.kind === 'REPORT';
  }

  protected apercu(p: Proposition): string {
    const x = { ...p.payload };
    if (typeof x['bpmnXml'] === 'string') x['bpmnXml'] = (x['bpmnXml'] as string).slice(0, 1500) + '…';
    return p.kind === 'DRL' ? String(x['drl']) : JSON.stringify(x, null, 2);
  }

  protected commenter(id: number, c: string): void {
    this.commentaires.update(m => ({ ...m, [id]: c }));
  }

  protected corriger(p: Proposition): void {
    this.correction.set(p.id);
    this.texteCorrection.set(JSON.stringify(p.payload, null, 2));
  }

  protected statut(s: string): string {
    return ({ accepted: 'Acceptée', corrected: 'Corrigée', rejected: 'Rejetée', superseded: 'Remplacée' } as Record<string, string>)[s] ?? s;
  }

  protected revoir(p: Proposition, d: 'accept' | 'correct' | 'reject'): void {
    let payload: Record<string, unknown> | undefined;
    if (d === 'correct') {
      try { payload = JSON.parse(this.texteCorrection()); } catch { this.erreur.set(this.i18n.t('JSON de correction invalide.')); return; }
    }
    this.api.revoir(p.id, d, this.commentaires()[p.id] ?? '', payload).subscribe({
      next: () => { this.correction.set(null); this.charger(); this.actualiser.emit(); },
      error: e => this.erreur.set(erreurs(e).join(' ')),
    });
  }

  protected accepterMappings(): void {
    this.api.accepterTout(this.v().version.id, 'FIELD_MAPPING', 0.8).subscribe({ next: () => { this.charger(); this.actualiser.emit(); }, error: e => this.erreur.set(erreurs(e).join(' ')) });
  }
}
