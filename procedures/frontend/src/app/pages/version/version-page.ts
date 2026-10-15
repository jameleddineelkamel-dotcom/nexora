import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Api, Partie, Version, VersionComplete } from '../../core/api';
import { AuthService, DROITS } from '../../core/auth.service';
import { I18N } from '../../core/i18n';
import { erreurs } from '../../core/session';
import { Cycle } from '../../shared/cycle';
import { Icon } from '../../shared/icon';
import { OngletGeneral } from './general';
import { OngletFormulaire } from './formulaire';
import { OngletPiecesFrais } from './pieces-frais';
import { OngletMessages } from './messages';
import { OngletWorkflow } from './workflow';
import { OngletRegles } from './regles';
import { OngletAgents } from './agents';
import { OngletPublication } from './publication';

type Onglet = 'general' | 'formulaire' | 'messages' | 'workflow' | 'regles' | 'frais' | 'pieces' | 'agents' | 'publication';

/**
 * Atelier d'une version de procédure : chaque onglet édite une partie (enregistrée avec un motif), les agents IA
 * proposent, l'onglet Publication contrôle et fait progresser le cycle de vie. Seule une version brouillon est modifiable.
 */
@Component({
  selector: 'nx-version',
  imports: [RouterLink, Icon, Cycle, OngletGeneral, OngletFormulaire, OngletPiecesFrais, OngletMessages, OngletWorkflow, OngletRegles, OngletAgents,
    OngletPublication, ...I18N],
  template: `
    @if (erreur()) { <div class="alert err"><nx-icon name="alert" /> <div>@for (e of erreurListe(); track $index) { <div>{{ e }}</div> }</div></div> }
    @if (info()) { <div class="alert ok"><nx-icon name="check" /> {{ info()! | t }}</div> }
    @if (v(); as x) {
      <div class="page-head">
        <div>
          <a [routerLink]="['/procedures', x.procedure.code]" class="small"><nx-icon name="back" [size]="14" /> {{ x.procedure.code }}</a>
          <h1>{{ x.procedure.labelFr | lib: x.procedure.labelEn }} <span class="muted">v{{ x.version.versionNo }}</span></h1>
          <div class="row small"><nx-cycle [statut]="x.version.lifecycleStatus" />
            <span class="muted">{{ 'Créée par {0}' | t: x.version.createdBy }}@if (x.version.updatedBy) { · {{ 'modifiée par {0}' | t: x.version.updatedBy }} }</span>
            @if (!modifiable()) { <span class="chip">{{ 'Lecture seule' | t }}</span> }</div>
        </div>
      </div>
      @if (!modifiable() && peutModeliser()) {
        <div class="alert lecture-seule">
          <nx-icon name="info" />
          <div class="grow">
            @if (travail(); as w) {
              {{ 'Cette version est en lecture seule. Les sections, champs, règles et le gabarit se modifient dans la version de travail v{0} ({1}).' | t: w.versionNo : (statut(w.lifecycleStatus) | t) }}
            } @else {
              {{ 'Cette version est en lecture seule : une version publiée est immuable. Pour ajouter, modifier, supprimer ou réordonner sections, sous-sections et champs, créez une nouvelle version (copie de la dernière version) ; elle sera à faire approuver puis publier.' | t }}
            }
          </div>
          @if (travail(); as w) {
            <a class="btn primary sm" [routerLink]="['/versions', w.id]">{{ 'Ouvrir la version de travail' | t }}</a>
          } @else {
            <button class="btn primary sm" (click)="nouvelle(x.procedure.code)" [disabled]="occupe()"><nx-icon name="plus" [size]="15" /> {{ 'Créer une nouvelle version' | t }}</button>
          }
        </div>
      }
      <div class="tabs">
        @for (o of onglets; track o.code) {
          <button class="tab" [class.on]="onglet() === o.code" (click)="onglet.set(o.code)"><nx-icon [name]="o.icone" [size]="16" /> {{ o.libelle | t }}</button>
        }
      </div>
      @switch (onglet()) {
        @case ('general') { <nx-onglet-general [v]="x" [modifiable]="modifiable()" (enregistrer)="enregistrer($event.partie, $event.corps, $event.motif)" /> }
        @case ('formulaire') { <nx-onglet-formulaire [v]="x" [modifiable]="modifiable()" (enregistrer)="enregistrer($event.partie, $event.corps, $event.motif)" (regles)="onglet.set('regles')" /> }
        @case ('frais') { <nx-onglet-pieces-frais vue="frais" [v]="x" [modifiable]="modifiable()" (enregistrer)="enregistrer($event.partie, $event.corps, $event.motif)" /> }
        @case ('pieces') { <nx-onglet-pieces-frais vue="pieces" [v]="x" [modifiable]="modifiable()" (enregistrer)="enregistrer($event.partie, $event.corps, $event.motif)" /> }
        @case ('messages') { <nx-onglet-messages [v]="x" [modifiable]="modifiable()" (enregistrer)="enregistrer($event.partie, $event.corps, $event.motif)" /> }
        @case ('workflow') { <nx-onglet-workflow [v]="x" [modifiable]="modifiable()" (enregistrer)="enregistrer($event.partie, $event.corps, $event.motif)" /> }
        @case ('regles') { <nx-onglet-regles [v]="x" [modifiable]="modifiable()" (enregistrer)="enregistrer($event.partie, $event.corps, $event.motif)" /> }
        @case ('agents') { <nx-onglet-agents [v]="x" [modifiable]="modifiable()" (actualiser)="charger()" /> }
        @case ('publication') { <nx-onglet-publication [v]="x" (actualiser)="charger()" /> }
      }
    } @else if (!erreur()) { <div class="skeleton" style="height: 260px"></div> }
  `,
  styles: [`:host { display: flex; flex-direction: column; gap: 14px; } h1 { margin: 4px 0; } .lecture-seule { align-items: center; }`],
})
export class VersionPage {
  private readonly api = inject(Api);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  readonly id = input.required<string>();
  protected readonly v = signal<VersionComplete | null>(null);
  protected readonly onglet = signal<Onglet>('general');
  protected readonly erreur = signal<string | null>(null);
  protected readonly info = signal<string | null>(null);
  protected readonly erreurListe = computed(() => (this.erreur() ?? '').split('\n').filter(Boolean));
  protected readonly modifiable = computed(() => this.v()?.version.lifecycleStatus === 'draft' && this.auth.a(DROITS.modeliser));
  protected readonly peutModeliser = computed(() => this.auth.a(DROITS.modeliser));
  /** Autres versions de la procédure : la version de travail (brouillon, en revue, approuvée) s'il y en a une. */
  private readonly versions = signal<Version[]>([]);
  protected readonly travail = computed(() => this.versions()
    .find(x => x.id !== this.v()?.version.id && ['draft', 'in_review', 'approved'].includes(x.lifecycleStatus)) ?? null);
  protected readonly occupe = signal(false);
  protected readonly onglets: { code: Onglet; libelle: string; icone: string }[] = [
    { code: 'general', libelle: 'Général', icone: 'info' }, { code: 'formulaire', libelle: 'Formulaire et données', icone: 'file' },
    { code: 'messages', libelle: 'Messages et flux', icone: 'mail' }, { code: 'workflow', libelle: 'Workflow BPMN', icone: 'workflow' },
    { code: 'regles', libelle: 'Règles DRL', icone: 'scale' }, { code: 'frais', libelle: 'Frais', icone: 'wallet' },
    { code: 'pieces', libelle: 'Pièces jointes', icone: 'archive' },
    { code: 'agents', libelle: 'Agents IA', icone: 'robot' }, { code: 'publication', libelle: 'Contrôles et publication', icone: 'shield' }];

