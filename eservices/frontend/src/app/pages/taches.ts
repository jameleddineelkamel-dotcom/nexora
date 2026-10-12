import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Router } from '@angular/router';
import { Api, Tache } from '../core/api';
import { I18N } from '../core/i18n';
import { erreurs } from '../core/session';
import { Icon } from '../shared/icon';
import { NATURES, Statut } from '../shared/widgets';

/** Corbeille : tâches assignées à l'utilisateur (traitement, signature, complément demandé au déposant). */
@Component({
  selector: 'nx-taches',
  imports: [DatePipe, Icon, Statut, ...I18N],
  template: `
    <div class="page-head">
      <div><h1>{{ 'Mes tâches' | t }}</h1><p class="muted">{{ 'Tâches du workflow qui vous sont assignées : seul l\\'agent coté voit et traite le dossier (RG2 UC-04).' | t }}</p></div>
      <button class="btn" (click)="charger()"><nx-icon name="refresh" /> {{ 'Actualiser' | t }}</button>
    </div>
    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
    <div class="grid k4">
      @for (g of groupes(); track g.nom) {
        <div class="card pad kpi"><span class="ic" [style.--c]="g.couleur"><nx-icon [name]="g.icone" /></span><div><b>{{ g.n }}</b><span>{{ g.nom | t }}</span></div></div>
      }
    </div>
    <div class="card scroll-x">
      <table class="datagrid">
        <thead><tr><th>{{ 'Tâche' | t }}</th><th>{{ 'Dossier (CUR)' | t }}</th><th>{{ 'Opérateur' | t }}</th><th>{{ 'Nature' | t }}</th><th>{{ 'Reçue le' | t }}</th><th>{{ 'Échéance' | t }}</th><th>{{ 'Statut' | t }}</th></tr></thead>
        <tbody>
          @for (t of taches(); track t.id) {
            <tr (click)="router.navigate(['/demandes', t.dossierId])">
              <td><b>{{ t.name }}</b><div class="muted small">{{ t.decisions.join(' · ') }}</div></td>
              <td class="mono">{{ t.dossierNo }}</td><td>{{ t.applicantName }}</td>
              <td>{{ t.procedureCode }} {{ t.requestType }} · {{ natures[t.requestNature ?? ''] | t }}</td>
              <td class="small">{{ t.created | date: 'dd/MM/yyyy HH:mm' }}</td>
              <td class="small" [class.retard]="t.due && enRetard(t.due)">{{ t.due ? (t.due | date: 'dd/MM HH:mm') : '—' }}</td>
              <td>@if (t.status) { <nx-statut [code]="t.status" /> }</td>
            </tr>
          } @empty { <tr><td colspan="7" class="empty">{{ (charge() ? 'Aucune tâche en attente.' : 'Chargement…') | t }}</td></tr> }
        </tbody>
      </table>
    </div>
  `,
  styles: [`:host { display: flex; flex-direction: column; gap: 16px; } .retard { color: var(--danger); font-weight: 600; }`],
})
export class TachesPage {
  private readonly api = inject(Api);
  protected readonly router = inject(Router);
  protected readonly natures = NATURES;
  protected readonly taches = signal<Tache[]>([]);
  protected readonly charge = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly groupes = computed(() => {
    const t = this.taches();
    const n = (k: string) => t.filter(x => x.formKey?.endsWith(k)).length;
    return [
      { nom: 'à traiter', n: n(':traitement'), icone: 'inbox', couleur: 'var(--blue)' },
      { nom: 'à signer', n: n(':signature'), icone: 'stamp', couleur: 'var(--green)' },
      { nom: 'compléments à fournir', n: n(':complement'), icone: 'mail', couleur: 'var(--warn)' },
      { nom: 'en retard', n: t.filter(x => x.due && this.enRetard(x.due)).length, icone: 'clock', couleur: 'var(--danger)' },
    ];
  });

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.api.taches().subscribe({ next: t => { this.taches.set(t); this.charge.set(true); }, error: e => { this.erreur.set(erreurs(e).join(' ')); this.charge.set(true); } });
  }

  protected enRetard(d: string): boolean {
    return new Date(d).getTime() < Date.now();
  }
}
