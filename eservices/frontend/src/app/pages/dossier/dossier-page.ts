import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Api, AvisPaiement, Bilan, DetailDossier, Message, RequiredDocument, Tache } from '../../core/api';
import { AuthService } from '../../core/auth.service';
import { I18N, I18n } from '../../core/i18n';
import { enregistrer, erreurs } from '../../core/session';
import { FormulaireDynamique } from '../../shared/formulaire';
import { FormulaireProcedure } from '../../shared/formulaire-procedure';
import { Icon } from '../../shared/icon';
import { NATURES, Statut } from '../../shared/widgets';
import { MessageDrawer } from './message-drawer';

/**
 * Dossier : formulaire (brouillon ou complément), pièces, dépôt avec confirmation du montant (UC-01), paiement en ligne,
 * décision de l'agent ou du signataire (UC-04, UC-05), historique des statuts, messages échangés, livrables.
 */
@Component({
  selector: 'nx-dossier',
  imports: [RouterLink, DatePipe, Icon, Statut, FormulaireDynamique, FormulaireProcedure, MessageDrawer, ...I18N],
  template: `
    @if (erreur()) { <div class="alert err"><nx-icon name="alert" /> <div>@for (e of erreurListe(); track e) { <div>{{ e | t }}</div> }</div></div> }
    @if (info()) { <div class="alert ok"><nx-icon name="check" /> {{ info()! | t }}</div> }
    @if (d(); as x) {
      <div class="page-head">
        <div>
          <a [routerLink]="retour()" class="small"><nx-icon name="back" [size]="14" /> {{ 'Retour' | t }}</a>
          <h1 class="mono">{{ x.dossier.dossierNo ?? ('Brouillon de demande' | t) }}</h1>
          <div class="row small">
            <span>{{ x.form.procedure.labelFr | lib: x.form.procedure.labelEn }}</span>
            <span class="chip">{{ libelleType() }}</span><span class="chip">{{ natures[x.dossier.requestNature] | t }}</span>
            <nx-statut [code]="x.dossier.status" [statuts]="x.form.statuses" />
            @if (x.dossier.onBehalf) { <span class="chip info">{{ 'Déposé par un CDA' | t }}</span> }
          </div>
          <div class="muted small">{{ x.dossier.applicantName }} · NIU <span class="mono">{{ x.dossier.applicantNiu }}</span>
            @if (x.dossier.parentDossierNo) { · {{ 'Dossier source' | t }} <span class="mono">{{ x.dossier.parentDossierNo }}</span> }</div>
        </div>
        <div class="row">
          @if (x.canPreview) { <button class="btn" (click)="apercu()"><nx-icon name="eye" /> {{ 'Aperçu du livrable (brouillon)' | t }}</button> }
          @if (brouillon()) {
            <button class="btn" (click)="sauver()" [disabled]="occupe()"><nx-icon name="save" /> {{ 'Enregistrer' | t }}</button>
            <button class="btn primary" (click)="verifier()" [disabled]="occupe()"><nx-icon name="send" /> {{ 'Vérifier et déposer' | t }}</button>
          }
        </div>
      </div>

      <div class="colonnes" [class.seule]="!cote() || d()?.form?.settings?.ihm?.['resume'] === false">
        <div class="stack">
          @if (maTache(); as t) {
            <div class="card pad stack decision">
              <div class="card-title"><nx-icon name="inbox" /><h2>{{ t.name }}</h2><span class="grow"></span><span class="muted small">{{ t.created | date: 'dd/MM HH:mm' }}</span></div>
              @if (t.formKey?.endsWith(':complement')) {
                <p class="small">{{ 'L\\'agent de traitement demande un complément d\\'information' | t }} : <b>{{ x.dossier.lastObservation }}</b></p>
                <p class="muted small">{{ 'Complétez le formulaire et les pièces ci-dessous, puis renvoyez le dossier.' | t }}</p>
              } @else {
                <p class="muted small">{{ 'Vérifiez la conformité documentaire puis décidez. L\\'observation est transmise au demandeur.' | t }}</p>
              }
              @if (t.formKey?.endsWith(':traitement') && formInstruction(); as fi) {
                <div class="instruction"><h3>{{ 'Données d'instruction' | t }}</h3>
                  <p class="muted small">{{ 'Données saisies par l'organisme instructeur ; celles marquées * sont exigées pour une décision favorable.' | t }}</p>
                  <nx-formulaire [form]="fi" [donnees]="instruction()" [erreurs]="[]" (modification)="instruction.set($event)" /></div>
              }
              <div class="field"><label for="obs">{{ 'Observation' | t }}</label>
                <textarea id="obs" class="input" [value]="observation()" (input)="observation.set($any($event.target).value)"></textarea></div>
              <div class="row">
                @for (dec of t.decisions; track dec) {
                  <button class="btn" [class.primary]="dec === 'VALIDER' || dec === 'SOUMETTRE'" [class.danger]="dec === 'REJETER'" (click)="decider(t, dec)" [disabled]="occupe()">
                    <nx-icon [name]="icone(dec)" /> {{ libelleDecision(dec) | t }}</button>
                }
              </div>
            </div>
          }

          <div class="card pad">
            <div class="card-title"><nx-icon name="file" /><h2>{{ 'Formulaire, pièces et livrables' | t }}</h2>
              @if (x.dossier.requestNature === 'AMENDMENT') { <span class="chip warn">{{ 'Valeurs modifiées surlignées' | t }}</span> }</div>
            <nx-formulaire-procedure [form]="x.form" [donnees]="donnees()" [precedent]="x.dossier.previousData" [nature]="x.dossier.requestNature" [typeDemande]="x.dossier.requestType"
              [lectureSeule]="!x.canEdit" [sectionsMasquees]="x.canEdit ? ['INSTRUCTION'] : []" [erreurs]="bilan()?.erreurs ?? []" (modification)="donnees.set($event); modifie.set(true)"
              [ongletsSup]="[{ code: 'PIECES', labelFr: 'Pièces jointes', labelEn: 'Attachments', badge: piecesManquantes(), modele: tplPieces },
                { code: 'LIVRABLES', labelFr: 'Livrables', labelEn: 'Deliverables', badge: 0, modele: tplLivrables }]" />
            @if (x.canEdit && !brouillon()) {
              <div class="row"><button class="btn" (click)="sauver()" [disabled]="occupe()"><nx-icon name="save" /> {{ 'Enregistrer les compléments' | t }}</button></div>
            }
          </div>

        </div>

        @if (cote()) {
        <div class="stack">
          @if (x.paymentNotices.length) {
            <div class="card pad stack">
              <div class="card-title"><nx-icon name="wallet" /><h2>{{ 'Paiement' | t }}</h2></div>
              @for (a of x.paymentNotices; track a.id) {
                <div class="avis">
                  <div class="between"><b class="mono">{{ a.noticeNo }}</b>
                    <span class="chip" [class.ok]="a.status === 'PAID'" [class.warn]="a.status === 'ISSUED'" [class.danger]="a.status === 'CANCELLED'">{{ statutAvis(a.status) | t }}</span></div>
                  <table class="lignes small">
                    @for (l of a.lines; track l.lineNo) { <tr><td>{{ l.label }}<div class="muted">{{ l.beneficiary }}</div></td><td class="num">{{ l.amount | num }}</td></tr> }
                    <tr class="total"><td>{{ 'Total' | t }}</td><td class="num">{{ a.totalAmount | num }} {{ a.currency }}</td></tr>
                  </table>
                  @if (a.status === 'PAID') { <div class="muted small">{{ 'Payé le {0}' | t: (a.paidAt | date: 'dd/MM/yyyy HH:mm') }} · {{ 'Quittance' | t }} <span class="mono">{{ a.paymentReference }}</span> · {{ a.bankCode }}</div> }
                  @else if (a.status === 'ISSUED') {
                    @if (a.dueAt) { <div class="muted small">{{ 'À régler avant le {0}' | t: (a.dueAt | date: 'dd/MM/yyyy') }}</div> }
                    @if (estDeposant()) { <button class="btn primary" (click)="paiement.set(a)"><nx-icon name="money" /> {{ 'Payer en ligne' | t }}</button> }
                  }
                </div>
              }
            </div>
          }

          <div class="card pad">
            <div class="card-title"><nx-icon name="history" /><h2>{{ 'Suivi' | t }}</h2></div>
            <div class="timeline">
              @for (h of historiqueInverse(); track h.id) {
                <div class="evt"><span class="pt" [class]="'pt ' + ton(h.toStatus)"></span>
                  <div class="small"><nx-statut [code]="h.toStatus" [statuts]="x.form.statuses" />
                    <span class="muted"> · {{ h.occurredAt | date: 'dd/MM/yyyy HH:mm' }} · {{ h.actor }}</span>
                    @if (h.reason) { <div>{{ h.reason }}</div> }</div></div>
              }
            </div>
            @if (x.cotation.length) {
              <div class="muted small sep">{{ 'Coté à {0} (signataire {1}) — unité {2}, {3} agent(s) éligible(s)' | t: x.cotation[x.cotation.length - 1].agent : x.cotation[x.cotation.length - 1].signatory : x.cotation[x.cotation.length - 1].unit : x.cotation[x.cotation.length - 1].candidates }}</div>
            }
            @if (x.dossier.slaDueAt && x.dossier.lifecycle === 'ACTIVE') { <div class="muted small">{{ 'Échéance de traitement : {0}' | t: (x.dossier.slaDueAt | date: 'dd/MM/yyyy HH:mm') }}</div> }
          </div>

          @if (x.messages.length) {
            <div class="card pad">
              <div class="card-title"><nx-icon name="mail" /><h2>{{ 'Messages échangés' | t }}</h2></div>
              @for (m of x.messages; track m.id) {
                <button class="msg" (click)="messageOuvert.set(m)">
                  <span class="dir" [class.in]="m.direction === 'IN'"><nx-icon [name]="m.direction === 'IN' ? 'download' : 'send'" [size]="14" /></span>
                  <span class="grow"><b>{{ m.code }}</b><span class="muted small"> · {{ m.sender }} → {{ m.recipient }}</span>
                    <span class="muted small" style="display:block">{{ m.createdAt | date: 'dd/MM HH:mm:ss' }} · {{ m.state }}</span></span>
                  <span class="chip info">{{ m.format }}</span>
                </button>
              }
            </div>
          }
        </div>
        }
      </div>

      <!-- Onglets « Pièces jointes » et « Livrables », en dernier dans le formulaire -->
      <ng-template #tplPieces>
            <p class="muted small">{{ 'Pièces exigées par la procédure pour ce type de demande ; * obligatoire.' | t }}</p>
            <div class="scroll-x">
            <table class="datagrid">
              <thead><tr><th>{{ 'Pièce' | t }}</th><th>{{ 'Fichier' | t }}</th><th></th></tr></thead>
              <tbody>
                @for (p of piecesAttendues(); track p.code) {
                  <tr [class.manque]="!piece(p.code) && p.mandatory">
                    <td><span class="code-tag">{{ p.code }}</span> {{ p.labelFr | lib: p.labelEn }}
                      @if (p.mandatory) { <span class="req">*</span> } @else if (p.conditionText) { <span class="muted small">({{ 'conditionnelle' | t }})</span> }
                      @if (p.observation) { <div class="muted small">{{ p.observation }}</div> }</td>
                    <td class="small">
                      @if (piece(p.code); as f) {
                        <a [href]="auth.lien(api.urlPiece(x.dossier.id, f.id))" target="_blank" rel="noopener">{{ f.fileName }}</a>
                        <div class="muted">{{ f.sizeBytes / 1024 | num }} Ko · {{ origine(f.source) | t }}</div>
                      } @else { <span class="muted">—</span> }
                    </td>
                    <td class="actions">
                      @if (x.canEdit) {
                        <label class="btn sm"><nx-icon name="upload" [size]="15" /> {{ (piece(p.code) ? 'Remplacer' : 'Joindre') | t }}
                          <input type="file" hidden (change)="joindre(p.code, $any($event.target))" /></label>
                        @if (piece(p.code); as f) { <button class="btn icon ghost sm danger" (click)="retirerPiece(f.id)" [attr.aria-label]="'Retirer' | t"><nx-icon name="trash" [size]="15" /></button> }
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
            </div>
      </ng-template>
      <ng-template #tplLivrables>
        <div class="stack">
              @for (doc of x.documents; track doc.id) {
                <div class="avis">
                  <div class="between"><b class="mono">{{ doc.docNo }}</b><span class="chip" [class.ok]="doc.status === 'ACTIVE'">{{ doc.status }}</span></div>
                  <div class="small">{{ 'Valable du {0} au {1}' | t: (doc.validFrom | date: 'dd/MM/yyyy') : (doc.validUntil | date: 'dd/MM/yyyy') }}</div>
                  <div class="muted small">{{ 'Signé par {0} le {1}' | t: (doc.signerName ?? doc.signedBy) : (doc.signedAt | date: 'dd/MM/yyyy HH:mm') }}</div>
                  <div class="row">
                    <a class="btn sm" [href]="auth.lien(api.urlPdf(doc.docNo))" target="_blank" rel="noopener"><nx-icon name="file" [size]="15" /> PDF</a>
                    <a class="btn sm ghost" [routerLink]="['/verifier', doc.docNo]"><nx-icon name="qr" [size]="15" /> {{ 'Vérifier' | t }}</a>
                  </div>
                </div>
              } @empty { <div class="empty small">{{ 'Aucun livrable délivré pour ce dossier : il apparaîtra ici après la signature.' | t }}</div> }
        </div>
      </ng-template>
    } @else if (!erreur()) { <div class="skeleton" style="height: 240px"></div> }

    @if (confirmation(); as b) {
      <div class="overlay" (click)="confirmation.set(null)"></div>
      <div class="modal" role="dialog" [attr.aria-label]="'Confirmer le dépôt' | t">
        <div class="m-head"><h2>{{ (b.erreurs.length ? 'Demande incomplète' : 'Confirmer le dépôt') | t }}</h2>
          <button class="btn icon ghost" (click)="confirmation.set(null)"><nx-icon name="x" /></button></div>
        <div class="m-body">
          @if (b.erreurs.length) {
            <div class="alert err"><ul>@for (e of b.erreurs; track $index) { <li>{{ e.message }}</li> }</ul></div>
          } @else {
            @if (b.total > 0) {
              <p>{{ 'Montant à payer après le dépôt :' | t }}</p>
              <table class="lignes">
                @for (f of b.frais; track f.code) { <tr><td>{{ f.libelle }}<div class="muted small">{{ f.beneficiaire }}</div></td><td class="num">{{ f.montant | num }}</td></tr> }
                <tr class="total"><td>{{ 'Total' | t }}</td><td class="num">{{ b.total | num }} XAF</td></tr>
              </table>
              <p class="muted small">{{ 'Un numéro de dossier (CUR) et un avis de paiement seront générés ; la demande sera transmise à l\\'administration après le paiement.' | t }}</p>
            } @else {
              <p>{{ 'Cette demande n\\'est pas soumise à paiement : elle sera transmise directement pour traitement.' | t }}</p>
            }
            <details class="small muted"><summary>{{ 'Règles appliquées ({0})' | t: b.regles.length }}</summary><ul>@for (r of b.regles; track r) { <li>{{ r }}</li> }</ul></details>
          }
        </div>
        <div class="m-foot">
          <button class="btn ghost" (click)="confirmation.set(null)">{{ 'Annuler' | t }}</button>
          @if (!b.erreurs.length) { <button class="btn primary" (click)="deposer()" [disabled]="occupe()"><nx-icon name="send" /> {{ 'Confirmer l\\'envoi' | t }}</button> }
        </div>
      </div>
    }

    @if (paiement(); as a) {
      <div class="overlay" (click)="paiement.set(null)"></div>
      <div class="modal" role="dialog" [attr.aria-label]="'Paiement en ligne' | t">
        <div class="m-head"><h2>{{ 'Paiement en ligne — e-PAYMENT' | t }}</h2><button class="btn icon ghost" (click)="paiement.set(null)"><nx-icon name="x" /></button></div>
        <div class="m-body">
          <p>{{ 'Avis' | t }} <b class="mono">{{ a.noticeNo }}</b> — <b>{{ a.totalAmount | num }} {{ a.currency }}</b></p>
          <div class="field"><label for="bq">{{ 'Banque ou opérateur de paiement' | t }}</label>
            <select id="bq" class="input" (change)="banque.set($any($event.target).value)">
              @for (b of banques; track b.code) { <option [value]="b.code" [selected]="banque() === b.code">{{ b.label }}</option> }
            </select></div>
          <div class="alert info small">{{ 'Démonstration : la banque renvoie sa confirmation de paiement au format XML (message PAYMENT_CONFIRMATION), traitée comme un message bancaire réel.' | t }}</div>
        </div>
        <div class="m-foot"><button class="btn ghost" (click)="paiement.set(null)">{{ 'Annuler' | t }}</button>
          <button class="btn primary" (click)="payer(a)" [disabled]="occupe()"><nx-icon name="money" /> {{ 'Payer {0} XAF' | t: (a.totalAmount | num) }}</button></div>
      </div>
    }

    @if (messageOuvert(); as m) { <nx-message-drawer [message]="m" (fermer)="messageOuvert.set(null)" /> }
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 16px; }
    h1 { margin: 4px 0; }
    .colonnes { display: grid; grid-template-columns: minmax(0, 1fr) minmax(260px, 320px); gap: 14px; align-items: start; }
    .colonnes.seule { grid-template-columns: minmax(0, 1fr); }
    @media (max-width: 1100px) { .colonnes { grid-template-columns: minmax(0, 1fr); } }
    .decision { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
    .req { color: var(--danger); }
    tr.manque td:first-child { box-shadow: inset 3px 0 0 var(--danger); }
    .datagrid tbody tr { cursor: default; }
    .actions { white-space: nowrap; text-align: right; }
    .avis { display: flex; flex-direction: column; gap: 8px; padding: 12px; border: 1px solid var(--line); border-radius: 12px; }
    table.lignes { width: 100%; border-collapse: collapse; }
    table.lignes td { padding: 5px 0; border-bottom: 1px dashed var(--line); }
    table.lignes .num { text-align: right; white-space: nowrap; font-variant-numeric: tabular-nums; }
    table.lignes .total td { font-weight: 700; border-bottom: 0; }
    .msg { display: flex; gap: 10px; align-items: center; width: 100%; padding: 8px; border: 0; border-radius: 10px; background: none; font: inherit; color: var(--text); text-align: left; cursor: pointer; }
    .msg:hover { background: var(--surface-2); }
    .dir { width: 26px; height: 26px; border-radius: 8px; display: grid; place-items: center; background: var(--accent-soft); color: var(--accent); }
    .dir.in { background: var(--ok-soft); color: var(--ok); }
    @media (max-width: 1100px) { .colonnes { grid-template-columns: 1fr; } }
  `],
})
export class DossierPage {
  protected readonly api = inject(Api);
  protected readonly auth = inject(AuthService);
  private readonly i18n = inject(I18n);
  readonly id = input.required<string>();
  protected readonly natures = NATURES;
  protected readonly d = signal<DetailDossier | null>(null);
  protected readonly donnees = signal<Record<string, unknown>>({});
  protected readonly modifie = signal(false);
  protected readonly bilan = signal<Bilan | null>(null);
  protected readonly confirmation = signal<Bilan | null>(null);
  protected readonly paiement = signal<AvisPaiement | null>(null);
  protected readonly messageOuvert = signal<Message | null>(null);
  protected readonly observation = signal('');
  /** Données d'instruction saisies dans la tâche de traitement (section INSTRUCTION). */
  protected readonly instruction = signal<Record<string, unknown>>({});
  protected readonly formInstruction = computed(() => {
    const x = this.d(), t = this.maTache();
    if (!x || !t) return null;
    const champs = x.form.fields.filter(f => f.sectionCode === 'INSTRUCTION')
      .map(f => (t.fields ?? []).includes(f.code) ? { ...f, mandatory: 'OUI' as const } : { ...f, mandatory: 'NON' as const });
    return champs.length ? { ...x.form, sections: x.form.sections.filter(s => s.code === 'INSTRUCTION'), fields: champs } : null;
  });
  protected readonly banque = signal('BICEC');
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly info = signal<string | null>(null);
  protected readonly banques = [{ code: 'BICEC', label: 'BICEC' }, { code: 'SGC', label: 'Société Générale Cameroun' }, { code: 'AFRILAND', label: 'Afriland First Bank' },
    { code: 'ECOBANK', label: 'Ecobank Cameroun' }, { code: 'MTN_MOMO', label: 'MTN Mobile Money' }, { code: 'ORANGE_MONEY', label: 'Orange Money' }];