  constructor() {
    effect(() => { this.id(); this.charger(); });
  }

  protected charger(): void {
    this.api.version(Number(this.id())).subscribe({
      next: v => {
        this.v.set(v);
        this.erreur.set(null);
        if (v.version.lifecycleStatus !== 'draft' && this.peutModeliser())
          this.api.procedure(v.procedure.code).subscribe({ next: p => this.versions.set(p.versions), error: () => this.versions.set([]) });
      },
      error: e => this.erreur.set(erreurs(e, 'Le studio').join('\n')),
    });
  }

  protected statut(s: string): string {
    return ({ draft: 'brouillon', in_review: 'en revue', approved: 'approuvée' } as Record<string, string>)[s] ?? s;
  }

  /** Nouvelle version de travail (copie de la dernière version de la procédure), puis ouverture. */
  protected nouvelle(code: string): void {
    this.occupe.set(true);
    this.api.nouvelleVersion(code, '').subscribe({
      next: v => { this.occupe.set(false); this.router.navigate(['/versions', v.version.id]); },
      error: e => { this.occupe.set(false); this.erreur.set(erreurs(e).join('\n')); },
    });
  }

  protected enregistrer(partie: Partie, corps: unknown, motif: string): void {
    this.erreur.set(null);
    this.api.partie(Number(this.id()), partie, corps, motif).subscribe({
      next: v => { this.v.set(v); this.info.set('Modifications enregistrées.'); setTimeout(() => this.info.set(null), 2500); },
      error: e => { this.erreur.set(erreurs(e).join('\n')); window.scrollTo({ top: 0, behavior: 'smooth' }); },
    });
  }
}
