import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { motif, params } from './session';

export type Statut = 'draft' | 'in_review' | 'approved' | 'published' | 'superseded' | 'retired';

export interface ProcedureResume {
  code: string; labelFr: string; labelEn: string | null; authority: string | null; circuitCode: string; circuitFr: string; phaseCode: string; phaseFr: string;
  processCode: string; processFr: string; bspArea: string | null; publishedVersionId: number | null; publishedVersionNo: number | null; publishedAt: string | null;
  workingVersionId: number | null; workingVersionNo: number | null; workingStatus: Statut | null; versionCount: number;
}
export interface Procedure { code: string; labelFr: string; labelEn: string | null; circuitCode: string; authority: string | null; description: string | null; active: boolean; createdAt: string; createdBy: string; }
export interface Version {
  id: number; procedureCode: string; versionNo: number; lifecycleStatus: Statut; settings: Record<string, unknown>; changeNote: string | null; createdAt: string; createdBy: string;
  updatedAt: string; updatedBy: string | null; submittedAt: string | null; submittedBy: string | null; approvedAt: string | null; approvedBy: string | null;
  publishedAt: string | null; publishedBy: string | null; retiredAt: string | null; packageHash: string | null;
}
export interface RequestType { code: string; labelFr: string; labelEn: string | null; curCode: string; natures: string[]; description: string | null; sortOrder: number; }
export interface Actor { code: string; labelFr: string; labelEn: string | null; kind: string; systemCode: string; authRole: string | null; responsibilities: string | null; sortOrder: number; }
export interface CaseStatus { code: string; labelFr: string; labelEn: string | null; isFinal: boolean; tone: string; sortOrder: number; }
export interface Section { code: string; labelFr: string; labelEn: string | null; repeating: boolean; minRows: number; sortOrder: number; parentCode?: string | null; }
export interface CodeValue { code: string; labelFr: string; labelEn: string | null; }
export interface Field {
  code: string; sectionCode: string; labelFr: string; labelEn: string | null; dataType: string; maxLength: number | null; pattern: string | null; mandatory: string;
  conditionText: string | null; refdataTable: string | null; codeValues: CodeValue[] | null; untdedRef: string | null; wcoRef: string | null; cclDen: string | null;
  cclUid: string | null; biePath: string | null; searchable: boolean; prefill: string | null; amendable: boolean; helpFr: string | null; helpEn: string | null; sortOrder: number;
}
export interface RequiredDocument { code: string; labelFr: string; labelEn: string | null; mandatory: boolean; conditionText: string | null; requestTypes: string[]; observation: string | null; sortOrder: number; }
export interface FeeItem { code: string; beneficiary: string; labelFr: string; labelEn: string | null; amount: number; currency: string; requestTypes: string[]; natures: string[]; sortOrder: number; }
export interface MessageField { seq: number; fieldCode: string | null; path: string; mandatory: boolean; indexed: boolean; }
export interface MessageDef {
  code: string; labelFr: string; labelEn: string | null; messageType: string; contentFormat: string; rootElement: string | null; standardRef: string | null;
  schemaText: string | null; sensitivity: string; description: string | null; fields: MessageField[];
}
/** Usage d'un message dans un flux ; contentFormat vide : format du contrat du message. */
export interface FlowMessage { seq: number; messageCode: string; senderActor: string; receiverActor: string; interactionPattern: string; responseToSeq: number | null; timeoutHours: number | null; contentFormat?: string | null; }
export interface Flow { code: string; labelFr: string; labelEn: string | null; sortOrder: number; messages: FlowMessage[]; }
export interface ProcessModel { processKey: string; bpmnXml: string; updatedAt: string | null; updatedBy: string | null; }
export interface BusinessRule { code: string; text: string; source: string | null; drlRule: string | null; }
export interface RuleSet { drl: string; businessRules: BusinessRule[]; updatedAt: string | null; updatedBy: string | null; approvedBy?: string | null; approvedAt?: string | null; approvedHash?: string | null; }
export interface CompilationDrl { compiled: boolean; errors: string[]; rules: string[]; hash: string; saved: boolean; }
export interface ApprobationRegles { approved: boolean; approvedBy: string | null; approvedAt: string | null; stale: boolean; updatedBy: string | null; updatedAt: string | null; }
export interface Untded { tag: string; name: string; description: string | null; representation: string | null; }
export interface RolePartie { code: string; category: string; labelEn: string; labelFr: string; definition: string; biePath: string; mappedTo: string; keywords: string[]; actorGroup: string | null; functionCode: string | null; }
export interface ListeCodes { id: string; agency: string; name: string; description: string | null; agencyCode: string | null; listRef: string | null; listName: string | null; version: string | null; issueDate: string | null; closed: boolean; sourceUri: string | null; entries: number; }
export interface CodeUnCefact { code: string; name: string | null; altNames: string[]; }
export interface ModeleBsp {
  domains: { code: string; labelFr: string; labelEn: string; description: string }[];
  processes: { code: string; phase: string; domain: string; labelFr: string; labelEn: string; description: string; documents: string[] }[];
  entities: { code: string; labelFr: string; labelEn: string; definition: string; abie: string; biePath: string; keyPaths: string[]; note: string | null }[];
  relations: { from: string; to: string; cardinality: string; rule: string }[];
  parties: { entity: string; partyRole: string; cardinality: string; rule: string }[];
}
export interface Instrument { code: string; family: string; number: string | null; titleFr: string; docRef: string | null; issued: string | null; summaryFr: string | null; url: string | null; }
export interface LienInstrument { instrument: string; targetKind: string; target: string; note: string | null; }
export interface MessageEchange { code: string; standard: string; kind: string; name: string; nameFr: string | null; phase: string | null; description: string | null; sourceUri: string | null; }
export interface Deliverable { code: string; labelFr: string; labelEn: string | null; docCode: string; validityRule: string; titleFr: string | null; bodyFields: string[]; signatureNote: string | null; }
export interface VersionComplete {
  version: Version; procedure: Procedure; requestTypes: RequestType[]; actors: Actor[]; statuses: CaseStatus[]; sections: Section[]; fields: Field[];
  documents: RequiredDocument[]; fees: FeeItem[]; messages: MessageDef[]; flows: Flow[]; process: ProcessModel | null; rules: RuleSet | null; deliverables: Deliverable[];
}
/** Code Angular généré pour une version : composant, sélecteur et fichiers (.ts, .html) à placer dans e-Services. */
export interface CodeAngular { format: number; procedure: string; versionNo: number; rendu: 'DYNAMIQUE' | 'GENERE'; component: string; selector: string; files: { path: string; content: string }[]; }
export interface Constat { severity: 'error' | 'warning' | 'info'; rule: string; target: string; message: string; }
export interface Terme { code: string; labelFr: string; labelEn: string | null; sortOrder: number; processCode?: string; phaseCode?: string; bspArea?: string; sharedRequirements?: string; description?: string; }
export interface Terminologie { processes: Terme[]; phases: Terme[]; circuits: Terme[]; natures: Terme[]; }
export interface AgentInfo { code: string; labelFr: string; labelEn: string | null; product: string; description: string; enabled: boolean; policy: Record<string, string>; }
export interface Execution {
  id: number; parentRunId: number | null; agentCode: string; versionId: number | null; objective: string; actionCode: string; autonomyLevel: string; policyDecision: string;
  model: string; status: string; summary: string | null; proposals: number; startedAt: string; endedAt: string | null; startedBy: string;
}
export interface Proposition {
  id: number; runId: number; versionId: number; agentCode: string; kind: string; target: string | null; title: string; payload: Record<string, unknown>;
  rationale: string | null; sourceExcerpt: string | null; proposalType: string; confidence: number | null; reviewStatus: string; reviewedBy: string | null;
  reviewedAt: string | null; reviewComment: string | null; createdAt: string;
}
export interface Evenement { id: number; occurredAt: string; actor: string; channel: string; actionCode: string; procedureCode: string | null; versionId: number | null; aiRunId: number | null; policyDecision: string | null; reason: string | null; details: Record<string, unknown>; rowHash: string; }
export interface Bie { abie: string; property: string; den: string; kind: string; cardinality: string; dataType: string | null; target: string | null; definition: string | null; codeList?: string | null; unitCodeList?: string | null; }
export interface Dashboard { procedures: number; versionsByStatus: Record<string, number>; pendingProposals: number; aiRuns: number; fields: { withBie: number; total: number }; recent: Evenement[]; }

