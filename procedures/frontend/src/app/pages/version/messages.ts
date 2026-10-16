import { Component, computed, input, output, signal } from '@angular/core';
import { Flow, FlowMessage, MessageDef, MessageField, VersionComplete } from '../../core/api';
import { I18N } from '../../core/i18n';
import { Colonne, Grille } from '../../shared/grille';
import { Icon } from '../../shared/icon';
import { Enregistrement } from './general';

/** Formats de contenu d'un message (contrat) ou d'un échange dans un flux. */
const FORMATS = ['JSON', 'XML', 'CSV', 'TXT', 'EDIFACT', 'PDF'];

/**
 * Catalogue de messages (contrats réutilisables : type, format JSON / XML / CSV / TXT, élément racine, champs et chemins)
 * et flux (usage ordonné des messages entre acteurs) — le contrat est séparé de son usage (dossier v4.2, §10.3).
 */
@Component({
  selector: 'nx-onglet-messages',
  imports: [Icon, Grille, ...I18N],
  template: `
    <div class="card pad stack">
      <div class="card-title"><nx-icon name="mail" /><h2>{{ 'Catalogue de messages' | t }}</h2></div>
      <nx-grille [colonnes]="colMessages" [donnees]="$any(v().messages)" [modele]="{ messageType: 'NOTIFICATION', contentFormat: 'JSON', sensitivity: 'internal', fields: [] }"
        [lectureSeule]="!modifiable()" (choisie)="message.set($any($event)['code'])"
        (enregistrer)="enregistrer.emit({ partie: 'messages', corps: $event.lignes, motif: $event.motif })" />
    </div>
    @if (messageChoisi(); as m) {
      <div class="card pad stack">
        <div class="card-title"><nx-icon name="code" /><h2>{{ 'Champs du message {0}' | t: m.code }}</h2></div>
        <p class="muted small">{{ 'Chemin : $.a.b ou $.liste[*].x (JSON), Racine/a/b (XML), nom de colonne (CSV) ou clé (TXT). Sans champ de formulaire, la valeur vient de l\\'enveloppe (CUR, avis, livrable…).' | t }}</p>
        <nx-grille [colonnes]="colChamps()" [donnees]="$any(m.fields)" [modele]="{ mandatory: false, indexed: false }" [lectureSeule]="!modifiable()"
          (enregistrer)="enregistrerChamps(m, $any($event.lignes), $event.motif)" />
      </div>
    } @else { <p class="muted small">{{ 'Sélectionnez un message pour éditer ses champs.' | t }}</p> }

    <div class="card pad stack">
      <div class="card-title"><nx-icon name="route" /><h2>{{ 'Flux' | t }}</h2></div>
      <nx-grille [colonnes]="colFlux" [donnees]="$any(v().flows)" [modele]="{ messages: [] }" [lectureSeule]="!modifiable()" (choisie)="flux.set($any($event)['code'])"
        (enregistrer)="enregistrer.emit({ partie: 'flows', corps: $event.lignes, motif: $event.motif })" />
    </div>
    @if (fluxChoisi(); as f) {
      <div class="card pad stack">
        <div class="card-title"><nx-icon name="send" /><h2>{{ 'Messages du flux {0}' | t: f.code }}</h2></div>
        <nx-grille [colonnes]="colUsages()" [donnees]="$any(f.messages)" [modele]="{ interactionPattern: 'ASYNC' }" [lectureSeule]="!modifiable()"
          (enregistrer)="enregistrerUsages(f, $any($event.lignes), $event.motif)" />
      </div>
    } @else { <p class="muted small">{{ 'Sélectionnez un flux pour éditer ses messages (émetteur, destinataire, rang, réponse, délai, format de l\\'échange).' | t }}</p> }
  `,
  styles: [`:host { display: flex; flex-direction: column; gap: 16px; }`],
})
export class OngletMessages {
  readonly v = input.required<VersionComplete>();
  readonly modifiable = input(false);
  readonly enregistrer = output<Enregistrement>();
  protected readonly message = signal<string | null>(null);
  protected readonly flux = signal<string | null>(null);
  protected readonly messageChoisi = computed(() => this.v().messages.find(m => m.code === this.message()) ?? null);
  protected readonly fluxChoisi = computed(() => this.v().flows.find(f => f.code === this.flux()) ?? null);

