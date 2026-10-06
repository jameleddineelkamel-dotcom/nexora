import { Component, computed, input } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { inject } from '@angular/core';
import { I18N } from '../core/i18n';
import { Icon } from '../shared/icon';
import { AdminUsers } from './admin-users';
import { AdminGroups } from './admin-groups';
import { AdminRoles } from './admin-roles';
import { AdminJournal } from './admin-journal';

type Onglet = 'utilisateurs' | 'groupes' | 'roles' | 'journal';

@Component({
  selector: 'nx-admin',
  imports: [RouterLink, Icon, AdminUsers, AdminGroups, AdminRoles, AdminJournal, ...I18N],
  template: `
    <div>
      <h1>{{ 'Administration des accès' | t }}</h1>
      <p class="muted">{{ 'Utilisateurs, groupes, rôles et habilitations de la plateforme NEXORA.' | t }}</p>
    </div>
    <div class="tabs" role="tablist">
      @for (o of onglets; track o.code) {
        <a class="tab" role="tab" [class.on]="actif() === o.code" [routerLink]="['/admin', o.code]"><nx-icon [name]="o.icone" [size]="16" /> {{ o.libelle | t }}</a>
      }
    </div>
    @switch (actif()) {
      @case ('utilisateurs') { <nx-admin-users /> }
      @case ('groupes') { <nx-admin-groups /> }
      @case ('roles') { <nx-admin-roles /> }
      @case ('journal') { <nx-admin-journal /> }
    }
  `,
  styles: [':host { display: flex; flex-direction: column; gap: 14px; } p { margin: 4px 0 0; } .tab { text-decoration: none; }'],
})
export class AdminPage {
  readonly onglet = input<Onglet | undefined>();
  protected readonly actif = computed<Onglet>(() => this.onglet() ?? 'utilisateurs');
  protected readonly onglets: { code: Onglet; libelle: string; icone: string }[] = [
    { code: 'utilisateurs', libelle: 'Utilisateurs', icone: 'user' }, { code: 'groupes', libelle: 'Groupes', icone: 'layers' },
    { code: 'roles', libelle: 'Rôles et habilitations', icone: 'tag' }, { code: 'journal', libelle: 'Journal de sécurité', icone: 'history' },
  ];
  protected readonly router = inject(Router);
}
