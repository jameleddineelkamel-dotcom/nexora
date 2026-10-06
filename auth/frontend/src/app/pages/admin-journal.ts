import { Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Acces, Evenement } from '../core/acces';
import { I18N } from '../core/i18n';
import { Icon } from '../shared/icon';

const TYPES: Record<string, string> = {
  LOGIN_SUCCESS: 'Connexion réussie', LOGIN_FAILURE: 'Échec de connexion', ACCOUNT_LOCKED: 'Compte verrouillé', ACCOUNT_UNLOCKED: 'Compte déverrouillé',
  LOGOUT: 'Déconnexion', USER_CREATED: 'Utilisateur créé', USER_UPDATED: 'Utilisateur modifié', USER_DISABLED: 'Utilisateur désactivé',
  PASSWORD_CHANGED: 'Mot de passe changé', PASSWORD_RESET: 'Mot de passe réinitialisé', GROUP_CREATED: 'Groupe créé', GROUP_UPDATED: 'Groupe modifié',
  GROUP_DELETED: 'Groupe supprimé', ROLE_CREATED: 'Rôle créé', ROLE_UPDATED: 'Habilitations modifiées', ROLE_DELETED: 'Rôle supprimé',
};

/** Journal de sécurité : connexions réussies et échouées, verrouillages, administration des comptes et des droits. */
@Component({
  selector: 'nx-admin-journal',
  imports: [DatePipe, Icon, ...I18N],
  template: `
    <div class="card pad filtres">
      <input class="input" [placeholder]="'Utilisateur ou auteur' | t" [value]="utilisateur()" (change)="utilisateur.set($any($event.target).value); page.set(0); charger()" />
      <select class="input" (change)="type.set($any($event.target).value); page.set(0); charger()">
        <option value="">{{ 'Tous les événements' | t }}</option>
        @for (t of types; track t[0]) { <option [value]="t[0]" [selected]="t[0] === type()">{{ t[1] | t }}</option> }
      </select>
    </div>
    <div class="card scroll-x">
      <table class="datagrid">
        <thead><tr><th>{{ 'Date' | t }}</th><th>{{ 'Événement' | t }}</th><th>{{ 'Utilisateur' | t }}</th><th>{{ 'Auteur' | t }}</th><th>{{ 'Adresse IP' | t }}</th><th>{{ 'Détails' | t }}</th></tr></thead>
        <tbody>
          @for (e of evenements(); track e.id) {
            <tr>
              <td class="small nowrap">{{ e.occurredAt | date: 'dd/MM/yyyy HH:mm:ss' }}</td>
              <td><span class="chip" [class.ok]="e.type === 'LOGIN_SUCCESS'" [class.danger]="e.type === 'LOGIN_FAILURE' || e.type === 'ACCOUNT_LOCKED'"
                [class.accent]="e.type.includes('ROLE') || e.type.includes('GROUP')">{{ (libelles[e.type] ?? e.type) | t }}</span></td>
              <td class="mono small">{{ e.username }}</td><td class="mono small">{{ e.actor }}</td><td class="mono small">{{ e.ip }}</td>
              <td class="small muted">{{ e.details }}</td>
            </tr>
          } @empty { <tr><td colspan="6" class="empty">{{ 'Aucun événement.' | t }}</td></tr> }
        </tbody>
      </table>
    </div>
    <div class="between"><span class="muted small">{{ '{0} événement(s)' | t: total() }}</span>
      <div class="row">
        <button class="btn sm" [disabled]="page() === 0" (click)="page.set(page() - 1); charger()"><nx-icon name="back" [size]="15" /></button>
        <button class="btn sm" [disabled]="(page() + 1) * 50 >= total()" (click)="page.set(page() + 1); charger()"><nx-icon name="chevron" [size]="15" /></button>
      </div></div>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 12px; }
    .filtres { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(220px, 100%), 1fr)); gap: 10px; padding: 12px; }
    .nowrap { white-space: nowrap; }
    .datagrid tbody tr { cursor: default; }
  `],
})
export class AdminJournal {
  private readonly acces = inject(Acces);
  protected readonly evenements = signal<Evenement[]>([]);
  protected readonly total = signal(0);
  protected readonly page = signal(0);
  protected readonly utilisateur = signal('');
  protected readonly type = signal('');
  protected readonly libelles = TYPES;
  protected readonly types = Object.entries(TYPES);

  ngOnInit(): void { this.charger(); }

  protected charger(): void {
    this.acces.evenements({ username: this.utilisateur(), type: this.type(), page: this.page(), size: 50 })
      .subscribe(p => { this.evenements.set(p.items); this.total.set(p.total); });
  }
}
