import { Component, computed, inject, signal } from '@angular/core';
import { Api, Bie, CodeUnCefact, Instrument, LienInstrument, ListeCodes, MessageEchange, ModeleBsp, RolePartie, Untded } from '../core/api';
import { I18N } from '../core/i18n';
import { erreurs } from '../core/session';
import { Icon } from '../shared/icon';

type Abie = { code: string; den: string; definition: string; depth: number; root: boolean; source?: string };
type DetailAbie = { abie: { code: string; den: string; definition: string; source?: string }; properties: Bie[]; paths: string[]; restrictions?: { abie: string; path: string; level: string }[] };
type Resume = { family: string; release: string; abies: number; bbies: number; asbies: number; untded?: number; partyRoles?: number; restrictions?: number; codeLists?: number; codes?: number; instruments?: number; messages?: number };
type Vue = 'scrdm' | 'listes' | 'bsp' | 'instruments' | 'untded' | 'roles';

const PHASES: Record<string, string> = { BUY: 'Acheter', SHIP: 'Expédier', PAY: 'Payer' };
const FAMILLES: Record<string, string> = {
  UNCEFACT_REC: 'Recommandations UN/CEFACT', UNCEFACT_STD: 'Standards UN/CEFACT', ISO: 'Normes ISO', WCO: 'OMD', IMO_FAL: 'Formulaires FAL (OMI)',
  IPPC_ISPM: 'NIMP (CIPV)', CONVENTION: 'Conventions', AGREEMENT: 'Accords',
};
const GROUPES: Record<string, string> = { CUSTOMER: 'Client', SUPPLIER: 'Fournisseur', INTERMEDIARY: 'Intermédiaire', AUTHORITY: 'Autorité' };

/**
 * Dictionnaire du Supply Chain Reference Data Model UN/CEFACT D23B : catalogue des ABIE (filtré pendant la saisie),
 * recherche des BIE (DEN, propriété, définition), détail d'un ABIE (liste de codes des BBIE, restrictions contextuelles,
 * chemins depuis le message maître), listes de codes D23B, couche Buy-Ship-Pay, instruments de référence et messages.
 */
