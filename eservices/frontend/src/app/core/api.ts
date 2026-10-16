import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { motif, params } from './session';

// ------------------------------------------------------------------ paquet publié (formulaire)
export interface CodeValue { code: string; labelFr: string; labelEn: string | null; }
export interface RequestType { code: string; labelFr: string; labelEn: string | null; curCode: string; natures: string[]; description: string | null; }
export interface Nature { code: string; labelFr: string; labelEn: string; }
export interface Section { code: string; labelFr: string; labelEn: string | null; repeating: boolean; minRows: number; sortOrder: number; parentCode?: string | null; }
export interface Field {
  code: string; sectionCode: string; labelFr: string; labelEn: string | null; dataType: string; maxLength: number | null; pattern: string | null;
  mandatory: 'OUI' | 'NON' | 'CONDITIONNEL'; conditionText: string | null; refdataTable: string | null; codeValues: CodeValue[] | null;
  untdedRef: string | null; wcoRef: string | null; cclDen: string | null; biePath: string | null; searchable: boolean; prefill: string | null;
  amendable: boolean; helpFr: string | null; helpEn: string | null;
}
export interface RequiredDocument { code: string; labelFr: string; labelEn: string | null; mandatory: boolean; conditionText: string | null; requestTypes: string[]; observation: string | null; }
export interface CaseStatus { code: string; labelFr: string; labelEn: string | null; isFinal: boolean; tone: 'neutral' | 'info' | 'warn' | 'ok' | 'danger'; }
export interface Lieu { code: string; labelFr: string; labelEn: string | null; unitCode: string; unitLabel: string; sortOrder: number; active: boolean; agents: number; signataires: number; }
/** Formulaires en code Angular généré : dossier des sources, rendu demandé et présence du code par version déployée. */
export interface EtatFormulairesGeneres {
  directory: string; directoryExists: boolean;
  deployments: { procedure: string; versionNo: number; labelFr: string; active: boolean; rendu: 'DYNAMIQUE' | 'GENERE'; inSources: boolean }[];
  written?: string[]; next?: string;
}
export interface Formulaire {
  procedure: { code: string; labelFr: string; labelEn: string | null; authority: string; description: string };
  versionNo: number; requestTypes: RequestType[]; natures: Nature[]; sections: Section[]; fields: Field[]; documents: RequiredDocument[];
  statuses: CaseStatus[]; deliverables: { code: string; labelFr: string }[];
  settings: { processingLocationField: string; applicantField: string; previousField: string; renewalYearField: string; currency: string; ihm?: Record<string, unknown>;
    /** Rendu du formulaire choisi dans le studio : DYNAMIQUE (défaut) ou GENERE (code Angular généré, s'il est compilé dans e-Services). */
    rendu?: 'DYNAMIQUE' | 'GENERE' };
  locations: Lieu[];
}
export interface Procedure {
  code: string; labelFr: string; labelEn: string | null; authority: string; description: string; versionNo: number;
  terminology: { circuit: { code: string; labelFr: string }; phase: { code: string; labelFr: string; bspArea: string }; process: { code: string; labelFr: string } };
  requestTypes: RequestType[]; natures: Nature[]; fees: { code: string; beneficiary: string; labelFr: string; amount: number; requestTypes: string[]; natures: string[] }[];
  slaHours: string | null;
}

