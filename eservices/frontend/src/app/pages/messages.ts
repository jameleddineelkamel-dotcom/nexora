import { Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Api, Message } from '../core/api';
import { I18N } from '../core/i18n';
import { erreurs } from '../core/session';
import { Icon } from '../shared/icon';
import { MessageDrawer } from './dossier/message-drawer';

/** Journal des messages échangés (JSON, XML, CSV, TXT) : enveloppe, état, contenu, valeurs indexées. */
@Component({
  selector: 'nx-messages',
  imports: [RouterLink, DatePipe, Icon, MessageDrawer, ...I18N],
  template: `
    <div class="page-head">
      <div><h1>{{ 'Messages' | t }}</h1><p class="muted">{{ 'Chaque message est l\\'occurrence d\\'un message du catalogue publié, conforme à son usage dans un flux ; son contenu est conservé avec son empreinte.' | t }}</p></div>
    </div>
    <div class="card pad row filtres">
      <div class="field"><label for="c">{{ 'Message' | t }}</label><input id="c" class="input mono" [value]="code()" (input)="code.set($any($event.target).value.toUpperCase())" [placeholder]="'Code du message' | t" /></div>
      <div class="field"><label for="f">{{ 'Format' | t }}</label>
        <select id="f" class="input" (change)="format.set($any($event.target).value)"><option value="">{{ 'Tous' | t }}</option>
          @for (x of ['JSON', 'XML', 'CSV', 'TXT']; track x) { <option [value]="x">{{ x }}</option> }</select></div>
      <div class="field"><label for="e">{{ 'État' | t }}</label>
        <select id="e" class="input" (change)="etat.set($any($event.target).value)"><option value="">{{ 'Tous' | t }}</option>
          @for (x of etats; track x) { <option [value]="x">{{ x }}</option> }</select></div>
      <button class="btn" (click)="charger()"><nx-icon name="search" /> {{ 'Filtrer' | t }}</button>
    </div>
    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
    <div class="card scroll-x">
      <table class="datagrid">
        <thead><tr><th>{{ 'Date' | t }}</th><th>{{ 'Message' | t }}</th><th>{{ 'Sens' | t }}</th><th>{{ 'Émetteur → destinataire' | t }}</th><th>{{ 'Dossier' | t }}</th><th>{{ 'Format' | t }}</th><th>{{ 'État' | t }}</th></tr></thead>
        <tbody>
          @for (m of messages(); track m.id) {
            <tr (click)="ouvert.set(m)">
              <td class="small">{{ m.createdAt | date: 'dd/MM HH:mm:ss' }}</td><td><b>{{ m.code }}</b><div class="muted small">{{ m.type }}</div></td>
              <td>{{ m.direction === 'IN' ? ('Entrant' | t) : ('Sortant' | t) }}</td><td class="small">{{ m.sender }} → {{ m.recipient }}</td>
              <td class="mono small">@if (m.dossierId) { <a [routerLink]="['/demandes', m.dossierId]" (click)="$event.stopPropagation()">{{ m.dossierNo }}</a> } @else { — }</td>
              <td><span class="chip info">{{ m.format }}</span></td>
              <td><span class="chip" [class.ok]="['OUT_SENT','OUT_ACKED','IN_PROCESSED'].includes(m.state)" [class.danger]="['OUT_ERROR','IN_REJECTED'].includes(m.state)">{{ m.state }}</span></td>
            </tr>
          } @empty { <tr><td colspan="7" class="empty">{{ 'Aucun message.' | t }}</td></tr> }
        </tbody>
      </table>
    </div>
    @if (ouvert(); as m) { <nx-message-drawer [message]="m" (fermer)="ouvert.set(null)" /> }
  `,
  styles: [`:host { display: flex; flex-direction: column; gap: 16px; } .filtres { align-items: flex-end; }`],
})
export class MessagesPage {
  private readonly api = inject(Api);
  protected readonly etats = ['OUT_CREATED', 'OUT_SENT', 'OUT_ACKED', 'OUT_ERROR', 'IN_RECEIVED', 'IN_VALID', 'IN_PROCESSED', 'IN_REJECTED'];
  protected readonly code = signal('');
  protected readonly format = signal('');
  protected readonly etat = signal('');
  protected readonly messages = signal<Message[]>([]);
  protected readonly ouvert = signal<Message | null>(null);
  protected readonly erreur = signal<string | null>(null);

  constructor() {
    this.charger();
  }

  protected charger(): void {
    this.api.messages({ code: this.code(), format: this.format(), state: this.etat(), limit: 300 }).subscribe({
      next: m => { this.messages.set(m); this.erreur.set(null); }, error: e => this.erreur.set(erreurs(e).join(' ')),
    });
  }
}