@Component({
  selector: 'nx-dictionnaire',
  imports: [Icon, ...I18N],
  template: `
    <div class="page-head"><div><h1>{{ 'Dictionnaire de données' | t }}</h1>
      @if (resume(); as r) { <p class="muted">UN/CEFACT {{ r.family }} {{ r.release }} — {{ '{0} ABIE, {1} BBIE, {2} ASBIE' | t: r.abies : r.bbies : r.asbies }}
        · {{ '{0} restrictions contextuelles' | t: r.restrictions ?? 0 }} · {{ '{0} listes de codes ({1} codes)' | t: r.codeLists ?? 0 : r.codes ?? 0 }}
        · UNTDED 2005 : {{ r.untded ?? 0 }} · {{ 'rôles' | t }} : {{ r.partyRoles ?? 0 }}</p> }</div></div>
    <div class="tabs" role="tablist">
      <button role="tab" class="tab" [class.on]="vue() === 'scrdm'" (click)="vue.set('scrdm')">SCRDM D23B</button>
      <button role="tab" class="tab" [class.on]="vue() === 'listes'" (click)="ouvrirListes()">{{ 'Listes de codes' | t }}</button>
      <button role="tab" class="tab" [class.on]="vue() === 'bsp'" (click)="ouvrirBsp()">{{ 'Modèle Buy-Ship-Pay' | t }}</button>
      <button role="tab" class="tab" [class.on]="vue() === 'instruments'" (click)="ouvrirInstruments()">{{ 'Instruments et messages' | t }}</button>
      <button role="tab" class="tab" [class.on]="vue() === 'untded'" (click)="ouvrirUntded()">UNTDED 2005 (ISO 7372)</button>
      <button role="tab" class="tab" [class.on]="vue() === 'roles'" (click)="ouvrirRoles()">{{ 'Rôles des parties (Buy-Ship-Pay)' | t }}</button>
    </div>
    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }

    @if (vue() === 'untded') {
      <div class="card pad row"><nx-icon name="search" />
        <input class="input grow" [value]="qu()" (input)="qu.set($any($event.target).value)" (keydown.enter)="chercherUntded()" [placeholder]="'Tag (ex. 3132), nom ou description…' | t" />
        <button class="btn" (click)="chercherUntded()">{{ 'Rechercher' | t }}</button></div>
      <p class="muted small">{{ 'UN Trade Data Elements Directory, édition 2005 : référence « UNTDED » des champs du formulaire (proposée par le Data Agent à partir du BBIE SCRDM, contrôlée par le Compliance Agent).' | t }}</p>
      <div class="card scroll-x liste">
        <table class="datagrid"><thead><tr><th>Tag</th><th>{{ 'Nom normalisé' | t }}</th><th>{{ 'Description' | t }}</th><th>{{ 'Repr.' | t }}</th></tr></thead>
          <tbody>@for (e of untded(); track e.tag) {
            <tr><td class="mono"><b>{{ e.tag }}</b></td><td>{{ e.name }}</td><td class="small">{{ e.description }}</td><td class="mono small">{{ e.representation }}</td></tr>
          } @empty { <tr><td colspan="4" class="empty small">{{ 'Aucun élément ne correspond.' | t }}</td></tr> }</tbody></table>
      </div>
    }

    @if (vue() === 'roles') {
      <p class="muted small">{{ 'Rôles des parties et lieux (UN/CEFACT RSM SCRDM v1.0.0.2, tables 1 et 2, complétés des acteurs du BSP) : le Data Agent reconnaît le rôle dans le libellé du champ et rattache son chemin BIE à la partie correspondante.' | t }}</p>
      <div class="card scroll-x">
        <table class="datagrid"><thead><tr><th>{{ 'Rôle' | t }}</th><th>{{ 'Catégorie' | t }}</th><th>{{ 'Définition' | t }}</th><th>{{ 'Chemin BIE' | t }}</th><th>{{ 'Correspondances (RSM)' | t }}</th></tr></thead>
          <tbody>@for (r of roles(); track r.code) {
            <tr><td><b>{{ r.labelFr }}</b><div class="muted small">{{ r.labelEn }}</div></td>
              <td><span class="chip">{{ r.category }}</span>@if (r.actorGroup) { <div class="small muted">{{ groupe(r.actorGroup) | t }}</div> }
                @if (r.functionCode) { <div class="small mono" [title]="'Code fonction UNTDID 3035' | t">3035 : {{ r.functionCode }}</div> }</td>
              <td class="small">{{ r.definition }}</td><td><code class="chemin small">{{ r.biePath }}</code></td>
              <td class="small muted">{{ r.mappedTo }}<div>{{ 'Termes' | t }} : {{ r.keywords.join(', ') }}</div></td></tr>
          }</tbody></table>
      </div>
    }

    @if (vue() === 'listes') {
      <p class="muted small">{{ 'Listes de codes du BSP-RDM D23B référencées par les types de données des BBIE (UNTDID, Recommandations UN/CEFACT, ISO, IANA). Une liste ouverte n\\'énumère pas ses valeurs.' | t }}</p>
      <div class="grid k2">
        <div class="card stack pad">
          <input class="input" [value]="ql()" (input)="ql.set($any($event.target).value)" [placeholder]="'Filtrer les listes (numéro UNTDID, Rec., nom)…' | t" />
          <div class="liste scroll-x">
            <table class="datagrid"><thead><tr><th>{{ 'Liste' | t }} ({{ listesFiltrees().length }})</th><th>{{ 'Codes' | t }}</th></tr></thead>
              <tbody>@for (l of listesFiltrees(); track l.id) {
                <tr (click)="ouvrirListe(l.id)" [class.sel]="liste()?.list?.id === l.id"><td><b>{{ l.name }}</b><div class="muted small mono">{{ l.id }}@if (l.listRef) { · {{ l.listRef }} }</div></td>
                  <td>@if (l.closed) { {{ l.entries }} } @else { <span class="chip">{{ 'ouverte' | t }}</span> }</td></tr>
              }</tbody></table>
          </div>
        </div>
        @if (liste(); as l) {
          <div class="card pad stack">
            <h2>{{ l.list.name }}</h2>
            <p class="muted small">{{ l.list.description }}</p>
            <div class="row small"><span class="chip">{{ l.list.agency }}</span>@if (l.list.listRef) { <span class="chip info">{{ l.list.listRef }}</span> }
              @if (l.list.listName) { <span>{{ l.list.listName }}</span> }@if (l.list.version) { <span class="muted">v{{ l.list.version }}</span> }
              @if (l.list.issueDate) { <span class="muted">{{ l.list.issueDate }}</span> }</div>
            @if (l.list.closed) {
              <div class="row"><nx-icon name="search" /><input class="input grow" [value]="qc()" (input)="qc.set($any($event.target).value)" (keydown.enter)="ouvrirListe(l.list.id)" [placeholder]="'Code ou libellé…' | t" />
                <button class="btn" (click)="ouvrirListe(l.list.id)">{{ 'Rechercher' | t }}</button></div>
              <div class="liste scroll-x"><table class="datagrid"><thead><tr><th>{{ 'Code' | t }}</th><th>{{ 'Libellé' | t }}</th></tr></thead>
                <tbody>@for (c of l.codes; track c.code) {
                  <tr><td class="mono"><b>{{ c.code }}</b></td><td>{{ c.name }}@for (a of c.altNames; track $index) { <div class="muted small">{{ a }}</div> }</td></tr>
                } @empty { <tr><td colspan="2" class="empty small">{{ 'Aucun code ne correspond.' | t }}</td></tr> }</tbody></table></div>
            } @else { <p class="muted small">{{ 'Liste ouverte : les valeurs ne sont pas énumérées dans la publication D23B.' | t }}</p> }
          </div>
        }
      </div>
    }

    @if (vue() === 'bsp') { @if (bsp(); as m) {
      <p class="muted small">{{ 'Modèle de référence de la chaîne logistique internationale (ISCRM, BSP-RDM) : domaines, processus « Acheter-Expédier-Payer », entités de haut niveau ancrées dans le SCRDM et leurs règles de gestion.' | t }}</p>
      <div class="grid k4">@for (d of m.domains; track d.code) {
        <div class="card pad"><h3>{{ d.labelFr }}</h3><p class="muted small">{{ d.description }}</p></div>
      }</div>
      <h2>{{ 'Processus' | t }}</h2>
      <div class="card scroll-x"><table class="datagrid"><thead><tr><th>{{ 'Phase' | t }}</th><th>{{ 'Processus' | t }}</th><th>{{ 'Domaine' | t }}</th><th>{{ 'Documents' | t }}</th></tr></thead>
        <tbody>@for (p of m.processes; track p.code) {
          <tr><td><span class="chip info">{{ phase(p.phase) | t }}</span></td><td><b>{{ p.labelFr }}</b><div class="muted small">{{ p.description }}</div></td>
            <td class="small">{{ domaine(m, p.domain) }}</td><td class="small">{{ p.documents.join(' · ') }}</td></tr>
        }</tbody></table></div>
      <h2>{{ 'Entités de haut niveau' | t }}</h2>
      <div class="card scroll-x"><table class="datagrid"><thead><tr><th>{{ 'Entité' | t }}</th><th>{{ 'Définition' | t }}</th><th>{{ 'Ancrage SCRDM' | t }}</th><th>{{ 'Règles' | t }}</th></tr></thead>
        <tbody>@for (e of m.entities; track e.code) {
          <tr><td><b>{{ e.labelFr }}</b><div class="muted small">{{ e.labelEn }}</div></td>
            <td class="small">{{ e.definition }}@if (e.note) { <div class="muted">{{ e.note }}</div> }</td>
            <td><button class="btn sm ghost" (click)="vue.set('scrdm'); ouvrir(e.abie)">→ {{ e.abie }}</button><code class="chemin small">{{ e.biePath }}</code>
              @for (k of e.keyPaths; track k) { <div class="mono small muted chemin-cle">{{ k }}</div> }</td>
            <td class="small">@for (r of relationsDe(m, e.code); track r.to) { <div><span class="mono">{{ r.cardinality }}</span> {{ libelleEntite(m, r.to) }} — <span class="muted">{{ r.rule }}</span></div> }
              @for (p of partiesDe(m, e.code); track p.partyRole) { <div><span class="mono">{{ p.cardinality }}</span> {{ p.partyRole }} — <span class="muted">{{ p.rule }}</span></div> }</td></tr>
        }</tbody></table></div>
    } }

    @if (vue() === 'instruments') { @if (instr(); as ins) {
      <div class="card pad row">
        <select class="input" [value]="famille()" (change)="famille.set($any($event.target).value)">
          <option value="">{{ 'Toutes les familles' | t }}</option>
          @for (f of familles; track f[0]) { <option [value]="f[0]">{{ f[1] | t }}</option> }
        </select>
        <input class="input grow" [value]="qi()" (input)="qi.set($any($event.target).value)" [placeholder]="'Filtrer (numéro, titre, cote)…' | t" />
      </div>
      <div class="card scroll-x liste"><table class="datagrid"><thead><tr><th>{{ 'Instrument' | t }}</th><th>{{ 'Cote / date' | t }}</th><th>{{ 'Ce qu\\'il normalise dans le modèle' | t }}</th></tr></thead>
        <tbody>@for (i of instrumentsFiltres(); track i.code) {
          <tr><td><span class="chip">{{ famille_(i.family) | t }}</span> <b>{{ i.titleFr }}</b>@if (i.summaryFr) { <div class="muted small">{{ i.summaryFr }}</div> }
              @if (i.url) { <a class="small" [href]="i.url" target="_blank" rel="noopener">{{ i.url }}</a> }</td>
            <td class="small mono">{{ i.docRef }}<div>{{ i.issued }}</div></td>
            <td class="small">@for (l of liensDe(ins.links, i.code); track l.targetKind + l.target) {
              <div><span class="chip">{{ l.targetKind }}</span>
                @if (l.targetKind === 'CODE_LIST') { <button class="btn sm ghost" (click)="ouvrirListe(l.target, true)">{{ l.target }}</button> }
                @else if (l.targetKind === 'ABIE') { <button class="btn sm ghost" (click)="vue.set('scrdm'); ouvrir(l.target)">{{ l.target }}</button> }
                @else { <span class="mono">{{ l.target }}</span> }
                @if (l.note) { <span class="muted"> — {{ l.note }}</span> }</div>
            }</td></tr>
        } @empty { <tr><td colspan="3" class="empty small">{{ 'Aucun instrument ne correspond.' | t }}</td></tr> }</tbody></table></div>
      <h2>{{ 'Messages d\\'échange' | t }}</h2>
      <div class="card scroll-x liste"><table class="datagrid"><thead><tr><th>{{ 'Message' | t }}</th><th>{{ 'Norme' | t }}</th><th>{{ 'Phase' | t }}</th><th>{{ 'Description' | t }}</th></tr></thead>
        <tbody>@for (m of messages(); track m.code) {
          <tr><td><b>{{ m.nameFr ?? m.name }}</b><div class="muted small mono">{{ m.code }}</div></td><td class="small">{{ m.standard }}@if (m.kind === 'MASTER') { <span class="chip info">{{ 'maître' | t }}</span> }</td>
            <td>@if (m.phase) { <span class="chip">{{ phase(m.phase) | t }}</span> }</td><td class="small">{{ m.description }}</td></tr>
        }</tbody></table></div>
    } }

    @if (vue() === 'scrdm') {
    <div class="card pad row"><nx-icon name="search" />
      <input class="input grow" [value]="q()" (input)="saisir($any($event.target).value)" (keydown.enter)="chercher()" [placeholder]="'Nom normalisé (DEN), propriété ou définition…' | t" />
      <button class="btn" (click)="chercher()">{{ 'Rechercher les propriétés' | t }}</button>
      @if (resultats()) { <button class="btn ghost" (click)="saisir('')">{{ 'Catalogue des ABIE' | t }}</button> }</div>
    <div class="grid k2">
      <div class="card scroll-x liste">
        @if (resultats(); as res) {
          <table class="datagrid"><thead><tr><th>DEN</th><th>{{ 'Type' | t }}</th><th>{{ 'Card.' | t }}</th></tr></thead>
            <tbody>@for (b of res; track b.abie + b.property) {
              <tr (click)="ouvrir(b.abie)" [class.sel]="abie()?.abie?.code === b.abie"><td><b>{{ b.den }}</b><div class="muted small mono">{{ b.abie }}/{{ b.property }}</div></td><td>{{ b.kind }}</td><td>{{ b.cardinality }}</td></tr>
            } @empty { <tr><td colspan="3" class="empty small">{{ 'Aucune propriété ne correspond.' | t }}</td></tr> }</tbody></table>
        } @else {
          <table class="datagrid"><thead><tr><th>{{ 'ABIE' | t }} ({{ abiesFiltres().length }})</th><th>{{ 'Niveau' | t }}</th></tr></thead>
            <tbody>@for (a of abiesFiltres(); track a.code) {
              <tr (click)="ouvrir(a.code)" [class.sel]="abie()?.abie?.code === a.code"><td><b>{{ a.den }}</b><div class="muted small mono">{{ a.code }}</div></td>
                <td>@if (a.root) { <span class="chip info">{{ 'racine' | t }}</span> } @else { {{ a.depth }} }</td></tr>
            } @empty { <tr><td colspan="2" class="empty small">{{ chargement() ? ('Chargement…' | t) : ('Aucun ABIE ne correspond ; « Rechercher les propriétés » cherche aussi dans les BBIE et ASBIE.' | t) }}</td></tr> }</tbody></table>
        }
      </div>
      @if (abie(); as a) {
        <div class="card pad stack">
          <h2>{{ a.abie.den }}</h2><p class="muted small">{{ a.abie.definition }}</p>
          @if (a.abie.source === 'BSP') { <p class="small"><span class="chip">BSP</span> {{ 'ABIE de la bibliothèque Buy-Ship-Pay D23B repris sans restriction par le SCRDM.' | t }}</p> }
          <h3>{{ 'Chemins depuis la racine' | t }}</h3>
          @for (p of a.paths; track p) { <code class="chemin">{{ p }}</code> } @empty { <span class="muted small">{{ 'Racine du message maître.' | t }}</span> }
          @if (a.restrictions?.length) {
            <h3>{{ 'Restrictions contextuelles' | t }} ({{ a.restrictions!.length }})</h3>
            <p class="muted small">{{ 'Chemins exclus sous cet ABIE dans le SCRDM (le Compliance Agent les refuse).' | t }}</p>
            <div class="restr">@for (r of a.restrictions; track r.path) { <code class="small" [class.muted]="r.level === 'SUPPLEMENTARY'">{{ r.path }}</code> }</div>
          }
          <h3>{{ 'Propriétés' | t }}</h3>
          <div class="props">@for (b of a.properties; track b.property) {
            <div class="prop"><span class="chip" [class.info]="b.kind === 'ASBIE'">{{ b.kind }}</span> <b class="mono small">{{ b.property }}</b> <span class="muted small">{{ b.cardinality }}</span>
              @if (b.target) { <button class="btn sm ghost" (click)="ouvrir(b.target)">→ {{ b.target }}</button> }
              @if (b.dataType) { <span class="muted small mono">{{ b.dataType }}</span> }
              @if (b.codeList) { <button class="btn sm ghost" (click)="ouvrirListe(b.codeList, true)">{{ 'liste' | t }} {{ b.codeList }}</button> }
              @if (b.unitCodeList) { <button class="btn sm ghost" (click)="ouvrirListe(b.unitCodeList, true)">{{ 'unités' | t }} {{ b.unitCodeList }}</button> }
              <div class="muted small">{{ b.den }}</div></div>
          }</div>
        </div>
      }
    </div>
    }
  `,
  styles: [`:host { display: flex; flex-direction: column; gap: 16px; } .chemin { padding: 5px 8px; background: var(--surface-2); border-radius: 8px; overflow-wrap: anywhere; }
    .chemin-cle { overflow-wrap: anywhere; margin-top: 4px; }
    .liste { max-height: 75vh; overflow-y: auto; } tr { cursor: pointer; } tr.sel td { background: var(--surface-2); }
    .restr { display: flex; flex-wrap: wrap; gap: 6px; max-height: 22vh; overflow-y: auto; } .restr code { overflow-wrap: anywhere; }
    .props { display: flex; flex-direction: column; gap: 6px; max-height: 60vh; overflow-y: auto; } .prop { border-bottom: 1px solid var(--line); padding-bottom: 6px; }`],
})
export class DictionnairePage {
  private readonly api = inject(Api);
  protected readonly q = signal('');
  protected readonly abies = signal<Abie[]>([]);
  protected readonly chargement = signal(true);
  /** Résultats de la recherche des propriétés ; null = affichage du catalogue des ABIE. */
  protected readonly resultats = signal<Bie[] | null>(null);
  protected readonly resume = signal<Resume | null>(null);
  protected readonly abie = signal<DetailAbie | null>(null);
  protected readonly erreur = signal<string | null>(null);
  protected readonly vue = signal<Vue>('scrdm');
  protected readonly qu = signal('');
  protected readonly untded = signal<Untded[]>([]);
  protected readonly roles = signal<RolePartie[]>([]);
  protected readonly listes = signal<ListeCodes[]>([]);
  protected readonly ql = signal('');
  protected readonly qc = signal('');
  protected readonly liste = signal<{ list: ListeCodes; codes: CodeUnCefact[] } | null>(null);
  protected readonly bsp = signal<ModeleBsp | null>(null);
  protected readonly instr = signal<{ instruments: Instrument[]; links: LienInstrument[] } | null>(null);
  protected readonly messages = signal<MessageEchange[]>([]);
  protected readonly famille = signal('');
  protected readonly qi = signal('');
  protected readonly familles = Object.entries(FAMILLES);

