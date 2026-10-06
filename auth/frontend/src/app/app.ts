import { Component, effect, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { Acces } from './core/acces';
import { I18n, LANGUES, TPipe } from './core/i18n';
import { Icon } from './shared/icon';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icon, TPipe],
  template: `
    @if (pageConnexion()) {
      <router-outlet />
    } @else {
      <header class="barre">
        <a routerLink="/login" class="marque"><img src="nexora-logo.webp" alt="NEXORA" /></a>
        <span class="module"><nx-icon name="landmark" [size]="15" /> {{ 'Accès et habilitations' | t }}</span>
        <nav>
          <a routerLink="/login" class="lien"><nx-icon name="grid" [size]="16" /> <span>{{ 'Applications' | t }}</span></a>
          @if (acces.admin()) { <a routerLink="/admin" routerLinkActive="on" class="lien"><nx-icon name="landmark" [size]="16" /> <span>{{ 'Administration' | t }}</span></a> }
          <a routerLink="/compte" routerLinkActive="on" class="lien"><nx-icon name="user" [size]="16" /> <span>{{ 'Mon compte' | t }}</span></a>
        </nav>
        <span class="grow"></span>
        <div class="langues" role="group" [attr.aria-label]="'Langue' | t">
          @for (l of langues; track l.code) { <button class="btn sm" [class.on]="i18n.langue() === l.code" (click)="i18n.langue.set(l.code)">{{ l.code.toUpperCase() }}</button> }
        </div>
        <button class="btn icon ghost" (click)="theme.set(theme() === 'light' ? 'dark' : 'light')" [attr.aria-label]="'Thème' | t"><nx-icon [name]="theme() === 'light' ? 'moon' : 'sun'" /></button>
        @if (acces.utilisateur(); as u) { <span class="nom small">{{ u.fullName }}</span> }
        <button class="btn icon ghost" (click)="acces.deconnecter()" [attr.aria-label]="'Se déconnecter' | t"><nx-icon name="back" /></button>
      </header>
      <main><router-outlet /></main>
    }
  `,
  styles: [`
    .barre { position: sticky; top: 0; z-index: 20; display: flex; align-items: center; gap: 12px; padding: 10px 24px; background: var(--surface);
      border-bottom: 1px solid var(--line); flex-wrap: wrap; }
    .marque { display: block; background: #fff; border-radius: 8px; padding: 2px 4px; }
    .marque img { height: 34px; width: auto; display: block; }
    .module { display: inline-flex; align-items: center; gap: 6px; font-weight: 700; font-size: 13px; color: var(--navy); }
    :root[data-theme="dark"] .module { color: var(--accent); }
    nav { display: flex; gap: 4px; }
    .lien { display: inline-flex; align-items: center; gap: 6px; padding: 7px 10px; border-radius: 9px; color: var(--muted); font-weight: 600; text-decoration: none; }
    .lien:hover { background: var(--chip); color: var(--text); text-decoration: none; }
    .lien.on { background: var(--navy); color: #fff; }
    .langues { display: flex; gap: 2px; padding: 2px; border: 1px solid var(--line); border-radius: 10px; }
    .langues .btn { height: 28px; border: 0; padding: 0 9px; font-size: 12px; }
    .langues .btn.on { background: var(--navy); color: #fff; }
    .nom { font-weight: 600; }
    main { padding: 24px; max-width: 1280px; margin: 0 auto; }
    @media (max-width: 760px) {
      .barre { padding: 8px 12px; gap: 8px; }
      .module, .nom, .lien span { display: none; }
      main { padding: 14px 12px 32px; }
    }
  `],
})
export class App {
  protected readonly acces = inject(Acces);
  protected readonly i18n = inject(I18n);
  protected readonly langues = LANGUES;
  protected readonly pageConnexion = signal(true);
  protected readonly theme = signal<'light' | 'dark'>((() => { try { return (localStorage.getItem('nexora.theme') as 'light' | 'dark') ?? 'light'; } catch { return 'light'; } })());

  constructor() {
    inject(Router).events.pipe(filter(e => e instanceof NavigationEnd))
      .subscribe(e => this.pageConnexion.set((e as NavigationEnd).urlAfterRedirects.startsWith('/login')));
    effect(() => {
      document.documentElement.setAttribute('data-theme', this.theme());
      try { localStorage.setItem('nexora.theme', this.theme()); } catch { /* stockage indisponible */ }
    });
  }
}
