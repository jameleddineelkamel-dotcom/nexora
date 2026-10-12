import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Api, Deploiement, Incident, Lieu, Membre, Message, Publiee, Unite } from '../core/api';
import { AuthService } from '../core/auth.service';
import { I18N, I18n } from '../core/i18n';
import { erreurs } from '../core/session';
import { Icon } from '../shared/icon';
import { MessageDrawer } from './dossier/message-drawer';

type Onglet = 'procedures' | 'cotation' | 'listes' | 'incidents' | 'vues' | 'reception';

const EXEMPLE_XML = `<?xml version="1.0" encoding="UTF-8"?>
<PaymentConfirmation>
  <paymentNoticeNumber>AVCM2026IFI000001</paymentNoticeNumber>
  <paymentReferenceText>QT-BANQUE-000123</paymentReferenceText>
  <paidAmount>46500</paidAmount>
  <currencyCode>XAF</currencyCode>
  <paidAt>2026-10-12T10:15:00+01:00</paidAt>
  <bankCode>BICEC</bankCode>
</PaymentConfirmation>`;

/**
 * Administration des e-Services : déploiement des procédures publiées par le studio (paquet vérifié, BPMN déployé dans
 * Operaton, DRL compilé, vues métier), paramétrage de la cotation (lieux de traitement, unités, agents), listes de
 * codes projetées du Référentiel Commun, incidents du moteur, vues métier, réception de messages partenaires.
 */