  protected readonly abiesFiltres = computed(() => {
    const q = this.q().trim().toLowerCase();
    const tri = [...this.abies()].sort((a, b) => Number(b.root) - Number(a.root) || a.den.localeCompare(b.den));
    return q ? tri.filter(a => (a.code + ' ' + a.den + ' ' + (a.definition ?? '')).toLowerCase().includes(q)) : tri;
  });

  protected readonly listesFiltrees = computed(() => {
    const q = this.ql().trim().toLowerCase();
    return q ? this.listes().filter(l => (l.id + ' ' + l.name + ' ' + (l.listRef ?? '') + ' ' + (l.listName ?? '')).toLowerCase().includes(q)) : this.listes();
  });

  protected readonly instrumentsFiltres = computed(() => {
    const q = this.qi().trim().toLowerCase(), f = this.famille();
    return (this.instr()?.instruments ?? []).filter(i => (!f || i.family === f)
      && (!q || (i.code + ' ' + i.titleFr + ' ' + (i.docRef ?? '') + ' ' + (i.summaryFr ?? '')).toLowerCase().includes(q)));
  });

  constructor() {
    this.api.rdm().subscribe({ next: r => this.resume.set(r), error: e => this.erreur.set(erreurs(e).join(' ')) });
    this.api.abies().subscribe({
      next: a => {
        this.abies.set(a);
        this.chargement.set(false);
        const racine = a.find(x => x.root);
        if (racine) this.ouvrir(racine.code);
      },
      error: e => { this.chargement.set(false); this.erreur.set(erreurs(e).join(' ')); },
    });
  }

