import { Component, effect, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Router } from '@angular/router';
import { Api, Verification } from '../core/api';
import { I18N, I18n, LANGUES } from '../core/i18n';
import { erreurs } from '../core/session';
import { Icon } from '../shared/icon';

/** Vérification publique d'un livrable (cible du code QR) : authenticité du cachet, statut et validité. */
@Component({
  selector: 'nx-verifier',
  imports: [DatePipe, Icon, ...I18N],
  template: `
    <div class="carte card">
      <div class="haut">
        <img src="nexora-logo.webp" alt="NEXORA" class="logo" />
        <div class="langues">@for (l of langues; track l.code) { <button class="btn sm" [class.on]="i18n.langue() === l.code" (click)="i18n.langue.set(l.code)">{{ l.code.toUpperCase() }}</button> }</div>
      </div>
      <h1>{{ 'Vérifier un document délivré' | t }}</h1>
      <form class="row" (submit)="$event.preventDefault(); aller()">
        <input class="input mono grow" [value]="numero()" (input)="numero.set($any($event.target).value.toUpperCase())" [placeholder]="'Numéro du document' | t" />
        <button class="btn primary"><nx-icon name="search" /> {{ 'Vérifier' | t }}</button>
      </form>
      @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
      @if (r(); as v) {
        @if (!v.found) {
          <div class="alert err"><nx-icon name="ban" /> {{ 'Aucun document ne porte ce numéro.' | t }}</div>
        } @else {
          <div class="verdict" [class.ok]="v.valid" [class.ko]="!v.valid">
            <nx-icon [name]="v.valid ? 'shield' : 'alert'" [size]="30" />
            <div><b>{{ (v.valid ? 'Document authentique et en cours de validité' : 'Document non valide') | t }}</b>
              <div class="small">{{ (v.sealValid ? 'Cachet électronique vérifié' : 'Cachet électronique invalide') | t }} · {{ v.status }}</div></div>
          </div>
          <dl class="dl">
            <dt>{{ 'Numéro' | t }}</dt><dd class="mono">{{ v.docNo }} @if ((v.versionNo ?? 1) > 1) { ({{ 'version' | t }} {{ v.versionNo }}) }</dd>
            <dt>{{ 'Titulaire' | t }}</dt><dd>{{ v.holderName }} <span class="mono muted">{{ v.holderNiu }}</span></dd>
            <dt>{{ 'Objet' | t }}</dt><dd>{{ v.requestType }}</dd>
            <dt>{{ 'Validité' | t }}</dt><dd>{{ v.validFrom | date: 'dd/MM/yyyy' }} → {{ v.validUntil ? (v.validUntil | date: 'dd/MM/yyyy') : '—' }}</dd>
            <dt>{{ 'Signé par' | t }}</dt><dd>{{ v.signerName }} · {{ v.signedAt | date: 'dd/MM/yyyy HH:mm' }}</dd>
          </dl>
        }
      }
      <p class="muted small pied">NEXORA · {{ 'Guichet unique du commerce extérieur' | t }}</p>
    </div>
  `,
  styles: [`
    :host { display: flex; justify-content: center; width: 100%; }
    .carte { width: min(560px, 100%); padding: 26px; display: flex; flex-direction: column; gap: 14px; align-self: flex-start; }
    .haut { display: flex; justify-content: space-between; align-items: flex-start; }
    .logo { width: 180px; margin: -10px 0 -6px -8px; }
    :root[data-theme="dark"] .logo { background: #fff; border-radius: 10px; }
    .langues { display: flex; gap: 2px; padding: 2px; border: 1px solid var(--line); border-radius: 10px; }
    .langues .btn { height: 28px; border: 0; padding: 0 9px; font-size: 12px; }
    .langues .btn.on { background: var(--navy); color: #fff; }
    .verdict { display: flex; gap: 14px; align-items: center; padding: 14px; border-radius: 14px; }
    .verdict.ok { background: var(--ok-soft); color: var(--ok); } .verdict.ko { background: var(--danger-soft); color: var(--danger); }
    .pied { text-align: center; }
  `],
})
export class VerifierPage {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  protected readonly i18n = inject(I18n);
  protected readonly langues = LANGUES;
  readonly no = input<string>();
  protected readonly numero = signal('');
  protected readonly r = signal<Verification | null>(null);
  protected readonly erreur = signal<string | null>(null);

  constructor() {
    effect(() => {
      const n = this.no();
      if (!n) return;
      this.numero.set(n);
      this.api.verifier(n).subscribe({ next: v => { this.r.set(v); this.erreur.set(null); }, error: e => this.erreur.set(erreurs(e).join(' ')) });
    });
  }

  protected aller(): void {
    if (this.numero().trim()) this.router.navigate(['/verifier', this.numero().trim()]);
  }
}
