import { HttpClient, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Observable, tap } from 'rxjs';

export interface Utilisateur {
  username: string; fullName: string; email: string | null; organisation: string | null; language: 'fr' | 'en';
  mustChangePassword: boolean; roles: string[]; groups: string[]; permissions: string[]; lastLoginAt: string | null;
}
export interface Connexion { accessToken: string; expiresIn: number; user: Utilisateur; }
export interface Compte {
  id: number; username: string; fullName: string; email: string | null; organisation: string | null; language: string; active: boolean;
  mustChangePassword: boolean; failedAttempts: number; lockedUntil: string | null; lastLoginAt: string | null; createdAt: string;
  directRoles: string[]; groups: string[]; roles: string[]; permissions: string[];
}
export interface Groupe { id: number; code: string; labelFr: string; labelEn: string | null; description: string | null; roles: string[]; members: string[]; }
export interface Role { id: number; code: string; labelFr: string; labelEn: string | null; description: string | null; system: boolean; permissions: string[]; userCount: number; }
export interface Permission { code: string; application: string; labelFr: string; labelEn: string | null; description: string | null; }
export interface Evenement { id: number; occurredAt: string; type: string; username: string | null; actor: string | null; ip: string | null; details: string | null; }
export interface Application { code: string; label: string; url: string; }

export const ADMINISTRATION = 'auth.administration';
const CLE = 'nexora.auth.jeton';

/** Session de l'interface d'accès (page de connexion, compte, administration). */
@Injectable({ providedIn: 'root' })
export class Acces {
  private readonly http = inject(HttpClient);
  readonly jeton = signal<string | null>(lire());
  readonly utilisateur = signal<Utilisateur | null>(null);
  readonly admin = computed(() => this.utilisateur()?.permissions.includes(ADMINISTRATION) ?? false);

  connecter(username: string, password: string): Observable<Connexion> {
    return this.http.post<Connexion>('/api/v1/auth/login', { username, password }).pipe(tap(c => this.retenir(c)));
  }

  /** Session SSO déjà ouverte (cookie) : renvoie un jeton frais, ou rien. */
  session(): Observable<Connexion | null> {
    return this.http.get<Connexion | null>('/api/v1/auth/session').pipe(tap(c => { if (c) this.retenir(c); }));
  }

  retenir(c: Connexion): void {
    this.jeton.set(c.accessToken);
    this.utilisateur.set(c.user);
    try { sessionStorage.setItem(CLE, c.accessToken); } catch { /* stockage indisponible */ }
  }

  deconnecter(): void {
    this.http.post('/api/v1/auth/logout', {}).subscribe({ complete: () => this.oublier(), error: () => this.oublier() });
  }

  oublier(): void {
    this.jeton.set(null);
    this.utilisateur.set(null);
    try { sessionStorage.removeItem(CLE); } catch { /* stockage indisponible */ }
    location.assign('/login');
  }

  config(): Observable<{ applications: Application[]; passwordMinLength: number; maxAttempts: number }> {
    return this.http.get<{ applications: Application[]; passwordMinLength: number; maxAttempts: number }>('/api/v1/auth/config');
  }
  moi(): Observable<Utilisateur> { return this.http.get<Utilisateur>('/api/v1/auth/me').pipe(tap(u => this.utilisateur.set(u))); }
  changerMotDePasse(current: string, next: string): Observable<void> { return this.http.post<void>('/api/v1/auth/password', { current, next }); }

  // Administration
  comptes(): Observable<Compte[]> { return this.http.get<Compte[]>('/api/v1/admin/users'); }
  creerCompte(c: Partial<Compte> & { password?: string }): Observable<{ user: Compte; temporaryPassword: string | null }> {
    return this.http.post<{ user: Compte; temporaryPassword: string | null }>('/api/v1/admin/users', c);
  }
  modifierCompte(id: number, c: Partial<Compte>): Observable<Compte> { return this.http.put<Compte>(`/api/v1/admin/users/${id}`, c); }
  reinitialiser(id: number): Observable<{ temporaryPassword: string }> { return this.http.post<{ temporaryPassword: string }>(`/api/v1/admin/users/${id}/reset-password`, {}); }
  deverrouiller(id: number): Observable<Compte> { return this.http.post<Compte>(`/api/v1/admin/users/${id}/unlock`, {}); }
  groupes(): Observable<Groupe[]> { return this.http.get<Groupe[]>('/api/v1/admin/groups'); }
  enregistrerGroupe(g: Partial<Groupe>): Observable<Groupe> {
    return g.id ? this.http.put<Groupe>(`/api/v1/admin/groups/${g.id}`, g) : this.http.post<Groupe>('/api/v1/admin/groups', g);
  }
  supprimerGroupe(id: number): Observable<void> { return this.http.delete<void>(`/api/v1/admin/groups/${id}`); }
  roles(): Observable<Role[]> { return this.http.get<Role[]>('/api/v1/admin/roles'); }
  enregistrerRole(r: Partial<Role>): Observable<Role> {
    return r.id ? this.http.put<Role>(`/api/v1/admin/roles/${r.id}`, r) : this.http.post<Role>('/api/v1/admin/roles', r);
  }
  supprimerRole(id: number): Observable<void> { return this.http.delete<void>(`/api/v1/admin/roles/${id}`); }
  permissions(): Observable<Permission[]> { return this.http.get<Permission[]>('/api/v1/admin/permissions'); }
  evenements(f: Record<string, string | number>): Observable<{ items: Evenement[]; total: number }> {
    return this.http.get<{ items: Evenement[]; total: number }>('/api/v1/admin/events', { params: f });
  }
}

function lire(): string | null { try { return sessionStorage.getItem(CLE); } catch { return null; } }

export const jetonInterceptor: HttpInterceptorFn = (req, next) => {
  const j = inject(Acces).jeton();
  const proteges = req.url.startsWith('/api/v1/admin') || req.url === '/api/v1/auth/me' || req.url === '/api/v1/auth/password';
  return next(j && proteges ? req.clone({ setHeaders: { Authorization: `Bearer ${j}` } }) : req);
};

/** Pages réservées : session valide (et droit d'administration pour /admin). */
export const exigeSession = (admin: boolean): CanActivateFn => async () => {
  const acces = inject(Acces);
  const router = inject(Router);
  if (!acces.utilisateur()) {
    try {
      const c = await new Promise<Connexion | null>((ok, ko) => acces.session().subscribe({ next: ok, error: ko }));
      if (!c) return router.createUrlTree(['/login']);
    } catch {
      return router.createUrlTree(['/login']);
    }
  }
  return !admin || acces.admin() || router.createUrlTree(['/compte']);
};

export function erreurs(e: unknown): string[] {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) return ['Le service d\'authentification ne répond pas.'];
    if (e.error?.erreurs?.length) return e.error.erreurs;
    return [`Erreur ${e.status}`];
  }
  return [String(e)];
}
