import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Api, Ligne, Procedure, Tache } from '../core/api';
import { AuthService, DROITS } from '../core/auth.service';
import { I18N } from '../core/i18n';
import { erreurs } from '../core/session';
import { Icon } from '../shared/icon';
import { LIBELLES, NATURES, Statut } from '../shared/widgets';

/** Accueil des e-Services : selon les droits, demandes de l'opérateur, tâches de l'agent, accès à la supervision. */
@Component({
  selector: 'nx-accueil',
  imports: [RouterLink, DatePipe, Icon, Statut, ...I18N],
  template: `
    <section class="hero">
      <div>
        <span class="eyebrow"><nx-icon name="sparkles" [size]="14" /> {{ 'NEXORA · Guichet unique du commerce extérieur' | t }}</span>
        <h1>{{ 'Bonjour {0}' | t: (auth.utilisateur()?.name ?? '') }}</h1>
        <p>{{ 'Déposez et suivez vos formalités (eFORCE), traitez les dossiers qui vous sont cotés (eGOV), réglez les frais en ligne (e-PAYMENT). Chaque procédure est exécutée selon le workflow BPMN et les règles publiés par le studio.' | t }}</p>
        <div class="row">
          @if (auth.a(d.deposer)) { <a routerLink="/nouvelle-demande" class="btn primary"><nx-icon name="plus" /> {{ 'Nouvelle demande' | t }}</a> }
          @if (taches().length) { <a routerLink="/taches" class="btn"><nx-icon name="inbox" /> {{ '{0} tâche(s) en attente' | t: taches().length }}</a> }
          @if (auth.a(d.superviser) || auth.a(d.administrer)) { <a routerLink="/supervision" class="btn"><nx-icon name="gauge" /> {{ 'Supervision' | t }}</a> }
        </div>
      </div>
      <div class="flux" aria-hidden="true">
        <div class="etape"><b>eFORCE</b><span>{{ 'dépôt' | t }}</span></div><nx-icon name="chevron" />
        <div class="etape"><b>e-PAY</b><span>{{ 'paiement' | t }}</span></div><nx-icon name="chevron" />
        <div class="etape"><b>eGOV</b><span>{{ 'traitement' | t }}</span></div>
      </div>
    </section>
    @if (sansDroit()) {
      <div class="alert info"><nx-icon name="info" /> <div>{{ 'Votre profil ({0}) n'a aucun droit sur les e-Services : il sert à une autre application de la plateforme (ex. le studio des procédures pour approuver et publier).' | t: (auth.utilisateur()?.roles?.join(', ') ?? '') }}
        <a [href]="studio()">{{ 'Ouvrir le studio des procédures' | t }}</a></div></div>
    }

    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }

    @if (auth.a(d.deposer)) {
      <div class="grid k4">
        <div class="card pad kpi"><span class="ic" style="--c: var(--blue)"><nx-icon name="file" /></span><div><b>{{ mesDossiers().length }}</b><span>{{ 'demandes' | t }}</span><small>{{ '{0} brouillon(s)' | t: compte('BROUILLON') }}</small></div></div>
        <div class="card pad kpi"><span class="ic" style="--c: var(--warn)"><nx-icon name="wallet" /></span><div><b>{{ compte('EN_ATTENTE_PAIEMENT') }}</b><span>{{ 'à payer' | t }}</span><small>{{ 'e-PAYMENT' | t }}</small></div></div>
        <div class="card pad kpi"><span class="ic" style="--c: var(--cyan)"><nx-icon name="clock" /></span><div><b>{{ enCours() }}</b><span>{{ 'en traitement' | t }}</span><small>{{ 'eGOV' | t }}</small></div></div>
        <div class="card pad kpi"><span class="ic" style="--c: var(--green)"><nx-icon name="shield" /></span><div><b>{{ compte('VALIDE') }}</b><span>{{ 'livrables délivrés' | t }}</span><small>{{ 'attestations signées' | t }}</small></div></div>
      </div>
    }

    <div class="grid k2">
      @if (auth.a(d.deposer)) {
        <div class="card pad">
          <div class="between card-title"><h2>{{ 'Procédures disponibles' | t }}</h2><a routerLink="/nouvelle-demande" class="small">{{ 'Déposer' | t }}</a></div>
          @for (p of catalogue(); track p.code) {
            <a class="proc" [routerLink]="['/nouvelle-demande']" [queryParams]="{ procedure: p.code }">
              <span class="ic"><nx-icon name="stamp" /></span>
              <span class="grow"><b>{{ p.labelFr | lib: p.labelEn }}</b>
                <span class="muted small">{{ p.authority }} · {{ p.terminology.circuit.labelFr }} · v{{ p.versionNo }}</span></span>
              <nx-icon name="chevron" />
            </a>
          } @empty { <div class="muted small">{{ 'Aucune procédure déployée pour le moment.' | t }}</div> }
        </div>
        <div class="card pad">
          <div class="between card-title"><h2>{{ 'Mes dernières demandes' | t }}</h2><a routerLink="/demandes" class="small">{{ 'Tout voir' | t }}</a></div>
          @for (x of mesDossiers().slice(0, 6); track x.id) {
            <a class="ligne" [routerLink]="['/demandes', x.id]">
              <span class="grow"><b class="mono">{{ x.dossierNo ?? ('Brouillon' | t) }}</b>
                <span class="muted small"> · {{ x.procedureCode }} {{ x.requestType }} · {{ natures[x.requestNature] | t }}</span></span>
              <nx-statut [code]="x.status" />
            </a>
          } @empty { <div class="muted small">{{ 'Aucune demande.' | t }}</div> }
        </div>
      }
      @if (taches().length || auth.a(d.traiter) || auth.a(d.signer)) {
        <div class="card pad">
          <div class="between card-title"><h2>{{ 'Mes tâches' | t }}</h2><a routerLink="/taches" class="small">{{ 'Ouvrir la corbeille' | t }}</a></div>
          @for (t of taches().slice(0, 8); track t.id) {
            <a class="ligne" [routerLink]="['/demandes', t.dossierId]">
              <span class="grow"><b>{{ t.name }}</b><span class="muted small"> · <span class="mono">{{ t.dossierNo }}</span> · {{ t.applicantName }}</span></span>
              <span class="muted small">{{ t.created | date: 'dd/MM HH:mm' }}</span>
            </a>
          } @empty { <div class="muted small">{{ 'Aucune tâche en attente.' | t }}</div> }
        </div>
      }
    </div>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 18px; }
    .flux { display: flex; align-items: center; justify-content: center; gap: 6px; flex-wrap: wrap; }
    .etape { display: flex; flex-direction: column; align-items: center; justify-content: center; width: 96px; height: 96px; border-radius: 24px;
      background: rgba(255,255,255,.13); border: 1px solid rgba(255,255,255,.25); }
    .etape b { font-size: 17px; } .etape span { font-size: 12px; opacity: .85; }
    .proc, .ligne { display: flex; gap: 12px; align-items: center; padding: 10px; border-radius: 12px; color: var(--text); }
    .proc:hover, .ligne:hover { background: var(--surface-2); text-decoration: none; }
    .proc .ic { width: 38px; height: 38px; border-radius: 11px; display: grid; place-items: center; background: var(--gradient); color: #fff; flex-shrink: 0; }
    .proc b { display: block; }
  `],
})
export class AccueilPage {
  private readonly api = inject(Api);
  protected readonly auth = inject(AuthService);
  protected readonly d = DROITS;
  protected readonly natures = NATURES;
  protected readonly libelles = LIBELLES;
  protected readonly catalogue = signal<Procedure[]>([]);
  protected readonly mesDossiers = signal<Ligne[]>([]);
  protected readonly taches = signal<Tache[]>([]);
  protected readonly erreur = signal<string | null>(null);
  protected readonly studio = computed(() => this.auth.config().proceduresUrl ?? 'http://localhost:8084');
  /** Aucun droit e-Services (ex. approbateur des procédures) : la page l'explique au lieu d'échouer. */
  protected readonly sansDroit = computed(() => !Object.values(DROITS).some(d => d.startsWith('eservices.') && this.auth.a(d)));
  protected readonly enCours = computed(() => this.mesDossiers().filter(x => x.lifecycle === 'ACTIVE' && x.status !== 'EN_ATTENTE_PAIEMENT').length);

  constructor() {
    const e = (x: unknown) => this.erreur.set(erreurs(x, 'Le service eservices').join(' '));
    if (this.auth.a(DROITS.deposer)) {
      this.api.catalogue().subscribe({ next: c => this.catalogue.set(c), error: e });
      this.api.dossiers({ scope: 'mine', limit: 200 }).subscribe({ next: l => this.mesDossiers.set(l), error: e });
    }
    if (this.auth.a(DROITS.deposer) || this.auth.a(DROITS.traiter) || this.auth.a(DROITS.signer))
      this.api.taches().subscribe({ next: t => this.taches.set(t), error: e });
  }

  protected compte(statut: string): number {
    return this.mesDossiers().filter(x => x.status === statut).length;
  }
}