  protected readonly brouillon = computed(() => this.d()?.dossier.lifecycle === 'DRAFT' && this.d()!.canEdit);
  protected readonly estDeposant = computed(() => this.d()?.dossier.submittedBy === this.auth.utilisateur()?.username);
  protected readonly maTache = computed<Tache | null>(() => this.d()?.tasks.find(t => t.assignee === this.auth.utilisateur()?.username) ?? null);
  protected readonly historiqueInverse = computed(() => [...(this.d()?.history ?? [])].reverse());
  /** Colonne de droite (paiement, livrables, suivi, messages) : masquée tant qu'elle n'a rien à montrer (brouillon). */
  protected readonly cote = computed(() => {
    const x = this.d();
    return !!x && (x.paymentNotices.length > 0 || x.documents.length > 0 || x.history.length > 0 || x.messages.length > 0 || x.cotation.length > 0);
  });
  protected readonly piecesAttendues = computed<RequiredDocument[]>(() => {
    const x = this.d();
    if (!x) return [];
    return x.form.documents.filter(p => !p.requestTypes?.length || p.requestTypes.includes(x.dossier.requestType));
  });
  /** Pièces obligatoires non jointes : pastille de l'onglet « Pièces jointes ». */
  protected readonly piecesManquantes = computed(() => this.piecesAttendues().filter(p => p.mandatory && !this.piece(p.code)).length);
  protected readonly erreurListe = computed(() => (this.erreur() ?? '').split('\n').filter(Boolean));
  protected readonly retour = computed(() => this.estDeposant() ? '/demandes' : this.auth.a('eservices.traiter') || this.auth.a('eservices.signer') ? '/taches' : '/supervision/dossiers');