// ------------------------------------------------------------------ dossiers
export interface Dossier {
  id: string; dossierNo: string | null; deploymentId: number; procedureCode: string; versionNo: number; requestType: string; requestNature: string;
  parentDossierNo: string | null; applicantNiu: string | null; applicantName: string | null; submittedBy: string; onBehalf: boolean; status: string;
  lifecycle: 'DRAFT' | 'ACTIVE' | 'CLOSED' | 'CANCELLED' | 'ARCHIVED'; processingLocation: string | null; adminUnit: string | null;
  assignedAgent: string | null; signatory: string | null; processInstanceId: string | null; formData: Record<string, unknown>;
  previousData: Record<string, unknown> | null; searchCriteria: Record<string, string[]>; fees: { code: string; beneficiary: string; label: string; amount: number }[];
  totalAmount: number; currency: string; lastObservation: string | null; slaDueAt: string | null; createdAt: string; submittedAt: string | null;
  updatedAt: string; closedAt: string | null;
}
export interface Ligne {
  id: string; dossierNo: string | null; procedureCode: string; requestType: string; requestNature: string; applicantNiu: string | null;
  applicantName: string | null; submittedBy: string; status: string; lifecycle: string; processingLocation: string | null; assignedAgent: string | null;
  signatory: string | null; totalAmount: number; createdAt: string; submittedAt: string | null; slaDueAt: string | null;
}
export interface Piece { id: number; docCode: string; fileName: string; mimeType: string; sizeBytes: number; sha256: string; source: string; uploadedBy: string; uploadedAt: string; }
export interface StatutHisto { id: number; fromStatus: string | null; toStatus: string; activityId: string | null; actor: string; reason: string | null; occurredAt: string; }
export interface Tache {
  id: string; name: string; taskKey: string; formKey: string | null; assignee: string; created: string; due: string | null; decisions: string[];
  dossierId: string | null; dossierNo: string | null; procedureCode: string | null; requestType: string | null; requestNature: string | null;
  applicantName: string | null; status: string | null; fields?: string[];
}
export interface Message {
  id: string; dossierId: string | null; dossierNo: string | null; flowCode: string | null; flowSeq: number | null; code: string; type: string;
  direction: 'IN' | 'OUT' | 'INTERNAL'; sender: string | null; recipient: string | null; format: string; contentType: string; state: string;
  correlationId: string; refTo: string | null; idempotencyKey: string | null; length: number | null; sha256: string | null;
  validation: { valid?: boolean; errors?: string[] }; observation: string | null; createdBy: string; createdAt: string; updatedAt: string;
}
export interface AvisPaiement {
  id: number; noticeNo: string; dossierId: string; dossierNo: string; status: string; payee: string; payerNiu: string | null; payerName: string | null;
  totalAmount: number; currency: string; issuedAt: string; dueAt: string | null; paidAt: string | null; paymentReference: string | null;
  bankCode: string | null; channel: string | null; lines: { lineNo: number; feeCode: string; beneficiary: string; label: string; amount: number }[];
}
export interface Livrable {
  id: number; dossierId: string; dossierNo: string; deliverableCode: string; docNo: string; baseDocNo: string; versionNo: number; holderNiu: string | null;
  holderName: string | null; requestType: string; validFrom: string; validUntil: string | null; signedAt: string; signedBy: string; signerName: string | null;
  qrText: string; sealText: string; fileName: string; sha256: string; status: string;
}
export interface DetailDossier {
  dossier: Dossier; form: Formulaire; attachments: Piece[]; history: StatutHisto[]; tasks: Tache[]; messages: Message[];
  paymentNotices: AvisPaiement[]; documents: Livrable[]; cotation: { location: string; unit: string; agent: string; signatory: string; candidates: number; strategy: string; occurredAt: string }[];
  canEdit: boolean; canPreview: boolean;
}
export interface Bilan { erreurs: { champ: string; message: string; ligne: number | null }[]; frais: { code: string; beneficiaire: string; libelle: string; montant: number }[]; total: number; regles: string[]; }
export interface Valeur { code: string; labelFr: string; labelEn: string | null; parentCode: string | null; }

// ------------------------------------------------------------------ administration et supervision
export interface Deploiement { id: number; procedureCode: string; versionNo: number; labelFr: string; labelEn: string | null; packageHash: string; processKey: string; processDefinitionId: string; active: boolean; deployedAt: string; deployedBy: string; }
export interface Publiee { code: string; labelFr: string; labelEn: string | null; versionId: number; versionNo: number; publishedAt: string; publishedBy: string; packageHash: string; deployedVersionNo: number | null; upToDate: boolean; }
export interface Membre { unitCode: string; username: string; role: 'TRAITEMENT' | 'SIGNATAIRE'; fullName: string | null; active: boolean; openCases: number; }
export interface Unite { code: string; label: string; authority: string; active: boolean; membres: Membre[]; }
export interface Incident { id: string; type: string; message: string; activityId: string; jobId: string; processInstanceId: string; time: string; dossierNo: string | null; }
export interface Encaissements { byBeneficiary: { beneficiary: string; notices: number; amount: number; currency: string }[]; totals: { paidCount: number; paidAmount: number; pendingCount: number; pendingAmount: number }; }
export interface Supervision {
  byStatus: { procedure: string; status: string; count: number }[]; byType: { procedure: string; type: string; nature: string; count: number }[];
  sla: { overdue: number; active: number; avgHoursToIssue: number }; messagesByFormat: { format: string; direction: string; count: number }[];
  payments: Encaissements; incidents: number; deployments: Deploiement[];
}
export interface Evenement { id: number; occurredAt: string; actor: string; channel: string; actionCode: string; dossierId: string | null; dossierNo: string | null; reason: string | null; details: Record<string, unknown>; rowHash: string; }
export interface Verification { docNo: string; found: boolean; holderName?: string; holderNiu?: string; requestType?: string; baseDocNo?: string; versionNo?: number; validFrom?: string; validUntil?: string | null; signedAt?: string; signerName?: string; status?: string; sealValid?: boolean; valid?: boolean; }

