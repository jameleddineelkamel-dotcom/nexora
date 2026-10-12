import { Component, computed, effect, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SessionService } from './core/session';
import { Icon } from './shared/icon';
import { I18n, LANGUES, TPipe } from './core/i18n';
import { AuthService, DROITS } from './core/auth.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icon, TPipe],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly router = inject(Router);
  protected readonly session = inject(SessionService);
  protected readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthService);
  protected readonly droits = DROITS;
  protected readonly menuCompte = signal(false);
  protected readonly menuOuvert = signal(false);
  protected readonly langues = LANGUES;
  protected readonly recherche = signal('');
  protected readonly nav = signal([{ titre: '', liens: [
    { lien: '/', icone: 'home', libelle: 'Tableau de bord', exact: true }, { lien: '/operateurs', icone: 'building', libelle: 'Opérateurs économiques', exact: false }] }]);

  protected readonly plateforme = computed(() => {
    const c = this.auth.config();
    return [
      { libelle: 'Référentiel Commun', url: c.refdataUrl, droit: 'refdata.' },
      { libelle: 'Studio des procédures', url: c.proceduresUrl, droit: 'procedures.' },
      { libelle: 'e-Services', url: c.eservicesUrl, droit: 'eservices.' },
      { libelle: 'Accès (SSO)', url: c.authUrl ? c.authUrl.replace(/\/$/, '') + '/admin' : undefined, droit: 'auth.' },
    ];
  });

  constructor() {
    effect(() => document.documentElement.setAttribute('data-theme', this.session.theme()));
  }

  protected accessible(prefixe: string): boolean {
    return (this.auth.utilisateur()?.permissions ?? []).some(p => p.startsWith(prefixe));
  }

  protected chercher(): void {
    const q = this.recherche().trim();
    if (q) this.router.navigate(['/operateurs'], { queryParams: { q } });
  }

  protected libelleRole(r: string): string {
    return ({ ADMINISTRATEUR: 'Administrateur', ADMINISTRATEUR_ESERVICES: 'Administrateur e-Services', SUPERVISEUR: 'Superviseur' } as Record<string, string>)[r] ?? r;
  }

  protected initiales(): string {
    return (this.auth.utilisateur()?.name ?? '').split(/[\s.\-_]+/).filter(Boolean).slice(0, 2).map(m => m[0].toUpperCase()).join('') || '?';
  }
}