  constructor() {
    effect(() => { this.id(); this.charger(); });
  }

  protected charger(): void {
    this.api.dossier(this.id()).subscribe({
      next: x => { this.d.set(x); this.donnees.set({ ...x.dossier.formData }); this.instruction.set({ ...x.dossier.formData }); this.modifie.set(false); },
      error: e => this.erreur.set(erreurs(e, 'Le service eservices').join('\n')),
    });
  }

  protected libelleType(): string {
    const x = this.d();
    const t = x?.form.requestTypes.find(r => r.code === x.dossier.requestType);
    return t ? this.i18n.libelle(t.labelFr, t.labelEn) : x?.dossier.requestType ?? '';
  }

  protected piece(code: string) {
    return this.d()?.attachments.find(a => a.docCode === code) ?? null;
  }

  protected origine(s: string): string {
    return ({ REGISTRY: 'registre des opérateurs', SYSTEM: 'dossier source', UPLOAD: 'téléversée' } as Record<string, string>)[s] ?? s;
  }

  protected ton(statut: string): string {
    return this.d()?.form.statuses.find(s => s.code === statut)?.tone ?? 'info';
  }

  protected statutAvis(s: string): string {
    return ({ ISSUED: 'À payer', PAID: 'Payé', CANCELLED: 'Annulé', OVERDUE: 'En retard', DRAFT: 'Brouillon', DISPUTED: 'Contesté' } as Record<string, string>)[s] ?? s;
  }