@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);
  private readonly v1 = '/api/v1';

  catalogue(): Observable<Procedure[]> { return this.http.get<Procedure[]>(`${this.v1}/catalog`); }
  formulaire(code: string): Observable<Formulaire> { return this.http.get<Formulaire>(`${this.v1}/catalog/${code}/form`); }
  titres(code: string): Observable<{ dossierNo: string; requestType: string; applicantNiu: string; applicantName: string; docNo: string; baseDocNo: string; validUntil: string }[]> {
    return this.http.get<never>(`${this.v1}/catalog/${code}/titles`);
  }
  valeurs(table: string, q = '', limit = 5000): Observable<Valeur[]> { return this.http.get<Valeur[]>(`${this.v1}/lookup/${table}`, { params: params({ q, limit }) }); }

  creer(d: { procedure: string; requestType: string; nature: string; niu?: string | null; sourceDossierNo?: string | null }): Observable<Dossier> {
    return this.http.post<Dossier>(`${this.v1}/dossiers`, d);
  }
  dossiers(f: { scope?: string; procedure?: string; status?: string; q?: string; criterion?: string; limit?: number } = {}): Observable<Ligne[]> {
    return this.http.get<Ligne[]>(`${this.v1}/dossiers`, { params: params(f) });
  }
  dossier(id: string): Observable<DetailDossier> { return this.http.get<DetailDossier>(`${this.v1}/dossiers/${id}`); }
  modifier(id: string, data: Record<string, unknown>): Observable<Dossier> { return this.http.put<Dossier>(`${this.v1}/dossiers/${id}/data`, data); }
  joindre(id: string, docCode: string, f: File): Observable<Piece> {
    const fd = new FormData();
    fd.append('file', f);
    return this.http.post<Piece>(`${this.v1}/dossiers/${id}/attachments`, fd, { params: params({ docCode }) });
  }
  retirer(id: string, piece: number): Observable<void> { return this.http.delete<void>(`${this.v1}/dossiers/${id}/attachments/${piece}`); }
  urlPiece(id: string, piece: number): string { return `${this.v1}/dossiers/${id}/attachments/${piece}`; }
  estimer(id: string): Observable<Bilan> { return this.http.get<Bilan>(`${this.v1}/dossiers/${id}/estimate`); }
  deposer(id: string): Observable<Dossier> { return this.http.post<Dossier>(`${this.v1}/dossiers/${id}/submit`, {}); }
  apercu(id: string): Observable<Blob> { return this.http.get(`${this.v1}/dossiers/${id}/deliverable/preview`, { responseType: 'blob' }); }
  journalDossier(id: string): Observable<Evenement[]> { return this.http.get<Evenement[]>(`${this.v1}/dossiers/${id}/journal`); }

  taches(): Observable<Tache[]> { return this.http.get<Tache[]>(`${this.v1}/tasks`); }
  completer(id: string, d: { decision: string; observation: string; data?: Record<string, unknown> }): Observable<{ completed: boolean; decision: string; dossierNo: string }> {
    return this.http.post<never>(`${this.v1}/tasks/${id}/complete`, d);
  }

  message(id: string): Observable<{ message: Message; values: { path: string; occurrence: number; fieldCode: string | null; valueText: string; valueNum: number | null }[]; states: { from: string | null; to: string; at: string; by: string; reason: string | null }[] }> {
    return this.http.get<never>(`${this.v1}/messages/${id}`);
  }
  contenu(id: string): Observable<string> { return this.http.get(`${this.v1}/messages/${id}/content`, { responseType: 'text' }); }
  urlContenu(id: string): string { return `${this.v1}/messages/${id}/content?download=true`; }
  recevoir(code: string, corps: string, type: string, cle: string, partenaire: string): Observable<Message> {
    return this.http.post<Message>(`${this.v1}/messages/inbound`, corps, { params: params({ code }), headers: { 'Content-Type': type, 'Idempotency-Key': cle, 'X-Nexora-Partner': partenaire } });
  }

  avis(no: string): Observable<AvisPaiement> { return this.http.get<AvisPaiement>(`${this.v1}/payment-notices/${no}`); }
  payer(no: string, bank: string, channel: string): Observable<Message> { return this.http.post<Message>(`${this.v1}/payment-notices/${no}/pay`, { bank, channel }); }
  urlPdf(no: string, download = false): string { return `${this.v1}/documents/${no}/pdf${download ? '?download=true' : ''}`; }
  verifier(no: string): Observable<Verification> { return this.http.get<Verification>(`${this.v1}/public/verify/${no}`); }

  // administration
  publiees(): Observable<Publiee[]> { return this.http.get<Publiee[]>(`${this.v1}/admin/published`); }
  formulairesGeneres(): Observable<EtatFormulairesGeneres> { return this.http.get<EtatFormulairesGeneres>(`${this.v1}/admin/generated-forms`); }
  preparerFormulairesGeneres(): Observable<EtatFormulairesGeneres> { return this.http.post<EtatFormulairesGeneres>(`${this.v1}/admin/generated-forms`, {}); }
  deploiements(): Observable<Deploiement[]> { return this.http.get<Deploiement[]>(`${this.v1}/admin/deployments`); }
  deployer(code: string, m = ''): Observable<Deploiement> { return this.http.post<Deploiement>(`${this.v1}/admin/deployments`, { code }, { headers: motif(m) }); }
  listes(): Observable<{ table: string; entries: number; loadedAt: string; loadedBy: string }[]> { return this.http.get<never>(`${this.v1}/admin/code-lists`); }
  rafraichirListes(code: string): Observable<{ failures: Record<string, string> }> { return this.http.post<never>(`${this.v1}/admin/code-lists/refresh`, { code }); }
  lieux(): Observable<Lieu[]> { return this.http.get<Lieu[]>(`${this.v1}/admin/locations`); }
  lieu(l: Partial<Lieu>): Observable<Lieu[]> { return this.http.put<Lieu[]>(`${this.v1}/admin/locations`, l); }
  unites(): Observable<Unite[]> { return this.http.get<Unite[]>(`${this.v1}/admin/units`); }
  unite(u: Partial<Unite>): Observable<Unite[]> { return this.http.put<Unite[]>(`${this.v1}/admin/units`, u); }
  membre(m: Partial<Membre>): Observable<Unite[]> { return this.http.put<Unite[]>(`${this.v1}/admin/members`, m); }
  incidents(): Observable<Incident[]> { return this.http.get<Incident[]>(`${this.v1}/admin/incidents`); }
  relancer(job: string): Observable<void> { return this.http.post<void>(`${this.v1}/admin/incidents/${job}/retry`, {}); }

  // supervision
  supervision(procedure = ''): Observable<Supervision> { return this.http.get<Supervision>(`${this.v1}/supervision/dashboard`, { params: params({ procedure }) }); }
  encaissements(f: { procedure?: string; from?: string; to?: string }): Observable<Encaissements> { return this.http.get<Encaissements>(`${this.v1}/supervision/payments`, { params: params(f) }); }
  releve(procedure: string, beneficiary: string): Observable<Message> { return this.http.post<Message>(`${this.v1}/supervision/statements`, { procedure, beneficiary }); }
  messages(f: { code?: string; format?: string; state?: string; limit?: number }): Observable<Message[]> { return this.http.get<Message[]>(`${this.v1}/supervision/messages`, { params: params(f) }); }
  tachesOuvertes(): Observable<Tache[]> { return this.http.get<Tache[]>(`${this.v1}/supervision/tasks`); }
  journal(limit = 300): Observable<Evenement[]> { return this.http.get<Evenement[]>(`${this.v1}/supervision/journal`, { params: params({ limit }) }); }
  vues(): Observable<{ schema: string; view: string }[]> { return this.http.get<never>(`${this.v1}/supervision/views`); }
  vue(schema: string, vue: string): Observable<Record<string, unknown>[]> { return this.http.get<never>(`${this.v1}/supervision/views/${schema}/${vue}`); }
}
