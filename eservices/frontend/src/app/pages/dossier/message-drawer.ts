import { Component, inject, input, output, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Api, Message } from '../../core/api';
import { AuthService } from '../../core/auth.service';
import { I18N } from '../../core/i18n';
import { erreurs } from '../../core/session';
import { Icon } from '../../shared/icon';

/** Message échangé : enveloppe (type ebXML), contenu brut (JSON, XML, CSV, TXT), valeurs indexées et historique d'état. */
@Component({
  selector: 'nx-message-drawer',
  imports: [DatePipe, Icon, ...I18N],
  template: `
    <div class="overlay" (click)="fermer.emit()"></div>
    <aside class="drawer large" role="dialog" [attr.aria-label]="message().code">
      <header class="between">
        <div><h2>{{ message().code }}</h2>
          <span class="muted small">{{ message().direction === 'IN' ? ('Reçu' | t) : ('Émis' | t) }} · {{ message().createdAt | date: 'dd/MM/yyyy HH:mm:ss' }}</span></div>
        <div class="row">
          <span class="chip info">{{ message().format }}</span>
          <span class="chip" [class.ok]="['OUT_SENT','OUT_ACKED','IN_PROCESSED'].includes(message().state)" [class.danger]="['OUT_ERROR','IN_REJECTED'].includes(message().state)">{{ message().state }}</span>
          <button class="btn icon ghost" (click)="fermer.emit()" [attr.aria-label]="'Fermer' | t"><nx-icon name="x" /></button>
        </div>
      </header>
      <div class="body stack">
        <dl class="dl">
          <dt>MessageId</dt><dd class="mono">{{ message().id }}</dd>
          <dt>{{ 'Dossier (CUR)' | t }}</dt><dd class="mono">{{ message().dossierNo ?? '—' }}</dd>
          <dt>{{ 'Flux' | t }}</dt><dd>{{ message().flowCode ?? '—' }} @if (message().flowSeq) { · {{ 'rang' | t }} {{ message().flowSeq }} }</dd>
          <dt>{{ 'Émetteur → destinataire' | t }}</dt><dd>{{ message().sender }} → {{ message().recipient }}</dd>
          <dt>{{ 'Type' | t }}</dt><dd>{{ message().type }} · {{ message().contentType }}</dd>
          <dt>{{ 'Empreinte SHA-256' | t }}</dt><dd class="mono small">{{ message().sha256 }}</dd>
          <dt>{{ 'Taille' | t }}</dt><dd>{{ message().length | num }} {{ 'octets' | t }}</dd>
          <dt>{{ 'Corrélation' | t }}</dt><dd class="mono small">{{ message().correlationId }}</dd>
          @if (message().idempotencyKey) { <dt>{{ 'Clé d\\'idempotence' | t }}</dt><dd class="mono small">{{ message().idempotencyKey }}</dd> }
          @if (message().observation) { <dt>{{ 'Observation' | t }}</dt><dd>{{ message().observation }}</dd> }
        </dl>
        @if (message().validation?.errors?.length) {
          <div class="alert warn"><ul>@for (e of message().validation.errors!; track e) { <li>{{ e }}</li> }</ul></div>
        }
        <div class="between"><h3>{{ 'Contenu' | t }}</h3><a class="btn sm" [href]="auth.lien(api.urlContenu(message().id))"><nx-icon name="download" /> {{ 'Télécharger' | t }}</a></div>
        @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
        <pre class="code">{{ contenu() ?? '…' }}</pre>
        @if (valeurs().length) {
          <h3>{{ 'Valeurs indexées (payload_value)' | t }}</h3>
          <table class="datagrid">
            <thead><tr><th>{{ 'Chemin' | t }}</th><th>#</th><th>{{ 'Valeur' | t }}</th></tr></thead>
            <tbody>@for (v of valeurs(); track v.path + v.occurrence) { <tr><td class="mono small">{{ v.path }}</td><td>{{ v.occurrence }}</td><td>{{ v.valueText }}</td></tr> }</tbody>
          </table>
        }
        <h3>{{ 'Historique d\\'état' | t }}</h3>
        <div class="timeline">
          @for (s of etats(); track $index) {
            <div class="evt"><span class="pt"></span><div class="small"><b>{{ s.from ?? '∅' }} → {{ s.to }}</b> · {{ s.at | date: 'dd/MM HH:mm:ss' }} · {{ s.by }}
              @if (s.reason) { <div class="muted">{{ s.reason }}</div> }</div></div>
          }
        </div>
      </div>
    </aside>
  `,
  styles: [`.drawer.large { width: min(760px, 100vw); }`],
})
export class MessageDrawer {
  protected readonly api = inject(Api);
  protected readonly auth = inject(AuthService);
  readonly message = input.required<Message>();
  readonly fermer = output<void>();
  protected readonly contenu = signal<string | null>(null);
  protected readonly valeurs = signal<{ path: string; occurrence: number; valueText: string }[]>([]);
  protected readonly etats = signal<{ from: string | null; to: string; at: string; by: string; reason: string | null }[]>([]);
  protected readonly erreur = signal<string | null>(null);

  constructor() {
    queueMicrotask(() => {
      const id = this.message().id;
      this.api.contenu(id).subscribe({ next: c => this.contenu.set(c), error: e => this.erreur.set(erreurs(e).join(' ')) });
      this.api.message(id).subscribe({ next: m => { this.valeurs.set(m.values); this.etats.set(m.states); } });
    });
  }
}
