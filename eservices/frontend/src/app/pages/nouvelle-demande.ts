import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { Api, Procedure, RequestType } from '../core/api';
import { AuthService, DROITS } from '../core/auth.service';
import { I18N, I18n } from '../core/i18n';
import { erreurs } from '../core/session';
import { Icon } from '../shared/icon';
import { NATURES } from '../shared/widgets';

type Titre = { dossierNo: string; requestType: string; applicantNiu: string; applicantName: string; docNo: string; baseDocNo: string; validUntil: string };

/**
 * Initier une demande (UC-01 inscription, UC-02 renouvellement, UC-03 amendement) : procédure, type de demande,
 * nature, opérateur concerné (le CDA renseigne le NIU d'un tiers) et, pour un amendement, l'attestation à modifier.
 */
@Component({
  selector: 'nx-nouvelle-demande',
  imports: [RouterLink, DatePipe, Icon, ...I18N],
  template: `
    <div class="page-head">
      <div><h1>{{ 'Nouvelle demande' | t }}</h1><p class="muted">{{ 'Choisissez la procédure, le type de demande et sa nature : le formulaire est pré-rempli à partir du registre des opérateurs.' | t }}</p></div>
    </div>
    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }

    <div class="card pad stack">
      <h2>1. {{ 'Procédure' | t }}</h2>
      <div class="choix">
        @for (p of catalogue(); track p.code) {
          <button class="option" [class.on]="code() === p.code" (click)="choisirProcedure(p.code)">
            <span class="ic"><nx-icon name="stamp" /></span>
            <span class="grow"><b>{{ p.labelFr | lib: p.labelEn }}</b><span class="muted small">{{ p.authority }}</span>
              <span class="small"><span class="chip">{{ p.terminology.process.labelFr }}</span> <span class="chip">{{ p.terminology.circuit.labelFr }}</span> <span class="chip">v{{ p.versionNo }}</span></span></span>
          </button>
        } @empty { <div class="muted">{{ 'Aucune procédure déployée.' | t }}</div> }
      </div>
    </div>

    @if (procedureChoisie(); as p) {
      <div class="card pad stack">
        <h2>2. {{ 'Type de demande' | t }}</h2>
        <div class="choix">
          @for (t of p.requestTypes; track t.code) {
            <button class="option" [class.on]="type() === t.code" (click)="choisirType(t)">
              <span class="code-tag">{{ t.curCode }}</span>
              <span class="grow"><b>{{ t.labelFr | lib: t.labelEn }}</b>
                <span class="muted small">{{ 'Frais (demande initiale)' | t }} : {{ montant(p, t.code, 'INITIAL') | num }} XAF</span></span>
            </button>
          }
        </div>
        @if (typeChoisi(); as t) {
          <h2>3. {{ 'Nature de la demande' | t }}</h2>
          <div class="row">
            @for (n of t.natures; track n) {
              <button class="btn" [class.primary]="nature() === n" (click)="choisirNature(n)">{{ natures[n] | t }}</button>
            }
          </div>
          <p class="muted small">
            @switch (nature()) {
              @case ('RENEWAL') { {{ 'Renouvellement pour l\\'année {0} (RG05) ; impossible si un renouvellement est déjà en cours ou validé (RG07).' | t: (annee + 1) }} }
              @case ('AMENDMENT') { {{ 'L\\'amendement modifie une attestation valide ; il n\\'est pas payant et part directement en traitement.' | t }} }
              @default { {{ 'Première inscription ; la demande passe au traitement après le paiement des frais.' | t }} }
            }
          </p>

          @if (auth.a(d.mandataire)) {
            <h2>4. {{ 'Opérateur concerné' | t }}</h2>
            <div class="form-grid">
              <div class="field"><label for="niu">{{ 'NIU de l\\'opérateur' | t }}</label>
                <input id="niu" class="input mono" [value]="niu()" (input)="niu.set($any($event.target).value.toUpperCase())" [placeholder]="'Vide : votre propre structure' | t" />
                <span class="hint">{{ 'En tant que CDA, vous pouvez agir pour vous-même ou pour un tiers enrôlé (RG5).' | t }}</span></div>
            </div>
          }

          @if (nature() !== 'INITIAL') {
            <h2>{{ 'Attestation concernée' | t }}</h2>
            <div class="scroll-x">
              <table class="datagrid">
                <thead><tr><th></th><th>{{ 'N° d\\'inscription' | t }}</th><th>{{ 'Opérateur' | t }}</th><th>{{ 'Dossier' | t }}</th><th>{{ 'Valable jusqu\\'au' | t }}</th></tr></thead>
                <tbody>
                  @for (x of titresDuType(); track x.docNo) {
                    <tr (click)="source.set(x.dossierNo); niu.set(x.applicantNiu)" [class.sel]="source() === x.dossierNo">
                      <td><input type="radio" name="src" [checked]="source() === x.dossierNo" /></td>
                      <td class="mono">{{ x.docNo }}</td><td>{{ x.applicantName }} <span class="muted small mono">{{ x.applicantNiu }}</span></td>
                      <td class="mono">{{ x.dossierNo }}</td><td>{{ x.validUntil | date: 'dd/MM/yyyy' }}</td>
                    </tr>
                  } @empty { <tr><td colspan="5" class="empty small">{{ 'Aucune attestation valide pour ce type de demande.' | t }}</td></tr> }
                </tbody>
              </table>
            </div>
          }

          <div class="row">
            <button class="btn primary" (click)="creer()" [disabled]="occupe() || (nature() === 'AMENDMENT' && !source())">
              <nx-icon name="file" /> {{ (occupe() ? 'Création…' : 'Créer le brouillon') | t }}</button>
            <a routerLink="/demandes" class="btn ghost">{{ 'Annuler' | t }}</a>
          </div>
        }
      </div>
    }
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 18px; }
    .choix { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(300px, 100%), 1fr)); gap: 10px; }
    .option { display: flex; gap: 12px; align-items: center; padding: 14px; border: 1px solid var(--line); border-radius: 14px; background: var(--surface);
      text-align: left; font: inherit; color: var(--text); cursor: pointer; }
    .option:hover { border-color: var(--accent); }
    .option.on { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
    .option b, .option span.muted { display: block; }
    .option .ic { width: 40px; height: 40px; border-radius: 12px; display: grid; place-items: center; background: var(--gradient); color: #fff; flex-shrink: 0; }
    tr.sel td { background: var(--accent-soft); }
  `],
})
export class NouvelleDemandePage {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthService);
  protected readonly d = DROITS;
  protected readonly natures = NATURES;
  protected readonly annee = new Date().getFullYear();
  readonly procedure = input<string>();

  protected readonly catalogue = signal<Procedure[]>([]);
  protected readonly code = signal<string | null>(null);
  protected readonly type = signal<string | null>(null);
  protected readonly nature = signal('INITIAL');
  protected readonly niu = signal('');
  protected readonly source = signal<string | null>(null);
  protected readonly titres = signal<Titre[]>([]);
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);

  protected readonly procedureChoisie = computed(() => this.catalogue().find(p => p.code === this.code()) ?? null);
  protected readonly typeChoisi = computed(() => this.procedureChoisie()?.requestTypes.find(t => t.code === this.type()) ?? null);
  protected readonly titresDuType = computed(() => this.titres().filter(t => t.requestType === this.type()));

  constructor() {
    this.api.catalogue().subscribe({
      next: c => { this.catalogue.set(c); if (!this.code() && c.length === 1) this.choisirProcedure(c[0].code); },
      error: e => this.erreur.set(erreurs(e).join(' ')),
    });
    effect(() => { const p = this.procedure(); if (p) this.choisirProcedure(p); });
  }

  protected choisirProcedure(code: string): void {
    this.code.set(code);
    this.type.set(null);
    this.api.titres(code).subscribe({ next: t => this.titres.set(t), error: () => this.titres.set([]) });
  }

  protected choisirType(t: RequestType): void {
    this.type.set(t.code);
    if (!t.natures.includes(this.nature())) this.nature.set('INITIAL');
    this.source.set(null);
  }

  protected choisirNature(n: string): void {
    this.nature.set(n);
    this.source.set(null);
    const t = this.titresDuType();
    if (n !== 'INITIAL' && t.length === 1) { this.source.set(t[0].dossierNo); this.niu.set(t[0].applicantNiu); }
  }

  protected montant(p: Procedure, type: string, nature: string): number {
    return p.fees.filter(f => (!f.requestTypes.length || f.requestTypes.includes(type)) && (!f.natures.length || f.natures.includes(nature)))
      .reduce((s, f) => s + Number(f.amount), 0);
  }

  protected creer(): void {
    this.occupe.set(true);
    this.erreur.set(null);
    this.api.creer({ procedure: this.code()!, requestType: this.type()!, nature: this.nature(), niu: this.niu().trim() || null, sourceDossierNo: this.source() })
      .subscribe({
        next: d => this.router.navigate(['/demandes', d.id]),
        error: e => { this.occupe.set(false); this.erreur.set(erreurs(e).map(m => this.i18n.t(m)).join(' ')); },
      });
  }
}
