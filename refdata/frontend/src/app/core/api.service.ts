import { HttpClient, HttpErrorResponse, HttpInterceptorFn, HttpParams } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Activity, Category, Dashboard, Entry, HistoryEvent, ImportResult, ImportRun, LookupItem, MetadataField, Page, Proposition,
  SearchResults, TableDef, TableSummary,
} from './models';

/** Utilisateur courant : transmis à l'historique (en-tête X-Nexora-User) en attendant le service d'identité NEXORA. */
@Injectable({ providedIn: 'root' })
export class SessionService {
  readonly utilisateur = signal(lire('nexora.user') ?? '');
  readonly theme = signal<'light' | 'dark'>((lire('nexora.theme') as 'light' | 'dark') ?? 'light');

  definirUtilisateur(nom: string): void {
    this.utilisateur.set(nom.trim());
    ecrire('nexora.user', nom.trim());
  }

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

export const auditInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/')) return next(req);
  const session = inject(SessionService);
  const headers: Record<string, string> = { 'X-Nexora-Channel': 'UI' };
  if (session.utilisateur()) headers['X-Nexora-User'] = encodeURIComponent(session.utilisateur());
  return next(req.clone({ setHeaders: headers }));
};

/** Messages d'une réponse d'erreur RFC 9457 (« erreurs »). */
export function erreurs(e: unknown): string[] {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) return ['Le service refdata ne répond pas.'];
    if (e.error?.erreurs?.length) return e.error.erreurs;
    if (e.error?.detail) return [e.error.detail];
    return [`Erreur ${e.status} ${e.statusText}`];
  }
  return [String(e)];
}

/** Motif de la modification, enregistré dans l'historique. */
function motifs(motif: string): Record<string, string> {
  return motif.trim() ? { 'X-Nexora-Reason': encodeURIComponent(motif.trim()) } : {};
}

function params(o: Record<string, unknown>): HttpParams {
  let p = new HttpParams();
  for (const [k, v] of Object.entries(o)) if (v !== null && v !== undefined && v !== '') p = p.set(k, String(v));
  return p;
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly api = '/api/v1';

  dashboard(): Observable<Dashboard> { return this.http.get<Dashboard>(`${this.api}/dashboard`); }
  categories(): Observable<Category[]> { return this.http.get<Category[]>(`${this.api}/categories`); }
  creerCategorie(c: Partial<Category>): Observable<Category> { return this.http.post<Category>(`${this.api}/categories`, c); }
  schema(): Observable<{ metadata: MetadataField[] }> { return this.http.get<{ metadata: MetadataField[] }>(`${this.api}/catalogue/schema`); }

  tables(f: { category?: string; source?: string; phase?: string; status?: string } = {}): Observable<TableSummary[]> {
    return this.http.get<TableSummary[]>(`${this.api}/tables`, { params: params(f) });
  }
  table(code: string): Observable<TableDef> { return this.http.get<TableDef>(`${this.api}/tables/${code}`); }
  creerTable(t: Partial<TableDef>, motif = ''): Observable<TableDef> {
    return this.http.post<TableDef>(`${this.api}/tables`, t, { headers: motifs(motif) });
  }
  modifierTable(code: string, t: Partial<TableDef>, force = false, motif = ''): Observable<TableDef> {
    return this.http.put<TableDef>(`${this.api}/tables/${code}`, t, { params: params({ force }), headers: motifs(motif) });
  }
  statutTable(code: string, status: string): Observable<TableDef> {
    return this.http.patch<TableDef>(`${this.api}/tables/${code}/status`, { status });
  }

  entrees(table: string, f: Record<string, unknown>): Observable<Page<Entry>> {
    return this.http.get<Page<Entry>>(`${this.api}/tables/${table}/entries`, { params: params(f) });
  }
  creerEntree(table: string, e: Partial<Entry>, motif = ''): Observable<Entry> {
    return this.http.post<Entry>(`${this.api}/tables/${table}/entries`, e, { headers: motifs(motif) });
  }
  modifierEntree(table: string, code: string, e: Partial<Entry>, motif = ''): Observable<Entry> {
    return this.http.put<Entry>(`${this.api}/tables/${table}/entry`, e, { params: params({ code }), headers: motifs(motif) });
  }
  invalider(table: string, code: string, date: string | null, reason: string): Observable<Entry> {
    return this.http.post<Entry>(`${this.api}/tables/${table}/entry/invalidate`, { date, reason }, { params: params({ code }) });
  }
  reactiver(table: string, code: string, reason: string): Observable<Entry> {
    return this.http.post<Entry>(`${this.api}/tables/${table}/entry/reactivate`, { reason }, { params: params({ code }) });
  }
  lookup(table: string, q: string, limit = 20): Observable<LookupItem[]> {
    return this.http.get<LookupItem[]>(`${this.api}/lookup/${table}`, { params: params({ q, limit }) });
  }

  historique(f: Record<string, unknown>): Observable<Page<HistoryEvent>> {
    return this.http.get<Page<HistoryEvent>>(`${this.api}/history`, { params: params(f) });
  }
  activite(days = 30): Observable<Activity[]> { return this.http.get<Activity[]>(`${this.api}/history/activity`, { params: params({ days }) }); }
  chargements(table?: string): Observable<ImportRun[]> { return this.http.get<ImportRun[]>(`${this.api}/imports`, { params: params({ table }) }); }

  importer(table: string, fichier: File, mode: string, dryRun: boolean, reason: string): Observable<ImportResult> {
    const fd = new FormData();
    fd.append('file', fichier);
    return this.http.post<ImportResult>(`${this.api}/tables/${table}/import`, fd, { params: params({ mode, dryRun, reason }) });
  }
  proposer(fichier: File): Observable<Proposition> {
    const fd = new FormData();
    fd.append('file', fichier);
    return this.http.post<Proposition>(`${this.api}/tables/infer`, fd);
  }
  urlExport(table: string, format: string, status = 'ALL'): string {
    return `${this.api}/tables/${table}/export?format=${format}&status=${status}`;
  }

  rechercher(q: string): Observable<SearchResults> { return this.http.get<SearchResults>(`${this.api}/search`, { params: params({ q }) }); }
}
