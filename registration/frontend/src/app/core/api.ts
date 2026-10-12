import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { motif, params } from './session';

export type Operateur = Record<string, unknown> & { niu: string; legalName: string; status: string; enrolledAt: string; updatedAt: string; updatedBy: string | null };
export interface Attribut { key: string; label: string; type: 'T' | 'N' | 'D'; }
export interface Compte { niu: string; username: string; relation: 'TITULAIRE' | 'MANDATAIRE'; active: boolean; createdAt: string; createdBy: string; }
export interface Piece { id: number; docCode: string; label: string | null; fileName: string; mimeType: string; sizeBytes: number; sha256: string; validUntil: string | null; uploadedBy: string; uploadedAt: string; }
export interface Evenement { operation: string; actor: string; channel: string; reason: string | null; changes: Record<string, { old: unknown; new: unknown }>; occurredAt: string; }

@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);
  private readonly v1 = '/api/v1';

  tableauDeBord(): Observable<Record<string, number>> { return this.http.get<Record<string, number>>(`${this.v1}/dashboard`); }
  attributs(): Observable<Attribut[]> { return this.http.get<Attribut[]>(`${this.v1}/attributes`); }
  rechercher(q = '', limit = 300): Observable<Operateur[]> { return this.http.get<Operateur[]>(`${this.v1}/parties`, { params: params({ q, limit }) }); }
  lire(niu: string): Observable<Operateur> { return this.http.get<Operateur>(`${this.v1}/parties/${niu}`); }
  enroler(o: Record<string, unknown>, m = ''): Observable<Operateur> { return this.http.post<Operateur>(`${this.v1}/parties`, o, { headers: motif(m) }); }
  modifier(niu: string, o: Record<string, unknown>, m = ''): Observable<Operateur> { return this.http.put<Operateur>(`${this.v1}/parties/${niu}`, o, { headers: motif(m) }); }
  historique(niu: string): Observable<Evenement[]> { return this.http.get<Evenement[]>(`${this.v1}/parties/${niu}/history`); }
  comptes(niu: string): Observable<Compte[]> { return this.http.get<Compte[]>(`${this.v1}/parties/${niu}/users`); }
  rattacher(niu: string, c: Partial<Compte>): Observable<Compte[]> { return this.http.put<Compte[]>(`${this.v1}/parties/${niu}/users`, c); }
  pieces(niu: string): Observable<Piece[]> { return this.http.get<Piece[]>(`${this.v1}/parties/${niu}/documents`); }
  joindre(niu: string, docCode: string, label: string, f: File): Observable<Piece> {
    const fd = new FormData();
    fd.append('file', f);
    return this.http.post<Piece>(`${this.v1}/parties/${niu}/documents`, fd, { params: params({ docCode, label }) });
  }
  retirer(niu: string, id: number): Observable<void> { return this.http.delete<void>(`${this.v1}/parties/${niu}/documents/${id}`); }
  urlPiece(niu: string, id: number): string { return `${this.v1}/parties/${niu}/documents/${id}/content`; }
}