export const PARTIES = ['settings', 'requestTypes', 'actors', 'statuses', 'form', 'documents', 'fees', 'messages', 'flows', 'process', 'rules', 'deliverables'] as const;
export type Partie = typeof PARTIES[number];

@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);
  private readonly v1 = '/api/v1';

  dashboard(): Observable<Dashboard> { return this.http.get<Dashboard>(`${this.v1}/dashboard`); }
  terminologie(): Observable<Terminologie> { return this.http.get<Terminologie>(`${this.v1}/terminology`); }
  circuit(c: Partial<Terme>): Observable<Terminologie> { return this.http.put<Terminologie>(`${this.v1}/terminology/circuits`, c); }

  procedures(): Observable<ProcedureResume[]> { return this.http.get<ProcedureResume[]>(`${this.v1}/procedures`); }
  creerProcedure(p: { code: string; labelFr: string; labelEn?: string; circuitCode: string; authority?: string; description?: string; model?: string; curCode?: string }, m = ''): Observable<VersionComplete> {
    return this.http.post<VersionComplete>(`${this.v1}/procedures`, p, { headers: motif(m) });
  }
  procedure(code: string): Observable<{ procedure: Procedure; versions: Version[] }> { return this.http.get<never>(`${this.v1}/procedures/${code}`); }
  modifierProcedure(code: string, p: Partial<Procedure>, m = ''): Observable<Procedure> { return this.http.put<Procedure>(`${this.v1}/procedures/${code}`, p, { headers: motif(m) }); }
  nouvelleVersion(code: string, changeNote: string): Observable<VersionComplete> { return this.http.post<VersionComplete>(`${this.v1}/procedures/${code}/versions`, { changeNote }); }

  version(id: number): Observable<VersionComplete> { return this.http.get<VersionComplete>(`${this.v1}/versions/${id}`); }
  partie(id: number, partie: Partie, corps: unknown, m = ''): Observable<VersionComplete> {
    return this.http.put<VersionComplete>(`${this.v1}/versions/${id}/parts/${partie}`, corps, { headers: motif(m) });
  }
  controles(id: number): Observable<Constat[]> { return this.http.get<Constat[]>(`${this.v1}/versions/${id}/checks`); }
  cycle(id: number, action: 'submit' | 'approve' | 'reject' | 'publish' | 'retire', comment = ''): Observable<VersionComplete> {
    return this.http.post<VersionComplete>(`${this.v1}/versions/${id}/${action}`, action === 'reject' ? { comment } : {}, { headers: motif(comment) });
  }
  /** Formulaire de la version en code Angular (composant pour e-Services). */
  codeAngular(id: number): Observable<CodeAngular> { return this.http.get<CodeAngular>(`${this.v1}/versions/${id}/angular`); }
  paquet(id: number): Observable<string> { return this.http.get(`${this.v1}/versions/${id}/package`, { responseType: 'text' }); }
  urlBpmn(id: number): string { return `${this.v1}/versions/${id}/bpmn`; }
  urlDrl(id: number): string { return `${this.v1}/versions/${id}/drl`; }
  journalVersion(id: number): Observable<Evenement[]> { return this.http.get<Evenement[]>(`${this.v1}/versions/${id}/journal`); }
  journal(limit = 300): Observable<Evenement[]> { return this.http.get<Evenement[]>(`${this.v1}/journal`, { params: params({ limit }) }); }
  compilerRegles(id: number, drl: string): Observable<CompilationDrl> { return this.http.post<CompilationDrl>(`${this.v1}/versions/${id}/rules/compile`, { drl }); }
  approbationRegles(id: number): Observable<ApprobationRegles> { return this.http.get<ApprobationRegles>(`${this.v1}/versions/${id}/rules/approval`); }
  approuverRegles(id: number, note: string): Observable<ApprobationRegles> { return this.http.post<ApprobationRegles>(`${this.v1}/versions/${id}/rules/approve`, { note }, { headers: motif(note) }); }
  untded(q: string, limit = 100): Observable<Untded[]> { return this.http.get<Untded[]>(`${this.v1}/rdm/untded`, { params: params({ q, limit }) }); }
  rolesParties(): Observable<RolePartie[]> { return this.http.get<RolePartie[]>(`${this.v1}/rdm/party-roles`); }
  listesCodes(): Observable<ListeCodes[]> { return this.http.get<ListeCodes[]>(`${this.v1}/rdm/code-lists`); }
  listeCodes(id: string, q = '', limit = 500): Observable<{ list: ListeCodes; codes: CodeUnCefact[] }> { return this.http.get<never>(`${this.v1}/rdm/code-lists/${encodeURIComponent(id)}`, { params: params({ q, limit }) }); }
  modeleBsp(): Observable<ModeleBsp> { return this.http.get<ModeleBsp>(`${this.v1}/rdm/bsp`); }
  instruments(): Observable<{ instruments: Instrument[]; links: LienInstrument[] }> { return this.http.get<never>(`${this.v1}/rdm/instruments`); }
  messagesEchange(): Observable<MessageEchange[]> { return this.http.get<MessageEchange[]>(`${this.v1}/rdm/messages`); }
  testerRegles(id: number, t: Record<string, unknown>): Observable<Record<string, unknown>> { return this.http.post<Record<string, unknown>>(`${this.v1}/versions/${id}/rules/test`, t); }

  agents(): Observable<{ agents: AgentInfo[]; model: { available: boolean; model: string }; sequence: string[] }> { return this.http.get<never>(`${this.v1}/agents`); }
  activerAgent(code: string, enabled: boolean): Observable<AgentInfo[]> { return this.http.put<AgentInfo[]>(`${this.v1}/agents/${code}/enabled`, { enabled }); }
  executer(id: number, agent: string, objective: string, fichiers: File[]): Observable<{ runs: Execution[]; proposals: Proposition[] }> {
    if (fichiers.length) {
      const fd = new FormData();
      fichiers.forEach(f => fd.append('file', f));
      if (objective) fd.append('objective', objective);
      return this.http.post<never>(`${this.v1}/versions/${id}/agents/${agent}/run`, fd);
    }
    return this.http.post<never>(`${this.v1}/versions/${id}/agents/${agent}/run`, { objective });
  }
  executions(id: number): Observable<Execution[]> { return this.http.get<Execution[]>(`${this.v1}/versions/${id}/runs`); }
  toutesExecutions(): Observable<Execution[]> { return this.http.get<Execution[]>(`${this.v1}/runs`); }
  propositions(id: number, status = ''): Observable<Proposition[]> { return this.http.get<Proposition[]>(`${this.v1}/versions/${id}/proposals`, { params: params({ status }) }); }
  revoir(id: number, decision: 'accept' | 'correct' | 'reject', comment: string, payload?: Record<string, unknown>): Observable<Proposition> {
    return this.http.post<Proposition>(`${this.v1}/proposals/${id}/review`, { decision, comment, payload });
  }
  accepterTout(id: number, kind: string, minConfidence: number): Observable<{ accepted: number }> {
    return this.http.post<never>(`${this.v1}/versions/${id}/proposals/accept-all`, { kind, minConfidence });
  }

  rdm(): Observable<{ family: string; release: string; abies: number; bbies: number; asbies: number; roots: Bie[]; untded?: number; partyRoles?: number; restrictions?: number; codeLists?: number; codes?: number; instruments?: number; messages?: number }> { return this.http.get<never>(`${this.v1}/rdm`); }
  abies(): Observable<{ code: string; den: string; definition: string; depth: number; root: boolean; source?: string }[]> { return this.http.get<never>(`${this.v1}/rdm/abies`); }
  rechercherBie(q: string, limit = 60): Observable<Bie[]> { return this.http.get<Bie[]>(`${this.v1}/rdm/search`, { params: params({ q, limit }) }); }
  abie(code: string): Observable<{ abie: { code: string; den: string; definition: string; source?: string }; properties: Bie[]; paths: string[]; restrictions?: { abie: string; path: string; level: string }[] }> { return this.http.get<never>(`${this.v1}/rdm/abies/${code}`); }
  resoudre(path: string): Observable<{ path: string; bie: Bie; denChain: string[] }> { return this.http.get<never>(`${this.v1}/rdm/resolve`, { params: params({ path }) }); }
}
