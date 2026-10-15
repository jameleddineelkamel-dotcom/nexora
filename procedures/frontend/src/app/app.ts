import { Component, computed, effect, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SessionService } from './core/session';
import { Icon } from './shared/icon';
import { I18n, LANGUES, TPipe } from './core/i18n';
import { AuthService, DROITS } from './core/auth.service';

interface Lien { lien: string; icone: string; libelle: string; exact?: boolean; }
interface Groupe { titre: string; liens: Lien[]; }

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

  protected readonly nav = signal<Groupe[]>([
    { titre: '', liens: [{ lien: '/', icone: 'home', libelle: 'Tableau de bord', exact: true }, { lien: '/procedures', icone: 'workflow', libelle: 'Procédures' }] },
    { titre: 'Référentiels', liens: [{ lien: '/terminologie', icone: 'tree', libelle: 'Terminologie GUCE' }, { lien: '/dictionnaire', icone: 'database', libelle: 'Dictionnaire de données' }] },
    { titre: 'Gouvernance', liens: [{ lien: '/agents', icone: 'robot', libelle: 'Agents IA' }, { lien: '/journal', icone: 'history', libelle: 'Journal d\'audit' }] },
  ]);

  protected readonly plateforme = computed(() => {
    const c = this.auth.config();
    return [
      { libelle: 'Référentiel Commun', url: c.refdataUrl, droit: 'refdata.' },
      { libelle: 'Registre des opérateurs', url: c.registrationUrl, droit: 'registration.' },
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
    if (q) this.router.navigate(['/procedures'], { queryParams: { q } });
  }

  protected libelleRole(r: string): string {
    return ({ ADMINISTRATEUR: 'Administrateur', CONCEPTEUR_PROCEDURES: 'Concepteur de procédures', APPROBATEUR_PROCEDURES: 'Approbateur de procédures',
      SUPERVISEUR: 'Superviseur' } as Record<string, string>)[r] ?? r;
  }

  protected initiales(): string {
    return (this.auth.utilisateur()?.name ?? '').split(/[\s.\-_]+/).filter(Boolean).slice(0, 2).map(m => m[0].toUpperCase()).join('') || '?';
  }
}
