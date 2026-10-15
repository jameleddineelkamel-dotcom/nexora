import { HttpErrorResponse, HttpInterceptorFn, HttpParams } from '@angular/common/http';
import { Injectable, signal } from '@angular/core';

/** Préférences d'affichage de l'utilisateur (thème), communes aux applications NEXORA. */
@Injectable({ providedIn: 'root' })
export class SessionService {
  readonly theme = signal<'light' | 'dark'>((lire('nexora.theme') as 'light' | 'dark') ?? 'light');

  basculerTheme(): void {
    this.theme.set(this.theme() === 'light' ? 'dark' : 'light');
    ecrire('nexora.theme', this.theme());
  }
}

function lire(k: string): string | null {
  try { return localStorage.getItem(k); } catch { return null; }
}
function ecrire(k: string, v: string): void {
  try { localStorage.setItem(k, v); } catch { /* stockage indisponible */ }
}

/** Canal « UI » dans l'historique (l'auteur vient du jeton SSO). */
export const auditInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/')) return next(req);
  return next(req.clone({ setHeaders: { 'X-Nexora-Channel': 'UI' } }));
};

/** Messages d'une réponse d'erreur RFC 9457 (« erreurs »). */
export function erreurs(e: unknown, service = 'Le service'): string[] {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) return [`${service} ne répond pas.`];
    if (e.error?.erreurs?.length) return e.error.erreurs;
    if (e.error?.detail) return [e.error.detail];
    if (e.status === 403) return ["Accès refusé : votre profil n'a pas le droit requis pour cette action."];
    if (e.status === 401) return ['Session expirée : reconnectez-vous.'];
    return [`Erreur ${e.status} ${e.statusText}`];
  }
  return [String(e)];
}

/** Motif de la modification, enregistré dans l'historique. */
export function motif(m: string): Record<string, string> {
  return m.trim() ? { 'X-Nexora-Reason': encodeURIComponent(m.trim()) } : {};
}

export function params(o: Record<string, unknown>): HttpParams {
  let p = new HttpParams();
  for (const [k, v] of Object.entries(o)) if (v !== null && v !== undefined && v !== '') p = p.set(k, String(v));
  return p;
}

/** Téléchargement d'un contenu binaire (Blob) sous un nom de fichier. */
export function enregistrer(blob: Blob, nom: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = nom;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}
