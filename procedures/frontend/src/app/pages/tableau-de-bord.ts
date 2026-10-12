import { Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Api, Dashboard, ProcedureResume } from '../core/api';
import { AuthService, DROITS } from '../core/auth.service';
import { I18N } from '../core/i18n';
import { erreurs } from '../core/session';
import { Cycle } from '../shared/cycle';
import { Icon } from '../shared/icon';

/** Tableau de bord du studio : procédures, versions par statut, propositions IA en attente, rapprochement SCRDM. */
@Component({
  selector: 'nx-tableau-de-bord',
  imports: [RouterLink, DatePipe, Icon, Cycle, ...I18N],
  template: `
    <section class="hero">
      <div>
        <span class="eyebrow"><nx-icon name="sparkles" [size]="14" /> {{ 'NEXORA · Single Window AI Studio' | t }}</span>
        <h1>{{ 'Modéliser les procédures du guichet unique' | t }}</h1>
        <p>{{ 'Formulaire relié aux modèles de référence (UN/CCL, SCRDM, OMD), catalogue de messages et flux, workflow BPMN exécutable, règles Drools : les agents proposent, les experts valident, la version publiée est déployée telle quelle dans les e-Services.' | t }}</p>
        <div class="row">
          <a routerLink="/procedures" class="btn primary"><nx-icon name="workflow" /> {{ 'Procédures' | t }}</a>
          @if (auth.a(d.modeliser)) { <a routerLink="/procedures" [queryParams]="{ nouvelle: 1 }" class="btn"><nx-icon name="plus" /> {{ 'Nouvelle procédure' | t }}</a> }
        </div>
      </div>
      <div class="cycle" aria-hidden="true">
        @for (e of etapes; track e; let last = $last) { <span class="etape">{{ e | t }}</span> @if (!last) { <nx-icon name="chevron" [size]="16" /> } }
      </div>
    </section>
    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
    @if (tb(); as x) {
      <div class="grid k4">
        <div class="card pad kpi"><span class="ic" style="--c: var(--blue)"><nx-icon name="workflow" /></span><div><b>{{ x.procedures }}</b><span>{{ 'procédures' | t }}</span><small>{{ '{0} publiée(s)' | t: (x.versionsByStatus['published'] ?? 0) }}</small></div></div>
        <div class="card pad kpi"><span class="ic" style="--c: var(--warn)"><nx-icon name="edit" /></span><div><b>{{ (x.versionsByStatus['draft'] ?? 0) + (x.versionsByStatus['in_review'] ?? 0) }}</b><span>{{ 'versions de travail' | t }}</span><small>{{ '{0} en revue' | t: (x.versionsByStatus['in_review'] ?? 0) }}</small></div></div>
        <div class="card pad kpi"><span class="ic" style="--c: #7b4fe0"><nx-icon name="robot" /></span><div><b>{{ x.pendingProposals }}</b><span>{{ 'propositions IA à revoir' | t }}</span><small>{{ '{0} exécutions' | t: x.aiRuns }}</small></div></div>
        <div class="card pad kpi"><span class="ic" style="--c: var(--green)"><nx-icon name="link" /></span><div><b>{{ x.fields.total ? (pourcent(x.fields.withBie, x.fields.total) | num) : 0 }} %</b><span>{{ 'champs reliés au SCRDM' | t }}</span><small>{{ '{0} / {1} champs' | t: x.fields.withBie : x.fields.total }}</small></div></div>
      </div>
    }
    <div class="grid k2">
      <div class="card pad">
        <div class="between card-title"><h2>{{ 'Procédures' | t }}</h2><a routerLink="/procedures" class="small">{{ 'Tout voir' | t }}</a></div>
        @for (p of procedures(); track p.code) {
          <a class="ligne" [routerLink]="['/procedures', p.code]">
            <span class="code-tag">{{ p.code }}</span><span class="grow"><b>{{ p.labelFr | lib: p.labelEn }}</b><span class="muted small"> · {{ p.circuitFr }}</span></span>
            @if (p.publishedVersionNo) { <span class="chip ok">v{{ p.publishedVersionNo }}</span> }
            @if (p.workingStatus) { <nx-cycle [statut]="p.workingStatus" /> }
          </a>
        } @empty { <div class="muted small">{{ 'Aucune procédure.' | t }}</div> }
      </div>
      <div class="card pad">
        <div class="between card-title"><h2>{{ 'Activité récente' | t }}</h2><a routerLink="/journal" class="small">{{ 'Journal' | t }}</a></div>
        @for (e of tb()?.recent ?? []; track e.id) {
          <div class="ligne"><span class="code-tag">{{ e.actionCode }}</span><span class="grow small">{{ e.procedureCode }} <span class="muted">· {{ e.actor }}</span></span>
            <span class="muted small">{{ e.occurredAt | date: 'dd/MM HH:mm' }}</span></div>
        }
      </div>
    </div>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 18px; }
    .cycle { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; justify-content: center; }
    .etape { padding: 8px 12px; border-radius: 12px; background: rgba(255,255,255,.14); border: 1px solid rgba(255,255,255,.25); font-size: 13px; font-weight: 600; }
    .ligne { display: flex; gap: 10px; align-items: center; padding: 9px; border-radius: 10px; color: var(--text); }
    a.ligne:hover { background: var(--surface-2); text-decoration: none; }
  `],
})
export class TableauDeBordPage {
  private readonly api = inject(Api);
  protected readonly auth = inject(AuthService);
  protected readonly d = DROITS;
  protected readonly etapes = ['Brouillon', 'Agents IA', 'Revue', 'Approbation', 'Publication', 'Déploiement'];
  protected readonly tb = signal<Dashboard | null>(null);
  protected readonly procedures = signal<ProcedureResume[]>([]);
  protected readonly erreur = signal<string | null>(null);

  constructor() {
    this.api.dashboard().subscribe({ next: d => this.tb.set(d), error: e => this.erreur.set(erreurs(e, 'Le studio').join(' ')) });
    this.api.procedures().subscribe({ next: p => this.procedures.set(p) });
  }

  protected pourcent(n: number, total: number): number {
    return Math.round(n * 100 / total);
  }
}
