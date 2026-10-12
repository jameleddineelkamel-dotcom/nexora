import { HttpClient, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, firstValueFrom, throwError } from 'rxjs';

/** Droits des e-Services (attribués par rôle dans le microservice auth). */
export const DROITS = {
  deposer: 'eservices.deposer', mandataire: 'eservices.mandataire', traiter: 'eservices.traiter', signer: 'eservices.signer',
  encaisser: 'eservices.encaisser', superviser: 'eservices.superviser', administrer: 'eservices.administrer', administration: 'auth.administration',
} as const;

export interface Utilisateur {
  username: string; name: string; email: string; roles: string[]; groups: string[]; permissions: string[]; exp: number;
}

export interface ConfigPlateforme { authUrl: string; proceduresUrl?: string; registrationUrl?: string; refdataUrl?: string; eservicesUrl?: string; }

const CLE = 'nexora.jeton';
/** Pages publiques (vérification d'un livrable par son code QR) : aucune connexion exigée. */
const PUBLIQUES = ['/verifier'];

/**
 * Authentification unique NEXORA : l'interface récupère le jeton du service auth (redirection SSO),
 * le joint à chaque appel d'API et adapte l'affichage aux droits qu'il contient.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  readonly config = signal<ConfigPlateforme>({ authUrl: '' });
  readonly authUrl = computed(() => this.config().authUrl.replace(/\/$/, ''));
  readonly jeton = signal<string | null>(null);
  readonly utilisateur = computed<Utilisateur | null>(() => decoder(this.jeton()));
  private readonly droits = computed(() => new Set(this.utilisateur()?.permissions ?? []));
  readonly publique = signal(false);

  a(droit: string): boolean {
    return this.droits().has(droit);
  }

  /** Au démarrage : jeton reçu dans l'adresse (#access_token), sinon jeton mémorisé encore valide, sinon redirection SSO. */
  async initialiser(): Promise<boolean> {
    this.config.set(await firstValueFrom(this.http.get<ConfigPlateforme>('/api/v1/config')));
    const fragment = new URLSearchParams(location.hash.slice(1));
    let jeton = fragment.get('access_token');
    if (jeton) {
      history.replaceState(null, '', location.pathname + location.search);
      ecrire(jeton);
    } else {
      jeton = lire();
    }
    const u = decoder(jeton);
    const valide = !!jeton && !!u && u.exp * 1000 > Date.now() + 30_000;
    if (PUBLIQUES.some(p => location.pathname.startsWith(p))) {
      this.publique.set(true);
      if (valide) this.jeton.set(jeton);
      return true;
    }
    if (!valide) {
      this.connecter();
      return new Promise<boolean>(() => { /* redirection SSO en cours : l'application ne démarre pas */ });
    }
    this.jeton.set(jeton);
    return true;
  }

  connecter(): void {
    effacer();
    location.assign(`${this.authUrl()}/sso/authorize?redirect_uri=${encodeURIComponent(location.href.split('#')[0])}`);
  }

  deconnecter(): void {
    effacer();
    location.assign(`${this.authUrl()}/sso/logout?redirect_uri=${encodeURIComponent(location.origin + '/')}`);
  }

  /** Lien de téléchargement authentifié (PDF, pièce jointe, contenu de message). */
  lien(url: string): string {
    return `${url}${url.includes('?') ? '&' : '?'}access_token=${encodeURIComponent(this.jeton() ?? '')}`;
  }

  compte(): string { return `${this.authUrl()}/compte`; }
  administration(): string { return `${this.authUrl()}/admin`; }
}

function decoder(jeton: string | null): Utilisateur | null {
  if (!jeton) return null;
  try {
    const charge = jeton.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const json = decodeURIComponent(atob(charge).split('').map(c => '%' + c.charCodeAt(0).toString(16).padStart(2, '0')).join(''));
    const c = JSON.parse(json);
    return { username: c.preferred_username ?? c.sub, name: c.name ?? c.sub, email: c.email ?? '', roles: c.roles ?? [], groups: c.groups ?? [],
      permissions: c.permissions ?? [], exp: c.exp ?? 0 };
  } catch {
    return null;
  }
}
function lire(): string | null { try { return localStorage.getItem(CLE); } catch { return null; } }
function ecrire(j: string): void { try { localStorage.setItem(CLE, j); } catch { /* stockage indisponible */ } }
function effacer(): void { try { localStorage.removeItem(CLE); } catch { /* stockage indisponible */ } }

/** Jeton joint aux appels d'API ; jeton expiré ou refusé → nouvelle authentification SSO (sauf page publique). */
export const jetonInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/') || req.url === '/api/v1/config' || req.url.startsWith('/api/v1/public/')) return next(req);
  const auth = inject(AuthService);
  const j = auth.jeton();
  return next(j ? req.clone({ setHeaders: { Authorization: `Bearer ${j}` } }) : req).pipe(
    catchError((e: unknown) => {
      if (e instanceof HttpErrorResponse && e.status === 401 && !auth.publique()) auth.connecter();
      return throwError(() => e);
    }),
  );
};

/** Garde de route : page réservée à l'un des droits. */
export const exigeDroit = (...droits: string[]): CanActivateFn => () => {
  const auth = inject(AuthService);
  return droits.some(d => auth.a(d)) || inject(Router).createUrlTree(['/']);
};