  protected phase(p: string): string { return PHASES[p] ?? p; }
  protected famille_(f: string): string { return FAMILLES[f] ?? f; }
  protected groupe(g: string): string { return GROUPES[g] ?? g; }
  protected domaine(m: ModeleBsp, code: string): string { return m.domains.find(d => d.code === code)?.labelFr ?? code; }
  protected libelleEntite(m: ModeleBsp, code: string): string { return m.entities.find(e => e.code === code)?.labelFr ?? code; }
  protected relationsDe(m: ModeleBsp, code: string) { return m.relations.filter(r => r.from === code); }
  protected partiesDe(m: ModeleBsp, code: string) { return m.parties.filter(p => p.entity === code); }
  protected liensDe(liens: LienInstrument[], code: string): LienInstrument[] { return liens.filter(l => l.instrument === code); }

  protected ouvrirUntded(): void {
    this.vue.set('untded');
    if (!this.untded().length) this.chercherUntded();
  }

  protected chercherUntded(): void {
    this.api.untded(this.qu(), 300).subscribe({ next: r => this.untded.set(r), error: e => this.erreur.set(erreurs(e).join(' ')) });
  }

  protected ouvrirRoles(): void {
    this.vue.set('roles');
    if (!this.roles().length) this.api.rolesParties().subscribe({ next: r => this.roles.set(r), error: e => this.erreur.set(erreurs(e).join(' ')) });
  }