@Component({
  selector: 'nx-administration',
  imports: [DatePipe, Icon, MessageDrawer, ...I18N],
  template: `
    <div class="page-head">
      <div><h1>{{ 'Administration des e-Services' | t }}</h1>
        <p class="muted">{{ 'Le Runtime n\\'exécute que des procédures publiées et approuvées dans le studio : le paquet est vérifié par son empreinte avant déploiement.' | t }}</p></div>
    </div>
    <div class="tabs">
      @for (o of onglets; track o.code) { <button class="tab" [class.on]="onglet() === o.code" (click)="ouvrir(o.code)"><nx-icon [name]="o.icone" [size]="16" /> {{ o.libelle | t }}</button> }
    </div>
    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
    @if (info()) { <div class="alert ok">{{ info() }}</div> }

    @switch (onglet()) {
      @case ('procedures') {
        <div class="card pad stack">
          <div class="between"><h2>{{ 'Procédures publiées dans le studio' | t }}</h2><button class="btn sm" (click)="chargerProcedures()"><nx-icon name="refresh" [size]="15" /> {{ 'Actualiser' | t }}</button></div>
          <div class="scroll-x"><table class="datagrid">
            <thead><tr><th>{{ 'Procédure' | t }}</th><th>{{ 'Version publiée' | t }}</th><th>{{ 'Publiée par' | t }}</th><th>{{ 'Déployée' | t }}</th><th></th></tr></thead>
            <tbody>
              @for (p of publiees(); track p.code) {
                <tr><td><b>{{ p.labelFr | lib: p.labelEn }}</b><div class="muted small mono">{{ p.code }} · {{ p.packageHash.slice(0, 12) }}…</div></td>
                  <td>v{{ p.versionNo }} <span class="muted small">{{ p.publishedAt | date: 'dd/MM/yyyy HH:mm' }}</span></td><td>{{ p.publishedBy }}</td>
                  <td>@if (p.deployedVersionNo) { <span class="chip" [class.ok]="p.upToDate" [class.warn]="!p.upToDate">v{{ p.deployedVersionNo }} {{ (p.upToDate ? 'à jour' : 'ancienne') | t }}</span> } @else { <span class="chip">{{ 'non déployée' | t }}</span> }</td>
                  <td>@if (!p.upToDate) { <button class="btn primary sm" (click)="deployer(p.code)" [disabled]="occupe()"><nx-icon name="upload" [size]="15" /> {{ 'Déployer' | t }}</button> }</td></tr>
              } @empty { <tr><td colspan="5" class="empty">{{ 'Aucune procédure publiée (ou studio indisponible).' | t }}</td></tr> }
            </tbody></table></div>
          <h3 class="sep">{{ 'Déploiements' | t }}</h3>
          <div class="scroll-x"><table class="datagrid">
            <thead><tr><th>{{ 'Procédure' | t }}</th><th>{{ 'Version' | t }}</th><th>{{ 'Définition Operaton' | t }}</th><th>{{ 'Déployée le' | t }}</th><th>{{ 'État' | t }}</th></tr></thead>
            <tbody>@for (d of deploiements(); track d.id) {
              <tr><td>{{ d.labelFr }}</td><td>v{{ d.versionNo }}</td><td class="mono small">{{ d.processDefinitionId }}</td>
                <td class="small">{{ d.deployedAt | date: 'dd/MM/yyyy HH:mm' }} · {{ d.deployedBy }}</td><td><span class="chip" [class.ok]="d.active">{{ (d.active ? 'active' : 'remplacée') | t }}</span></td></tr>
            }</tbody></table></div>
        </div>
      }
      @case ('cotation') {
        <div class="grid k2">
          <div class="card pad stack">
            <h2>{{ 'Lieux de traitement' | t }}</h2>
            <p class="muted small">{{ 'Chaque lieu proposé dans le formulaire est rattaché à une unité administrative disposant d\\'au moins un agent de traitement et d\\'un signataire (paramétrage de la cotation).' | t }}</p>
            <table class="datagrid">
              <thead><tr><th>{{ 'Lieu' | t }}</th><th>{{ 'Unité' | t }}</th><th>{{ 'Agents' | t }}</th><th>{{ 'Actif' | t }}</th></tr></thead>
              <tbody>@for (l of lieux(); track l.code) {
                <tr><td><b>{{ l.labelFr }}</b> <span class="mono muted small">{{ l.code }}</span></td>
                  <td><select class="input" (change)="changerUnite(l, $any($event.target).value)">@for (u of unites(); track u.code) { <option [value]="u.code" [selected]="u.code === l.unitCode">{{ u.code }}</option> }</select></td>
                  <td class="small" [class.alerte]="l.active && (!l.agents || !l.signataires)">{{ l.agents }} / {{ l.signataires }}</td>
                  <td><input type="checkbox" [checked]="l.active" (change)="basculerLieu(l)" /></td></tr>
              }</tbody>
            </table>
          </div>
          <div class="card pad stack">
            <h2>{{ 'Unités et agents' | t }}</h2>
            <div class="form-grid ajout">
              <div class="field"><label for="u">{{ 'Unité' | t }}</label><select id="u" class="input" (change)="nouveau.update(n => ({ ...n, unitCode: $any($event.target).value }))">
                @for (u of unites(); track u.code) { <option [value]="u.code" [selected]="u.code === nouveau().unitCode">{{ u.code }} — {{ u.label }}</option> }</select></div>
              <div class="field"><label for="n">{{ 'Identifiant (compte SSO)' | t }}</label><input id="n" class="input mono" [value]="nouveau().username" (input)="nouveau.update(n => ({ ...n, username: $any($event.target).value.toLowerCase() }))" /></div>
              <div class="field"><label for="fn">{{ 'Nom complet' | t }}</label><input id="fn" class="input" [value]="nouveau().fullName" (input)="nouveau.update(n => ({ ...n, fullName: $any($event.target).value }))" /></div>
              <div class="field"><label for="r">{{ 'Rôle' | t }}</label><select id="r" class="input" (change)="nouveau.update(n => ({ ...n, role: $any($event.target).value }))">
                <option value="TRAITEMENT">{{ 'Agent de traitement' | t }}</option><option value="SIGNATAIRE">{{ 'Signataire' | t }}</option></select></div>
              <div class="full"><button class="btn primary" (click)="ajouterMembre()" [disabled]="!nouveau().username"><nx-icon name="plus" /> {{ 'Rattacher' | t }}</button></div>
            </div>
            @for (u of unitesAvecMembres(); track u.code) {
              <div class="unite"><b>{{ u.label }}</b> <span class="mono muted small">{{ u.code }}</span>
                @for (m of u.membres; track m.username + m.role) {
                  <div class="membre small"><span class="chip" [class.accent]="m.role === 'SIGNATAIRE'">{{ (m.role === 'SIGNATAIRE' ? 'Signataire' : 'Traitement') | t }}</span>
                    <span class="grow">{{ m.fullName ?? m.username }} <span class="mono muted">{{ m.username }}</span> · {{ '{0} dossier(s) actif(s)' | t: m.openCases }}</span>
                    <label class="check"><input type="checkbox" [checked]="m.active" (change)="basculerMembre(m)" /> {{ 'actif' | t }}</label></div>
                }</div>
            }
          </div>
        </div>
      }
      @case ('listes') {
        <div class="card pad stack">
          <div class="between"><h2>{{ 'Listes de codes projetées du Référentiel Commun' | t }}</h2>
            <div class="row">@for (d of actifs(); track d.id) {
              <button class="btn sm" (click)="rafraichir(d.procedureCode)" [disabled]="occupe()"><nx-icon name="refresh" [size]="15" /> {{ 'Recharger {0}' | t: d.procedureCode }}</button>
            }</div></div>
          <table class="datagrid"><thead><tr><th>{{ 'Table' | t }}</th><th>{{ 'Codes' | t }}</th><th>{{ 'Chargée le' | t }}</th></tr></thead>
            <tbody>@for (l of listes(); track l.table) { <tr><td class="mono">{{ l.table }}</td><td>{{ l.entries | num }}</td><td class="small">{{ l.loadedAt | date: 'dd/MM/yyyy HH:mm' }} · {{ l.loadedBy }}</td></tr> }
              @empty { <tr><td colspan="3" class="empty">{{ 'Aucune liste chargée : déployez une procédure.' | t }}</td></tr> }</tbody></table>
        </div>
      }
      @case ('incidents') {
        <div class="card pad stack">
          <h2>{{ 'Incidents du moteur de workflow' | t }}</h2>
          <p class="muted small">{{ 'Ex. cotation impossible faute d\\'agent actif : corrigez le paramétrage puis relancez.' | t }}</p>
          <table class="datagrid"><thead><tr><th>{{ 'Date' | t }}</th><th>{{ 'Dossier' | t }}</th><th>{{ 'Étape' | t }}</th><th>{{ 'Message' | t }}</th><th></th></tr></thead>
            <tbody>@for (i of incidents(); track i.id) {
              <tr><td class="small">{{ i.time | date: 'dd/MM HH:mm' }}</td><td class="mono">{{ i.dossierNo }}</td><td class="mono small">{{ i.activityId }}</td><td class="small">{{ i.message }}</td>
                <td><button class="btn sm" (click)="relancer(i)"><nx-icon name="play" [size]="15" /> {{ 'Relancer' | t }}</button></td></tr>
            } @empty { <tr><td colspan="5" class="empty">{{ 'Aucun incident.' | t }}</td></tr> }</tbody></table>
        </div>
      }
      @case ('vues') {
        <div class="card pad stack">
          <h2>{{ 'Vues métier des procédures' | t }}</h2>
          <p class="muted small">{{ 'Créées au déploiement à partir du paquet : elles exposent les dossiers selon le modèle de données métier (une vue par procédure déployée : demande, groupes répétitifs, avis de paiement, livrable).' | t }}</p>
          <div class="row">@for (v of vues(); track v.schema + v.view) { <button class="btn sm" [class.primary]="vueOuverte() === v.schema + '.' + v.view" (click)="ouvrirVue(v.schema, v.view)">{{ v.schema }}.{{ v.view }}</button> }</div>
          @if (lignesVue().length) {
            <div class="scroll-x"><table class="datagrid"><thead><tr>@for (c of colonnesVue(); track c) { <th>{{ c }}</th> }</tr></thead>
              <tbody>@for (l of lignesVue(); track $index) { <tr>@for (c of colonnesVue(); track c) { <td class="small">{{ l[c] }}</td> }</tr> }</tbody></table></div>
          } @else if (vueOuverte()) { <div class="muted small">{{ 'Vue vide.' | t }}</div> }
        </div>
      }
      @case ('reception') {
        <div class="card pad stack">
          <h2>{{ 'Réception d\\'un message partenaire' | t }}</h2>
          <p class="muted small">{{ 'Simule l\\'envoi par une banque ou une administration d\\'un message du catalogue (POST /api/v1/messages/inbound). La clé d\\'idempotence élimine les doublons.' | t }}</p>
          <div class="form-grid">
            <div class="field"><label for="mc">{{ 'Message du catalogue' | t }}</label><input id="mc" class="input mono" [value]="recCode()" (input)="recCode.set($any($event.target).value)" /></div>
            <div class="field"><label for="mt">{{ 'Type de contenu' | t }}</label><select id="mt" class="input" (change)="recType.set($any($event.target).value)">
              @for (x of ['application/xml', 'application/json', 'text/csv', 'text/plain']; track x) { <option [value]="x">{{ x }}</option> }</select></div>
            <div class="field"><label for="mk">Idempotency-Key</label><input id="mk" class="input mono" [value]="recCle()" (input)="recCle.set($any($event.target).value)" /></div>
            <div class="field"><label for="mp">{{ 'Partenaire émetteur' | t }}</label><input id="mp" class="input mono" [value]="recPartenaire()" (input)="recPartenaire.set($any($event.target).value)" /></div>
            <div class="field full"><label for="mb">{{ 'Contenu' | t }}</label><textarea id="mb" class="input code" style="min-height: 200px" [value]="recCorps()" (input)="recCorps.set($any($event.target).value)"></textarea></div>
          </div>
          <div class="row"><button class="btn primary" (click)="recevoir()" [disabled]="occupe()"><nx-icon name="download" /> {{ 'Envoyer au guichet unique' | t }}</button></div>
        </div>
      }
    }
    @if (message(); as m) { <nx-message-drawer [message]="m" (fermer)="message.set(null)" /> }
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 16px; }
    .datagrid tbody tr { cursor: default; }
    .alerte { color: var(--danger); font-weight: 700; }
    .unite { padding: 10px 0; border-top: 1px solid var(--line); display: flex; flex-direction: column; gap: 6px; }
    .membre { display: flex; gap: 8px; align-items: center; }
    .ajout { padding-bottom: 6px; }
  `],
})
export class AdministrationPage {
  private readonly api = inject(Api);
  private readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthService);
  protected readonly onglets: { code: Onglet; libelle: string; icone: string }[] = [
    { code: 'procedures', libelle: 'Procédures', icone: 'workflow' }, { code: 'cotation', libelle: 'Cotation', icone: 'users' },
    { code: 'listes', libelle: 'Listes de codes', icone: 'database' }, { code: 'incidents', libelle: 'Incidents', icone: 'alert' },
    { code: 'vues', libelle: 'Vues métier', icone: 'table' }, { code: 'reception', libelle: 'Réception de messages', icone: 'download' }];
  protected readonly onglet = signal<Onglet>('procedures');
  protected readonly publiees = signal<Publiee[]>([]);
  protected readonly deploiements = signal<Deploiement[]>([]);
  protected readonly lieux = signal<Lieu[]>([]);
  protected readonly unites = signal<Unite[]>([]);
  protected readonly listes = signal<{ table: string; entries: number; loadedAt: string; loadedBy: string }[]>([]);
  protected readonly incidents = signal<Incident[]>([]);
  protected readonly vues = signal<{ schema: string; view: string }[]>([]);
  protected readonly vueOuverte = signal<string | null>(null);
  protected readonly lignesVue = signal<Record<string, unknown>[]>([]);
  protected readonly colonnesVue = computed(() => Object.keys(this.lignesVue()[0] ?? {}));
  protected readonly actifs = computed(() => this.deploiements().filter(d => d.active));
  protected readonly unitesAvecMembres = computed(() => this.unites().filter(u => u.membres.length));
  protected readonly nouveau = signal<Partial<Membre> & { username: string; fullName: string }>({ unitCode: 'DCE_SOC', username: '', fullName: '', role: 'TRAITEMENT', active: true });
  protected readonly recCode = signal('PAYMENT_CONFIRMATION');
  protected readonly recType = signal('application/xml');
  protected readonly recCle = signal('BICEC-' + Date.now());
  protected readonly recPartenaire = signal('BICEC');
  protected readonly recCorps = signal(EXEMPLE_XML);
  protected readonly message = signal<Message | null>(null);
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly info = signal<string | null>(null);

  constructor() {
    this.chargerProcedures();
  }

  private echec = (e: unknown) => { this.occupe.set(false); this.erreur.set(erreurs(e).join(' ')); };

  protected ouvrir(o: Onglet): void {
    this.onglet.set(o);
    this.erreur.set(null);
    this.info.set(null);
    if (o === 'procedures') this.chargerProcedures();
    if (o === 'cotation') this.chargerCotation();
    if (o === 'listes') this.api.deploiements().subscribe({ next: d => this.deploiements.set(d) });
    if (o === 'listes') this.api.listes().subscribe({ next: l => this.listes.set(l), error: this.echec });
    if (o === 'incidents') this.api.incidents().subscribe({ next: l => this.incidents.set(l), error: this.echec });
    if (o === 'vues') this.api.vues().subscribe({ next: l => this.vues.set(l), error: this.echec });
  }

  protected chargerProcedures(): void {
    this.api.publiees().subscribe({ next: p => this.publiees.set(p), error: this.echec });
    this.api.deploiements().subscribe({ next: d => this.deploiements.set(d), error: this.echec });
  }

  private chargerCotation(): void {
    this.api.lieux().subscribe({ next: l => this.lieux.set(l), error: this.echec });
    this.api.unites().subscribe({ next: u => this.unites.set(u), error: this.echec });
  }

  protected deployer(code: string): void {
    this.occupe.set(true);
    this.api.deployer(code, 'Déploiement de la version publiée').subscribe({
      next: d => { this.occupe.set(false); this.info.set(this.i18n.t('Procédure {0} v{1} déployée (définition {2}).', d.procedureCode, d.versionNo, d.processDefinitionId)); this.chargerProcedures(); },
      error: this.echec,
    });
  }

  protected changerUnite(l: Lieu, unite: string): void {
    this.api.lieu({ ...l, unitCode: unite }).subscribe({ next: x => this.lieux.set(x), error: this.echec });
  }

  protected basculerLieu(l: Lieu): void {
    this.api.lieu({ ...l, active: !l.active }).subscribe({ next: x => this.lieux.set(x), error: this.echec });
  }

  protected ajouterMembre(): void {
    this.api.membre({ ...this.nouveau(), active: true }).subscribe({
      next: u => { this.unites.set(u); this.nouveau.update(n => ({ ...n, username: '', fullName: '' })); this.api.lieux().subscribe(l => this.lieux.set(l)); },
      error: this.echec,
    });
  }

  protected basculerMembre(m: Membre): void {
    this.api.membre({ ...m, active: !m.active }).subscribe({ next: u => { this.unites.set(u); this.api.lieux().subscribe(l => this.lieux.set(l)); }, error: this.echec });
  }

  protected rafraichir(code: string): void {
    this.occupe.set(true);
    this.api.rafraichirListes(code).subscribe({
      next: r => { this.occupe.set(false); this.info.set(Object.keys(r.failures).length ? JSON.stringify(r.failures) : this.i18n.t('Listes rechargées.')); this.api.listes().subscribe(l => this.listes.set(l)); },
      error: this.echec,
    });
  }

  protected relancer(i: Incident): void {
    this.api.relancer(i.jobId).subscribe({ next: () => this.api.incidents().subscribe(l => this.incidents.set(l)), error: this.echec });
  }

  protected ouvrirVue(schema: string, vue: string): void {
    this.vueOuverte.set(schema + '.' + vue);
    this.api.vue(schema, vue).subscribe({ next: l => this.lignesVue.set(l), error: this.echec });
  }

  protected recevoir(): void {
    this.occupe.set(true);
    this.api.recevoir(this.recCode(), this.recCorps(), this.recType(), this.recCle(), this.recPartenaire()).subscribe({
      next: m => { this.occupe.set(false); this.message.set(m); this.recCle.set(this.recPartenaire() + '-' + Date.now()); },
      error: this.echec,
    });
  }
}
