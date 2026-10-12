import { Component, computed, effect, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SessionService } from './core/session';
import { Icon } from './shared/icon';
import { I18n, LANGUES, TPipe } from './core/i18n';
import { AuthService, DROITS } from './core/auth.service';

interface Lien { lien: string; icone: string; libelle: string; exact?: boolean; droits?: string[]; }
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
  protected readonly menuCompte = signal(false);
  protected readonly menuOuvert = signal(false);
  protected readonly langues = LANGUES;
  protected readonly recherche = signal('');

  private readonly groupes: Groupe[] = [
    { titre: '', liens: [{ lien: '/', icone: 'home', libelle: 'Accueil', exact: true }] },
    { titre: 'eFORCE — opérateurs', liens: [
      { lien: '/nouvelle-demande', icone: 'plus', libelle: 'Nouvelle demande', droits: [DROITS.deposer] },
      { lien: '/demandes', icone: 'file', libelle: 'Mes demandes', droits: [DROITS.deposer] },
    ] },
    { titre: 'eGOV — administrations', liens: [
      { lien: '/taches', icone: 'inbox', libelle: 'Mes tâches' },
      { lien: '/dossiers', icone: 'list', libelle: 'Dossiers cotés', droits: [DROITS.traiter, DROITS.signer] },
    ] },
    { titre: 'Supervision', liens: [
      { lien: '/supervision', icone: 'gauge', libelle: 'Tableau de bord', exact: true, droits: [DROITS.superviser, DROITS.administrer] },
      { lien: '/supervision/dossiers', icone: 'layers', libelle: 'Tous les dossiers', droits: [DROITS.superviser, DROITS.administrer] },
      { lien: '/paiements', icone: 'wallet', libelle: 'Encaissements', droits: [DROITS.superviser, DROITS.administrer] },
      { lien: '/messages', icone: 'mail', libelle: 'Messages', droits: [DROITS.superviser, DROITS.administrer] },
    ] },
    { titre: 'Administration', liens: [{ lien: '/administration', icone: 'workflow', libelle: 'Procédures et cotation', droits: [DROITS.administrer] }] },
  ];

  /** Menu limité aux droits de l'utilisateur. */
  protected readonly nav = computed(() => this.groupes
    .map(g => ({ ...g, liens: g.liens.filter(l => !l.droits || l.droits.some(d => this.auth.a(d))) }))
    .filter(g => g.liens.length));

  protected readonly plateforme = computed(() => {
    const c = this.auth.config();
    return [
      { libelle: 'Référentiel Commun', url: c.refdataUrl, droit: 'refdata.' },
      { libelle: 'Registre des opérateurs', url: c.registrationUrl, droit: 'registration.' },
      { libelle: 'Studio des procédures', url: c.proceduresUrl, droit: 'procedures.' },
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
    if (!q) return;
    const route = this.auth.a(DROITS.superviser) || this.auth.a(DROITS.administrer) ? '/supervision/dossiers'
      : this.auth.a(DROITS.traiter) || this.auth.a(DROITS.signer) ? '/dossiers' : '/demandes';
    this.router.navigate([route], { queryParams: { q } });
  }

  protected libelleRole(r: string): string {
    return ({ ADMINISTRATEUR: 'Administrateur', OPERATEUR_ECONOMIQUE: 'Opérateur économique', COMMISSIONNAIRE_AGREE: 'Commissionnaire agréé (CDA)',
      AGENT_TRAITEMENT: 'Agent de traitement', AGENT_SIGNATAIRE: 'Agent signataire', SUPERVISEUR: 'Superviseur', BANQUE: 'Banque',
      ADMINISTRATEUR_ESERVICES: 'Administrateur e-Services' } as Record<string, string>)[r] ?? r;
  }

  protected initiales(): string {
    return (this.auth.utilisateur()?.name ?? '').split(/[\s.\-_]+/).filter(Boolean).slice(0, 2).map(m => m[0].toUpperCase()).join('') || '?';
  }
}
