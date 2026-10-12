import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Api, Constat, Evenement, VersionComplete } from '../../core/api';
import { AuthService, DROITS } from '../../core/auth.service';
import { I18N } from '../../core/i18n';
import { enregistrer, erreurs } from '../../core/session';
import { Cycle } from '../../shared/cycle';
import { Icon } from '../../shared/icon';

/**
 * Contrôles du Compliance Agent et cycle de vie : soumettre (sans erreur bloquante), approuver (par une autre personne
 * que l'auteur, RG-GOV-04) ou renvoyer, publier (paquet figé, empreinte SHA-256), retirer ; journal chaîné de la version.
 */
@Component({
  selector: 'nx-onglet-publication',
  imports: [DatePipe, Icon, Cycle, ...I18N],
  template: `
    <div class="card pad stack">
      <div class="between"><div class="card-title"><nx-icon name="shield" /><h2>{{ 'Contrôles de conformité' | t }}</h2></div>
        <button class="btn sm" (click)="controler()"><nx-icon name="refresh" [size]="15" /> {{ 'Relancer' | t }}</button></div>
      <div class="row"><span class="chip danger">{{ '{0} erreur(s)' | t: nb('error') }}</span><span class="chip warn">{{ '{0} alerte(s)' | t: nb('warning') }}</span><span class="chip">{{ '{0} information(s)' | t: nb('info') }}</span></div>
      @for (c of constats(); track $index) {
        <div class="constat" [class]="'constat ' + c.severity"><span class="code-tag">{{ c.rule }}</span> <span class="grow">{{ c.message }}</span> <span class="muted small mono">{{ c.target }}</span></div>
      }
    </div>
    <div class="card pad stack">
      <div class="card-title"><nx-icon name="route" /><h2>{{ 'Cycle de vie' | t }}</h2> <nx-cycle [statut]="v().version.lifecycleStatus" /></div>
      <dl class="dl">
        <dt>{{ 'Soumise' | t }}</dt><dd>{{ v().version.submittedBy ?? '—' }} @if (v().version.submittedAt) { · {{ v().version.submittedAt | date: 'dd/MM/yyyy HH:mm' }} }</dd>
        <dt>{{ 'Approuvée' | t }}</dt><dd>{{ v().version.approvedBy ?? '—' }} @if (v().version.approvedAt) { · {{ v().version.approvedAt | date: 'dd/MM/yyyy HH:mm' }} }</dd>
        <dt>{{ 'Publiée' | t }}</dt><dd>{{ v().version.publishedBy ?? '—' }} @if (v().version.publishedAt) { · {{ v().version.publishedAt | date: 'dd/MM/yyyy HH:mm' }} }</dd>
        @if (v().version.packageHash) { <dt>{{ 'Empreinte du paquet' | t }}</dt><dd class="mono small">{{ v().version.packageHash }}</dd> }
      </dl>
      <div class="field"><label for="cm">{{ 'Commentaire / motif' | t }}</label><input id="cm" class="input" [value]="commentaire()" (input)="commentaire.set($any($event.target).value)" /></div>
      <div class="row">
        @switch (v().version.lifecycleStatus) {
          @case ('draft') { @if (auth.a(d.modeliser)) { <button class="btn primary" (click)="action('submit')" [disabled]="occupe() || nb('error') > 0"><nx-icon name="send" /> {{ 'Soumettre à revue' | t }}</button> } }
          @case ('in_review') { @if (auth.a(d.approuver)) {
            <button class="btn primary" (click)="action('approve')" [disabled]="occupe()"><nx-icon name="check" /> {{ 'Approuver' | t }}</button>
            <button class="btn danger" (click)="action('reject')" [disabled]="occupe()"><nx-icon name="back" /> {{ 'Renvoyer en brouillon' | t }}</button> } }
          @case ('approved') { @if (auth.a(d.publier)) { <button class="btn primary" (click)="action('publish')" [disabled]="occupe()"><nx-icon name="upload" /> {{ 'Publier' | t }}</button> } }
          @case ('published') { @if (auth.a(d.publier)) { <button class="btn danger" (click)="action('retire')" [disabled]="occupe()"><nx-icon name="archive" /> {{ 'Retirer' | t }}</button> } }
        }
        <button class="btn" (click)="telechargerPaquet()"><nx-icon name="download" /> {{ (v().version.packageHash ? 'Paquet publié (JSON)' : 'Aperçu du paquet (JSON)') | t }}</button>
      </div>
      @if (v().version.lifecycleStatus === 'in_review' && v().version.submittedBy === auth.utilisateur()?.username) {
        <div class="alert warn small">{{ 'Vous avez soumis cette version : l\\'approbation doit venir d\\'une autre personne (RG-GOV-04).' | t }}</div>
      }
      @if (v().version.lifecycleStatus === 'published') { <div class="alert info small">{{ 'Version publiée : déployez-la depuis l\\'administration des e-Services.' | t }}</div> }
      @if (erreur()) { <div class="alert err"><ul>@for (e of erreurListe(); track $index) { <li>{{ e }}</li> }</ul></div> }
    </div>
    <div class="card pad stack">
      <div class="card-title"><nx-icon name="history" /><h2>{{ 'Journal de la version' | t }}</h2></div>
      <div class="scroll-x"><table class="datagrid"><thead><tr><th>{{ 'Date' | t }}</th><th>{{ 'Acteur' | t }}</th><th>{{ 'Action' | t }}</th><th>{{ 'Motif' | t }}</th><th>{{ 'Empreinte' | t }}</th></tr></thead>
        <tbody>@for (e of journal(); track e.id) {
          <tr><td class="small">{{ e.occurredAt | date: 'dd/MM HH:mm:ss' }}</td><td>{{ e.actor }} <span class="muted small">{{ e.channel }}</span></td><td><span class="code-tag">{{ e.actionCode }}</span></td>
            <td class="small">{{ e.reason }}</td><td class="mono small muted">{{ e.rowHash.slice(0, 12) }}…</td></tr>
        }</tbody></table></div>
    </div>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 16px; }
    .constat { display: flex; gap: 10px; align-items: center; padding: 7px 10px; border-radius: 9px; font-size: 13px; }
    .constat.error { background: var(--danger-soft); } .constat.warning { background: var(--warn-soft); } .constat.info { background: var(--surface-2); }
    .datagrid tbody tr { cursor: default; }
  `],
})
export class OngletPublication {
  private readonly api = inject(Api);
  protected readonly auth = inject(AuthService);
  protected readonly d = DROITS;
  readonly v = input.required<VersionComplete>();
  readonly actualiser = output<void>();
  protected readonly constats = signal<Constat[]>([]);
  protected readonly journal = signal<Evenement[]>([]);
  protected readonly commentaire = signal('');
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly erreurListe = computed(() => (this.erreur() ?? '').split('\n').filter(Boolean));

  constructor() {
    effect(() => { this.v(); this.controler(); this.api.journalVersion(this.v().version.id).subscribe(j => this.journal.set(j)); });
  }

  protected nb(s: string): number {
    return this.constats().filter(c => c.severity === s).length;
  }

  protected controler(): void {
    this.api.controles(this.v().version.id).subscribe({ next: c => this.constats.set(c), error: e => this.erreur.set(erreurs(e).join('\n')) });
  }

  protected action(a: 'submit' | 'approve' | 'reject' | 'publish' | 'retire'): void {
    this.occupe.set(true);
    this.erreur.set(null);
    this.api.cycle(this.v().version.id, a, this.commentaire()).subscribe({
      next: () => { this.occupe.set(false); this.commentaire.set(''); this.actualiser.emit(); },
      error: e => { this.occupe.set(false); this.erreur.set(erreurs(e).join('\n')); },
    });
  }

  protected telechargerPaquet(): void {
    this.api.paquet(this.v().version.id).subscribe(p => enregistrer(new Blob([p], { type: 'application/json' }),
      `${this.v().procedure.code.toLowerCase()}-v${this.v().version.versionNo}.package.json`));
  }
}