  protected ouvrirListes(): void {
    this.vue.set('listes');
    if (!this.listes().length) this.api.listesCodes().subscribe({ next: r => this.listes.set(r), error: e => this.erreur.set(erreurs(e).join(' ')) });
  }

  /** Ouvre une liste de codes ; {@code nouvelle} réinitialise la recherche (navigation depuis un BBIE ou un instrument). */
  protected ouvrirListe(id: string, nouvelle = false): void {
    if (nouvelle) { this.qc.set(''); this.ouvrirListes(); }
    this.api.listeCodes(id, this.qc(), 1000).subscribe({ next: r => this.liste.set(r), error: e => this.erreur.set(erreurs(e).join(' ')) });
  }

  protected ouvrirBsp(): void {
    this.vue.set('bsp');
    if (!this.bsp()) this.api.modeleBsp().subscribe({ next: r => this.bsp.set(r), error: e => this.erreur.set(erreurs(e).join(' ')) });
  }

  protected ouvrirInstruments(): void {
    this.vue.set('instruments');
    if (!this.instr()) this.api.instruments().subscribe({ next: r => this.instr.set(r), error: e => this.erreur.set(erreurs(e).join(' ')) });
    if (!this.messages().length) this.api.messagesEchange().subscribe({ next: r => this.messages.set(r), error: e => this.erreur.set(erreurs(e).join(' ')) });
  }

  protected saisir(v: string): void {
    this.q.set(v);
    this.resultats.set(null);
  }

  protected chercher(): void {
    if (!this.q().trim()) { this.resultats.set(null); return; }
    this.api.rechercherBie(this.q(), 100).subscribe({ next: r => this.resultats.set(r), error: e => this.erreur.set(erreurs(e).join(' ')) });
  }

  protected ouvrir(code: string): void {
    this.api.abie(code).subscribe({ next: a => this.abie.set(a), error: e => this.erreur.set(erreurs(e).join(' ')) });
  }
}