  protected readonly colMessages: Colonne[] = [
    { cle: 'code', libelle: 'Code', mono: true, largeur: 210 }, { cle: 'labelFr', libelle: 'Libellé (FR)', largeur: 230 },
    { cle: 'messageType', libelle: 'Type', type: 'select', options: ['REQUEST', 'RESPONSE', 'NOTIFICATION', 'DECISION', 'PAYMENT', 'ACKNOWLEDGEMENT', 'ERROR', 'REPORT', 'DOCUMENT'].map(x => ({ valeur: x, libelle: x })), largeur: 150 },
    { cle: 'contentFormat', libelle: 'Format', type: 'select', options: FORMATS.map(x => ({ valeur: x, libelle: x })), largeur: 100 },
    { cle: 'rootElement', libelle: 'Élément racine', mono: true, largeur: 160 }, { cle: 'standardRef', libelle: 'Référence normative', largeur: 230 },
    { cle: 'sensitivity', libelle: 'Sensibilité', type: 'select', options: ['public', 'internal', 'confidential', 'restricted', 'personal_data'].map(x => ({ valeur: x, libelle: x })), largeur: 140 },
    { cle: 'description', libelle: 'Description', type: 'textarea', largeur: 260 }];
  protected readonly colChamps = computed<Colonne[]>(() => [
    { cle: 'fieldCode', libelle: 'Champ du formulaire', type: 'select', options: this.v().fields.map(f => ({ valeur: f.code, libelle: f.code })), largeur: 220 },
    { cle: 'path', libelle: 'Chemin', mono: true, largeur: 340 }, { cle: 'mandatory', libelle: 'Obligatoire', type: 'bool', largeur: 90 },
    { cle: 'indexed', libelle: 'Indexé', type: 'bool', largeur: 80 }]);
  protected readonly colFlux: Colonne[] = [
    { cle: 'code', libelle: 'Code', mono: true, largeur: 160 }, { cle: 'labelFr', libelle: 'Libellé (FR)', largeur: 280 }, { cle: 'labelEn', libelle: 'Libellé (EN)', largeur: 220 }];
  protected readonly colUsages = computed<Colonne[]>(() => {
    const acteurs = this.v().actors.map(a => ({ valeur: a.code, libelle: a.code }));
    return [
      { cle: 'seq', libelle: 'Rang', type: 'number', largeur: 70 },
      { cle: 'messageCode', libelle: 'Message', type: 'select', options: this.v().messages.map(m => ({ valeur: m.code, libelle: m.code })), largeur: 230 },
      { cle: 'senderActor', libelle: 'Émetteur', type: 'select', options: acteurs, largeur: 170 }, { cle: 'receiverActor', libelle: 'Destinataire', type: 'select', options: acteurs, largeur: 170 },
      { cle: 'interactionPattern', libelle: 'Interaction', type: 'select', options: ['SYNC', 'ASYNC', 'NOTIFY'].map(x => ({ valeur: x, libelle: x })), largeur: 110 },
      { cle: 'contentFormat', libelle: 'Format de l\'échange', type: 'select', options: FORMATS.map(x => ({ valeur: x, libelle: x })), largeur: 130,
        aide: 'Vide : format du message dans le catalogue ; sinon format propre à cet échange (ex. EDIFACT vers la douane).' },
      { cle: 'responseToSeq', libelle: 'Réponse au rang', type: 'number', largeur: 110 }, { cle: 'timeoutHours', libelle: 'Délai (h)', type: 'number', largeur: 90 }];
  });

  protected enregistrerChamps(m: MessageDef, champs: MessageField[], motif: string): void {
    const messages = this.v().messages.map(x => x.code === m.code ? { ...x, fields: champs.map((c, i) => ({ ...c, seq: i + 1, fieldCode: c.fieldCode || null })) } : x);
    this.enregistrer.emit({ partie: 'messages', corps: messages, motif });
  }

  protected enregistrerUsages(f: Flow, usages: FlowMessage[], motif: string): void {
    const flux = this.v().flows.map(x => x.code === f.code ? { ...x, messages: usages.map((u, i) => ({ ...u, seq: u.seq || i + 1, contentFormat: u.contentFormat || null })) } : x);
    this.enregistrer.emit({ partie: 'flows', corps: flux, motif });
  }
}