  protected libelleDecision(d: string): string {
    return ({ COMPLEMENT: 'Demander un complément', VALIDER: 'Valider', REJETER: 'Rejeter', RETOUR: 'Retourner pour réexamen', SOUMETTRE: 'Renvoyer le dossier complété', OUI: 'Oui', NON: 'Non' } as Record<string, string>)[d] ?? d;
  }

  protected icone(d: string): string {
    return ({ COMPLEMENT: 'mail', VALIDER: 'check', REJETER: 'ban', RETOUR: 'back', SOUMETTRE: 'send', OUI: 'check', NON: 'ban' } as Record<string, string>)[d] ?? 'chevron';
  }

  private echec(e: unknown): void {
    this.occupe.set(false);
    this.erreur.set(erreurs(e, 'Le service eservices').join('\n'));
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  protected sauver(apres?: () => void): void {
    this.occupe.set(true);
    this.erreur.set(null);
    this.api.modifier(this.id(), this.donnees()).subscribe({
      next: () => { this.occupe.set(false); this.modifie.set(false); if (apres) apres(); else { this.info.set('Modifications enregistrées.'); setTimeout(() => this.info.set(null), 2500); } },
      error: e => this.echec(e),
    });
  }

  protected verifier(): void {
    this.sauver(() => this.api.estimer(this.id()).subscribe({
      next: b => { this.bilan.set(b); this.confirmation.set(b); },
      error: e => this.echec(e),
    }));
  }

  protected deposer(): void {
    this.occupe.set(true);
    this.api.deposer(this.id()).subscribe({
      next: x => { this.occupe.set(false); this.confirmation.set(null); this.bilan.set(null); this.info.set(this.i18n.t('Demande déposée : dossier {0}.', x.dossierNo ?? '')); this.charger(); },
      error: e => { this.confirmation.set(null); this.echec(e); },
    });
  }

  protected joindre(code: string, champ: HTMLInputElement): void {
    const f = champ.files?.[0];
    if (!f) return;
    this.occupe.set(true);
    this.api.joindre(this.id(), code, f).subscribe({ next: () => { this.occupe.set(false); champ.value = ''; this.charger(); }, error: e => this.echec(e) });
  }

  protected retirerPiece(piece: number): void {
    this.api.retirer(this.id(), piece).subscribe({ next: () => this.charger(), error: e => this.echec(e) });
  }

  protected payer(a: AvisPaiement): void {
    this.occupe.set(true);
    this.api.payer(a.noticeNo, this.banque(), this.banque().includes('MONEY') || this.banque().includes('MOMO') ? 'MOBILE' : 'BANQUE').subscribe({
      next: m => {
        this.occupe.set(false);
        this.paiement.set(null);
        this.info.set(m.state === 'IN_PROCESSED' ? 'Paiement confirmé : la demande est transmise à l\'administration.' : 'Paiement non accepté : ' + (m.validation?.errors?.join(' ') ?? m.state));
        setTimeout(() => this.charger(), 800);
      },
      error: e => this.echec(e),
    });
  }

  protected decider(t: Tache, decision: string): void {
    const complement = t.formKey?.endsWith(':complement');
    const lancer = () => this.api.completer(t.id, { decision, observation: this.observation(), data: complement ? this.donnees() : t.formKey?.endsWith(':traitement') ? this.instruction() : undefined }).subscribe({
      next: () => { this.occupe.set(false); this.observation.set(''); this.info.set(this.i18n.t('Décision enregistrée : {0}.', this.i18n.t(this.libelleDecision(decision)))); setTimeout(() => this.charger(), 600); },
      error: e => this.echec(e),
    });
    this.occupe.set(true);
    this.erreur.set(null);
    lancer();
  }

  protected apercu(): void {
    this.api.apercu(this.id()).subscribe({
      next: b => window.open(URL.createObjectURL(b), '_blank') ?? enregistrer(b, 'apercu-brouillon.pdf'),
      error: e => this.echec(e),
    });
  }
}
