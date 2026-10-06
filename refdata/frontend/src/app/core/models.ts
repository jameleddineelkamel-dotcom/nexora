export type Source = 'INTERNATIONALE' | 'REGIONALE' | 'NATIONALE';
export type Phase = 'BUY' | 'SHIP' | 'PAY';
export type Role = 'CODE' | 'LABEL_FR' | 'LABEL_EN' | 'ATTRIBUTE';
export type DataType = 'STRING' | 'TEXT' | 'INTEGER' | 'DECIMAL' | 'DATE' | 'BOOLEAN' | 'CODE_REF';

export interface Category {
  code: string; parentCode: string | null; labelFr: string; labelEn: string | null; description: string | null;
  source: Source; icon: string | null; color: string | null; sortOrder: number; tableCount: number; entryCount: number;
}

export interface ColumnDef {
  key: string; labelFr: string; labelEn?: string | null; role: Role; dataType: DataType; maxLength?: number | null;
  pattern?: string | null; required: boolean; cardinality?: string | null; xmlTag?: string | null; untded?: string | null;
  unit?: string | null; definition?: string | null; comment?: string | null; refTableCode?: string | null; sortOrder?: number;
}

export interface TableSummary {
  code: string; number: string | null; nameFr: string; nameEn: string | null; description: string | null; categoryCode: string;
  source: Source; bspPhases: Phase[]; status: string; contentType: 'SIMPLE' | 'COMPLEXE'; standards: string | null;
  entryCount: number; activeCount: number; completeness: number; updatedAt: string;
}

export interface TableDef extends Omit<TableSummary, 'contentType'> {
  standards: string | null; producer: string | null; updateAuthority: string | null; obtentionMode: string | null;
  updateMode: string | null; parentTableCode: string | null; metadata: Record<string, string>; sourceDocument: string | null;
  dataSource: string | null; columns: ColumnDef[]; version: number; createdAt: string; contentType: 'SIMPLE' | 'COMPLEXE';
  sqlView: string;
}

export interface Entry {
  code: string; labelFr: string | null; labelEn: string | null; parentCode: string | null; attributes: Record<string, unknown>;
  validFrom: string | null; validTo: string | null; status: 'ACTIVE' | 'INVALID'; version?: number; childCount?: number | null;
  createdAt?: string; updatedAt?: string;
}

export interface Attachment {
  id: number; fileName: string; contentType: string | null; sizeBytes: number; description: string | null; createdAt: string; createdBy: string;
}

export interface Page<T>{ items: T[]; total: number; page: number; size: number; }

export interface LookupItem { code: string; label: string; parentCode: string | null; }

export interface HistoryEvent {
  id: number; entityType: 'CATEGORY' | 'TABLE' | 'COLUMN' | 'ENTRY'; entityId: number; tableCode: string | null;
  entityCode: string | null; operation: 'CREATION' | 'MODIFICATION' | 'INVALIDATION' | 'REACTIVATION' | 'SUPPRESSION';
  changes: Record<string, [unknown, unknown]> | null; snapshot: Record<string, unknown>; author: string; channel: string;
  reason: string | null; correlationId: string | null; occurredAt: string;
}

export interface Activity { day: string; total: number; creations: number; modifications: number; invalidations: number; }

export interface ImportRun {
  id: string; tableCode: string; fileName: string | null; format: string; mode: string; total: number; created: number;
  updated: number; unchanged: number; invalidated: number; rejected: number; errors: string[]; author: string; channel: string;
  reason: string | null; occurredAt: string;
}

export interface ImportResult {
  importId?: string; tableCode: string; fileName: string; format: string; mode: 'MERGE' | 'REPLACE'; dryRun: boolean;
  total: number; created: number; updated: number; unchanged: number; invalidated: number; rejected: number;
  errors: string[]; warnings: string[]; mapping: Record<string, string>;
}

export interface Proposition {
  suggestedCode: string; suggestedName: string; format: string; rowCount: number; columns: ColumnDef[];
  sample: Record<string, string>[]; references: Record<string, string>;
}

export interface Dashboard {
  tables: number; tablesWithData: number; entries: number; activeEntries: number; invalidEntries: number;
  changesLast7Days: number; averageCompleteness: number; bsp: Record<Phase, number>; sources: Record<string, number>;
  largestTables: { code: string; nameFr: string; nameEn: string | null; entryCount: number }[];
  toComplete: { code: string; nameFr: string; nameEn: string | null; completeness: number }[];
  activity: Activity[]; recent: HistoryEvent[];
}

export interface MetadataField { key: string; labelFr: string; group: 'IDENTIFICATION' | 'CYCLE_DE_VIE' | 'ADMINISTRATIF'; longText: boolean; }

export interface SearchResults {
  query: string;
  tables: { code: string; nameFr: string; categoryCode: string; standards: string | null; entryCount: number }[];
  entries: { tableCode: string; tableName: string; code: string; labelFr: string | null; labelEn: string | null; status: string }[];
}

export const PHASES: { code: Phase; label: string; sub: string; processes: string[] }[] = [
  { code: 'BUY', label: 'Buy', sub: 'Acheter', processes: ['Identifier les partenaires', 'Établir l\'accord commercial', 'Commander'] },
  { code: 'SHIP', label: 'Ship', sub: 'Expédier', processes: ['Préparer l\'exportation', 'Exporter', 'Transporter', 'Préparer l\'importation', 'Importer'] },
  { code: 'PAY', label: 'Pay', sub: 'Payer', processes: ['Facturer', 'Payer', 'Rapprocher'] },
];

export const TYPES: { code: DataType; label: string }[] = [
  { code: 'STRING', label: 'Texte' }, { code: 'TEXT', label: 'Texte long' }, { code: 'INTEGER', label: 'Entier' },
  { code: 'DECIMAL', label: 'Décimal' }, { code: 'DATE', label: 'Date' }, { code: 'BOOLEAN', label: 'Oui / non' },
  { code: 'CODE_REF', label: 'Référence (table)' },
];

export const ROLES: { code: Role; label: string }[] = [
  { code: 'CODE', label: 'Code (clé)' }, { code: 'LABEL_FR', label: 'Libellé FR' }, { code: 'LABEL_EN', label: 'Libellé EN' },
  { code: 'ATTRIBUTE', label: 'Attribut' },
];

export const SOURCES: { code: Source; label: string }[] = [
  { code: 'INTERNATIONALE', label: 'Internationale' }, { code: 'REGIONALE', label: 'Régionale' }, { code: 'NATIONALE', label: 'Nationale' },
];

export const OPERATIONS: Record<string, string> = {
  CREATION: 'Création', MODIFICATION: 'Modification', INVALIDATION: 'Invalidation', REACTIVATION: 'Réactivation', SUPPRESSION: 'Suppression',
};
