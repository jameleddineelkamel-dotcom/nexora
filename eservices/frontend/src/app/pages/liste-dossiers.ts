import { Component, computed, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Api, Ligne } from '../core/api';
import { AuthService, DROITS } from '../core/auth.service';
import { I18N } from '../core/i18n';
import { erreurs } from '../core/session';
import { Icon } from '../shared/icon';
import { LIBELLES, NATURES, Statut } from '../shared/widgets';

/** Listes de dossiers : mes demandes (eFORCE), dossiers cotés (eGOV) ou tous les dossiers (supervision), avec recherche. */
@Component({
  selector: 'nx-liste-dossiers',
  imports: [RouterLink, DatePipe, Icon, Statut, ...I18N],
  template: `
    <div class="page-head">
      <div><h1>{{ titre() | t }}</h1><p class="muted">{{ sousTitre() | t }}</p></div>
      @if (portee() === 'mine' && auth.a(d.deposer)) { <a routerLink="/nouvelle-demande" class="btn primary"><nx-icon name="plus" /> {{ 'Nouvelle demande' | t }}</a> }
    </div>
    <div class="card pad row filtres">
      <div class="field grow"><label for="q">{{ 'Rechercher' | t }}</label>
        <input id="q" class="input" [value]="q()" (input)="q.set($any($event.target).value)" (keydown.enter)="charger()" [placeholder]="'CUR, NIU, raison sociale' | t" /></div>
      <div class="field"><label for="st">{{ 'Statut' | t }}</label>
        <select id="st" class="input" (change)="statut.set($any($event.target).value); charger()">
          <option value="">{{ 'Tous' | t }}</option>
          @for (s of statuts; track s) { <option [value]="s" [selected]="statut() === s">{{ libelles[s] | t }}</option> }
        </select></div>
      @if (portee() !== 'mine') {
        <div class="field"><label for="cr">{{ 'Critère indexé' | t }}</label>
          <input id="cr" class="input mono" [value]="critere()" (input)="critere.set($any($event.target).value)" (keydown.enter)="charger()" placeholder="commodityClassificationId=080390" /></div>
      }
      <button class="btn" (click)="charger()"><nx-icon name="search" /> {{ 'Filtrer' | t }}</button>
    </div>
    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
    <div class="card scroll-x">
      <table class="datagrid">
        <thead><tr>
          <th>{{ 'Dossier (CUR)' | t }}</th><th>{{ 'Procédure' | t }}</th><th>{{ 'Nature' | t }}</th><th>{{ 'Opérateur' | t }}</th>
          @if (portee() !== 'mine') { <th>{{ 'Agent' | t }}</th> }
          <th>{{ 'Déposé le' | t }}</th><th>{{ 'Échéance' | t }}</th><th>{{ 'Statut' | t }}</th>
        </tr></thead>
        <tbody>
          @for (x of lignes(); track x.id) {
            <tr (click)="ouvrir(x)">
              <td class="mono"><b>{{ x.dossierNo ?? ('Brouillon' | t) }}</b></td>
              <td>{{ x.procedureCode }} <span class="chip">{{ x.requestType }}</span></td>
              <td>{{ natures[x.requestNature] | t }}</td>
              <td>{{ x.applicantName }}<div class="muted small mono">{{ x.applicantNiu }}</div></td>
              @if (portee() !== 'mine') { <td class="small">{{ x.assignedAgent ?? '—' }}<div class="muted">{{ x.signatory }}</div></td> }
              <td class="small">{{ (x.submittedAt ?? x.createdAt) | date: 'dd/MM/yyyy HH:mm' }}</td>
              <td class="small" [class.retard]="enRetard(x)">{{ x.slaDueAt ? (x.slaDueAt | date: 'dd/MM HH:mm') : '—' }}</td>
              <td><nx-statut [code]="x.status" /></td>
            </tr>
          } @empty { <tr><td colspan="8" class="empty">{{ (charge() ? 'Aucun dossier.' : 'Chargement…') | t }}</td></tr> }
        </tbody>
      </table>
    </div>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 16px; }
    .filtres { align-items: flex-end; }
    .retard { color: var(--danger); font-weight: 600; }
  `],
})
export class ListeDossiersPage {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  protected readonly auth = inject(AuthService);
  protected readonly d = DROITS;
  protected readonly natures = NATURES;
  protected readonly libelles = LIBELLES;
  protected readonly statuts = Object.keys(LIBELLES);
  readonly scope = input<string>();
  protected readonly portee = computed(() => this.scope() ?? (this.route.snapshot.data['scope'] as string) ?? 'mine');
  protected readonly titre = computed(() => ({ mine: 'Mes demandes', assigned: 'Dossiers cotés', all: 'Tous les dossiers' } as Record<string, string>)[this.portee()]);
  protected readonly sousTitre = computed(() => ({
    mine: 'Demandes déposées par vous ou pour les opérateurs auxquels votre compte est rattaché.',
    assigned: 'Dossiers qui vous sont cotés comme agent de traitement ou signataire (RG2 UC-04).',
    all: 'Tous les dossiers déposés, avec recherche sur les critères indexés (projection JSONB).',
  } as Record<string, string>)[this.portee()]);
  protected readonly lignes = signal<Ligne[]>([]);
  protected readonly q = signal('');
  protected readonly statut = signal('');
  protected readonly critere = signal('');
  protected readonly charge = signal(false);
  protected readonly erreur = signal<string | null>(null);

  constructor() {
    this.route.queryParamMap.subscribe(p => { this.q.set(p.get('q') ?? ''); this.charger(); });
  }

  protected charger(): void {
    this.charge.set(false);
    this.api.dossiers({ scope: this.portee(), q: this.q(), status: this.statut(), criterion: this.critere(), limit: 300 }).subscribe({
      next: l => { this.lignes.set(l); this.charge.set(true); this.erreur.set(null); },
      error: e => { this.erreur.set(erreurs(e).join(' ')); this.charge.set(true); },
    });
  }

  protected enRetard(x: Ligne): boolean {
    return x.lifecycle === 'ACTIVE' && !!x.slaDueAt && new Date(x.slaDueAt).getTime() < Date.now();
  }

  protected ouvrir(x: Ligne): void {
    this.router.navigate(['/demandes', x.id]);
  }
}
