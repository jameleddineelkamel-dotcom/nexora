import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { DatePipe, JsonPipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { Api, Attribut, Compte, Evenement, Piece } from '../core/api';
import { AuthService, DROITS } from '../core/auth.service';
import { I18N } from '../core/i18n';
import { erreurs } from '../core/session';
import { Icon } from '../shared/icon';

/** Fiche d'un opérateur (ou enrôlement) : attributs, pièces d'enrôlement, comptes rattachés, historique. */
@Component({
  selector: 'nx-operateur',
  imports: [RouterLink, DatePipe, JsonPipe, Icon, ...I18N],
  template: `
    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
    @if (info()) { <div class="alert ok">{{ info()! | t }}</div> }
    <div class="page-head"><div>
      <a routerLink="/operateurs" class="small"><nx-icon name="back" [size]="14" /> {{ 'Opérateurs économiques' | t }}</a>
      <h1>{{ nouveau() ? ('Enrôler un opérateur' | t) : (val('legalName') || niu()) }}</h1>
      @if (!nouveau()) { <p class="muted mono">NIU {{ niu() }} · {{ 'mis à jour le {0} par {1}' | t: (val('updatedAt') | date: 'dd/MM/yyyy HH:mm') : val('updatedBy') }}</p> }
    </div></div>
    <div class="tabs">
      <button class="tab" [class.on]="onglet() === 'fiche'" (click)="onglet.set('fiche')"><nx-icon name="building" [size]="16" /> {{ 'Fiche' | t }}</button>
      @if (!nouveau()) {
        <button class="tab" [class.on]="onglet() === 'pieces'" (click)="onglet.set('pieces')"><nx-icon name="archive" [size]="16" /> {{ 'Pièces ({0})' | t: pieces().length }}</button>
        <button class="tab" [class.on]="onglet() === 'comptes'" (click)="onglet.set('comptes')"><nx-icon name="users" [size]="16" /> {{ 'Comptes rattachés' | t }}</button>
        <button class="tab" [class.on]="onglet() === 'historique'" (click)="onglet.set('historique')"><nx-icon name="history" [size]="16" /> {{ 'Historique' | t }}</button>
      }
    </div>
    @switch (onglet()) {
      @case ('fiche') {
        <div class="card pad stack">
          <fieldset class="lecture" [disabled]="!auth.a(d.gerer)">
            <div class="form-grid">
              @if (nouveau()) { <div class="field"><label for="niu">NIU *</label><input id="niu" class="input mono" [value]="val('niu')" (input)="poser('niu', $any($event.target).value.toUpperCase())" /></div> }
              @for (a of attributs(); track a.key) {
                <div class="field"><label [attr.for]="'a-' + a.key">{{ a.label | t }}</label>
                  @switch (a.key) {
                    @case ('partyTypeCode') { <select class="input" [id]="'a-' + a.key" (change)="poser(a.key, $any($event.target).value)"><option value=""></option>
                      @for (o of personnalites; track o.c) { <option [value]="o.c" [selected]="val(a.key) === o.c">{{ o.l | t }}</option> }</select> }
                    @case ('nationality') { <select class="input" [id]="'a-' + a.key" (change)="poser(a.key, $any($event.target).value)">
                      <option value="C" [selected]="val(a.key) === 'C'">{{ 'National' | t }}</option><option value="E" [selected]="val(a.key) === 'E'">{{ 'Étranger' | t }}</option></select> }
                    @default { <input class="input" [id]="'a-' + a.key" [type]="a.type === 'D' ? 'date' : 'text'" [value]="val(a.key)" (input)="poser(a.key, $any($event.target).value)"
                      [class.vide]="!nouveau() && !val(a.key)" /> }
                  }
                </div>
              }
              @if (!nouveau()) { <div class="field"><label for="st">{{ 'Statut' | t }}</label><select id="st" class="input" (change)="poser('status', $any($event.target).value)">
                <option value="ACTIVE" [selected]="val('status') === 'ACTIVE'">{{ 'Actif' | t }}</option><option value="SUSPENDED" [selected]="val('status') === 'SUSPENDED'">{{ 'Suspendu' | t }}</option></select></div> }
            </div>
          </fieldset>
          @if (auth.a(d.gerer)) {
            <div class="row"><input class="input grow" [value]="motif()" (input)="motif.set($any($event.target).value)" [placeholder]="'Motif de la modification' | t" />
              <button class="btn primary" (click)="enregistrer()" [disabled]="!modifie()"><nx-icon name="save" /> {{ (nouveau() ? 'Enrôler' : 'Enregistrer') | t }}</button></div>
          }
          @if (!nouveau() && vides() > 0) { <p class="muted small">{{ '{0} attribut(s) non renseigné(s) : ils pourront être complétés à la validation d\\'une procédure (RG2 UC-05).' | t: vides() }}</p> }
        </div>
      }
      @case ('pieces') {
        <div class="card pad stack">
          <table class="datagrid"><thead><tr><th>{{ 'Code' | t }}</th><th>{{ 'Pièce' | t }}</th><th>{{ 'Fichier' | t }}</th><th>{{ 'Déposée' | t }}</th><th></th></tr></thead>
            <tbody>@for (p of pieces(); track p.id) {
              <tr><td><span class="code-tag">{{ p.docCode }}</span></td><td>{{ p.label }}</td>
                <td><a [href]="auth.lien(api.urlPiece(niu(), p.id))" target="_blank" rel="noopener">{{ p.fileName }}</a> <span class="muted small">{{ p.sizeBytes / 1024 | num }} Ko</span></td>
                <td class="small">{{ p.uploadedAt | date: 'dd/MM/yyyy' }} · {{ p.uploadedBy }}</td>
                <td>@if (auth.a(d.gerer)) { <button class="btn icon ghost sm danger" (click)="retirer(p)"><nx-icon name="trash" [size]="15" /></button> }</td></tr>
            } @empty { <tr><td colspan="5" class="empty">{{ 'Aucune pièce.' | t }}</td></tr> }</tbody></table>
          <p class="muted small">{{ 'Les codes de pièces (P1…Pn) sont ceux des procédures : ces pièces sont jointes automatiquement aux nouvelles demandes.' | t }}</p>
          <div class="row"><input class="input mono" style="max-width: 90px" [value]="code()" (input)="code.set($any($event.target).value.toUpperCase())" placeholder="P2" />
            <input class="input grow" [value]="libelle()" (input)="libelle.set($any($event.target).value)" [placeholder]="'Libellé' | t" />
            <label class="btn"><nx-icon name="upload" /> {{ 'Déposer' | t }}<input type="file" hidden (change)="joindre($any($event.target))" /></label></div>
        </div>
      }
      @case ('comptes') {
        <div class="card pad stack">
          <table class="datagrid"><thead><tr><th>{{ 'Compte SSO' | t }}</th><th>{{ 'Relation' | t }}</th><th>{{ 'Actif' | t }}</th><th>{{ 'Depuis' | t }}</th></tr></thead>
            <tbody>@for (c of comptes(); track c.username) {
              <tr><td class="mono">{{ c.username }}</td><td><span class="chip" [class.accent]="c.relation === 'TITULAIRE'">{{ (c.relation === 'TITULAIRE' ? 'Titulaire' : 'Mandataire (CDA)') | t }}</span></td>
                <td><input type="checkbox" [checked]="c.active" [disabled]="!auth.a(d.gerer)" (change)="rattacher(c.username, c.relation, $any($event.target).checked)" /></td>
                <td class="small">{{ c.createdAt | date: 'dd/MM/yyyy' }} · {{ c.createdBy }}</td></tr>
            } @empty { <tr><td colspan="4" class="empty">{{ 'Aucun compte rattaché.' | t }}</td></tr> }</tbody></table>
          @if (auth.a(d.gerer)) {
            <div class="row"><input class="input mono grow" [value]="compte()" (input)="compte.set($any($event.target).value.toLowerCase())" [placeholder]="'identifiant SSO' | t" />
              <select class="input" style="max-width: 220px" (change)="relation.set($any($event.target).value)"><option value="TITULAIRE">{{ 'Titulaire' | t }}</option><option value="MANDATAIRE">{{ 'Mandataire (CDA)' | t }}</option></select>
              <button class="btn primary" (click)="rattacher(compte(), relation(), true)" [disabled]="!compte()"><nx-icon name="link" /> {{ 'Rattacher' | t }}</button></div>
          }
        </div>
      }
      @case ('historique') {
        <div class="card pad"><div class="timeline">
          @for (h of historique(); track $index) {
            <div class="evt"><span class="pt" [class.ok]="h.operation === 'ENROLEMENT'" [class.warn]="h.operation === 'COMPLETION'"></span>
              <div class="small"><b>{{ h.operation }}</b> · {{ h.occurredAt | date: 'dd/MM/yyyy HH:mm' }} · {{ h.actor }} <span class="muted">{{ h.channel }}</span>
                @if (h.reason) { <div>{{ h.reason }}</div> }
                <div class="muted mono">@for (c of champs(h); track c) { <div>{{ c }} : {{ h.changes[c].old | json }} → {{ h.changes[c].new | json }}</div> }</div></div></div>
          }
        </div></div>
      }
    }
  `,
  styles: [`:host { display: flex; flex-direction: column; gap: 16px; } .vide { border-style: dashed; } .datagrid tbody tr { cursor: default; }`],
})
export class OperateurPage {
  protected readonly api = inject(Api);
  private readonly router = inject(Router);
  protected readonly auth = inject(AuthService);
  protected readonly d = DROITS;
  readonly niu = input<string>('');
  protected readonly nouveau = computed(() => !this.niu());
  protected readonly onglet = signal<'fiche' | 'pieces' | 'comptes' | 'historique'>('fiche');
  protected readonly attributs = signal<Attribut[]>([]);
  protected readonly valeurs = signal<Record<string, unknown>>({ nationality: 'C', countryCode: 'CM' });
  protected readonly pieces = signal<Piece[]>([]);
  protected readonly comptes = signal<Compte[]>([]);
  protected readonly historique = signal<Evenement[]>([]);
  protected readonly modifie = signal(false);
  protected readonly motif = signal('');
  protected readonly code = signal('');
  protected readonly libelle = signal('');
  protected readonly compte = signal('');
  protected readonly relation = signal<'TITULAIRE' | 'MANDATAIRE'>('TITULAIRE');
  protected readonly erreur = signal<string | null>(null);
  protected readonly info = signal<string | null>(null);
  protected readonly personnalites = [{ c: 'PP', l: 'Personne physique' }, { c: 'PM', l: 'Personne morale' }, { c: 'SUC', l: 'Succursale d\'entité étrangère' }, { c: 'GIE', l: 'Groupement d\'intérêt économique' }];
  protected readonly vides = computed(() => this.attributs().filter(a => !this.val(a.key)).length);

  constructor() {
    this.api.attributs().subscribe(a => this.attributs.set(a));
    effect(() => { if (this.niu()) this.charger(); });
  }

  private echec = (e: unknown) => this.erreur.set(erreurs(e, 'Le registre').join(' '));

  private charger(): void {
    const n = this.niu();
    this.api.lire(n).subscribe({ next: o => { this.valeurs.set(o); this.modifie.set(false); }, error: this.echec });
    this.api.pieces(n).subscribe({ next: p => this.pieces.set(p), error: this.echec });
    this.api.comptes(n).subscribe({ next: c => this.comptes.set(c), error: () => this.comptes.set([]) });
    this.api.historique(n).subscribe({ next: h => this.historique.set(h), error: () => this.historique.set([]) });
  }

  protected val(k: string): string {
    const v = this.valeurs()[k];
    return v === null || v === undefined ? '' : String(v);
  }

  protected poser(k: string, v: string): void {
    this.valeurs.update(x => ({ ...x, [k]: v }));
    this.modifie.set(true);
  }

  protected champs(h: Evenement): string[] {
    return Object.keys(h.changes ?? {});
  }

  protected enregistrer(): void {
    const corps: Record<string, unknown> = {};
    for (const a of this.attributs()) corps[a.key] = this.val(a.key) || null;
    if (this.nouveau()) {
      corps['niu'] = this.val('niu');
      this.api.enroler(corps, this.motif() || 'Enrôlement').subscribe({ next: o => this.router.navigate(['/operateurs', o.niu]), error: this.echec });
    } else {
      corps['status'] = this.val('status');
      this.api.modifier(this.niu(), corps, this.motif()).subscribe({ next: () => { this.info.set('Fiche enregistrée.'); this.motif.set(''); this.charger(); }, error: this.echec });
    }
  }

  protected joindre(champ: HTMLInputElement): void {
    const f = champ.files?.[0];
    if (!f || !this.code()) { this.erreur.set('Indiquez le code de la pièce (ex. P2).'); return; }
    this.api.joindre(this.niu(), this.code(), this.libelle(), f).subscribe({ next: () => { champ.value = ''; this.charger(); }, error: this.echec });
  }

  protected retirer(p: Piece): void {
    this.api.retirer(this.niu(), p.id).subscribe({ next: () => this.charger(), error: this.echec });
  }

  protected rattacher(username: string, relation: string, actif: boolean): void {
    this.api.rattacher(this.niu(), { username, relation: relation as Compte['relation'], active: actif }).subscribe({ next: c => { this.comptes.set(c); this.compte.set(''); }, error: this.echec });
  }
}
