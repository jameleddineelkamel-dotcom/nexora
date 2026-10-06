import { Component, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Acces } from '../core/acces';
import { I18N } from '../core/i18n';
import { Icon } from '../shared/icon';
import { MotDePasse } from '../shared/mot-de-passe';

@Component({
  selector: 'nx-compte',
  imports: [DatePipe, RouterLink, Icon, MotDePasse, ...I18N],
  template: `
    <h1>{{ 'Mon compte' | t }}</h1>
    @if (acces.utilisateur(); as u) {
      <div class="grid k2">
        <div class="card pad stack">
          <div class="profil">
            <span class="avatar">{{ initiales(u.fullName) }}</span>
            <div><h2>{{ u.fullName }}</h2><span class="muted">{{ u.username }}@if (u.email) { · {{ u.email }} }</span>
              @if (u.organisation) { <div class="small muted">{{ u.organisation }}</div> }</div>
          </div>
          <div><span class="label">{{ 'Rôles' | t }}</span><div class="row">@for (r of u.roles; track r) { <span class="chip accent">{{ r }}</span> } @empty { <span class="muted small">—</span> }</div></div>
          <div><span class="label">{{ 'Groupes' | t }}</span><div class="row">@for (g of u.groups; track g) { <span class="chip">{{ g }}</span> } @empty { <span class="muted small">—</span> }</div></div>
          <div><span class="label">{{ 'Droits' | t }}</span><div class="row">@for (p of u.permissions; track p) { <span class="chip mono">{{ p }}</span> }</div></div>
          @if (u.lastLoginAt) { <p class="small muted">{{ 'Dernière connexion : {0}' | t: (u.lastLoginAt | date: 'dd/MM/yyyy HH:mm') }}</p> }
          <div class="row">
            <a routerLink="/login" class="btn"><nx-icon name="grid" /> {{ 'Applications' | t }}</a>
            <button class="btn ghost danger" (click)="acces.deconnecter()"><nx-icon name="back" /> {{ 'Se déconnecter' | t }}</button>
          </div>
        </div>
        <div class="card pad stack">
          <h2><nx-icon name="edit" /> {{ 'Changer le mot de passe' | t }}</h2>
          <nx-mot-de-passe />
        </div>
      </div>
    }
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 16px; }
    .profil { display: flex; gap: 14px; align-items: center; }
    .avatar { width: 56px; height: 56px; border-radius: 50%; background: var(--gradient); color: #fff; display: grid; place-items: center; font-size: 20px; font-weight: 700; flex-shrink: 0; }
    p { margin: 0; }
    .label { display: block; margin-bottom: 4px; }
  `],
})
export class ComptePage {
  protected readonly acces = inject(Acces);

  ngOnInit(): void { this.acces.moi().subscribe(); }

  protected initiales(n: string): string {
    return n.split(/\s+/).filter(Boolean).slice(0, 2).map(m => m[0].toUpperCase()).join('');
  }
}
