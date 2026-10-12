import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { JsonPipe } from '@angular/common';
import { Api, VersionComplete } from '../../core/api';
import { AuthService } from '../../core/auth.service';
import { I18N } from '../../core/i18n';
import { erreurs } from '../../core/session';
import { Colonne, Grille } from '../../shared/grille';
import { Icon } from '../../shared/icon';
import { Enregistrement } from './general';

/**
 * Règles de la procédure : DRL Drools (agenda-groups « validation » et « frais ») compilé et testé ici avec les mêmes
 * faits que le Runtime (Demande, Antecedent, Resultat), et règles de gestion en clair reliées à leur règle DRL.
 */
@Component({
  selector: 'nx-onglet-regles',
  imports: [JsonPipe, Icon, Grille, ...I18N],
  template: `
    <div class="card pad stack">
      <div class="between">
        <div class="card-title"><nx-icon name="scale" /><h2>{{ 'Règles DRL (Drools)' | t }}</h2></div>
        <div class="row">
          @if (v().rules) { <a class="btn sm" [href]="auth.lien(api.urlDrl(v().version.id))"><nx-icon name="download" [size]="15" /> .drl</a> }
          @if (modifiable()) { <button class="btn primary sm" (click)="enregistrerDrl()" [disabled]="drl() === (v().rules?.drl ?? '')"><nx-icon name="save" [size]="15" /> {{ 'Enregistrer' | t }}</button> }
        </div>
      </div>
      <p class="muted small">{{ 'Faits : Demande (typeDemande, nature, texte(code), entier(code), aPiece(code), lignes(groupe), mandataire, titulaire, anneeCourante), Antecedent (dossiers du même demandeur), global Resultat (erreur, frais, variable). Les sections « generated » sont régénérées par le Rules Agent.' | t }}</p>
      <textarea class="input code" [value]="drl()" (input)="drl.set($any($event.target).value)" [readonly]="!modifiable()" spellcheck="false"></textarea>
    </div>
    <div class="card pad stack">
      <div class="card-title"><nx-icon name="play" /><h2>{{ 'Banc d\\'essai des règles' | t }}</h2></div>
      <div class="form-grid">
        <div class="field"><label for="ty">{{ 'Type de demande' | t }}</label><select id="ty" class="input" (change)="type.set($any($event.target).value)">
          @for (t of v().requestTypes; track t.code) { <option [value]="t.code" [selected]="type() === t.code">{{ t.code }} — {{ t.labelFr }}</option> }</select></div>
        <div class="field"><label for="na">{{ 'Nature' | t }}</label><select id="na" class="input" (change)="nature.set($any($event.target).value)">
          @for (n of natures(); track n) { <option [value]="n" [selected]="nature() === n">{{ n }}</option> }</select></div>
        <div class="field"><label class="check"><input type="checkbox" [checked]="titulaire()" (change)="titulaire.set($any($event.target).checked)" /> {{ 'Titulaire (dépose pour lui-même)' | t }}</label>
          <label class="check"><input type="checkbox" [checked]="mandataire()" (change)="mandataire.set($any($event.target).checked)" /> {{ 'Mandataire (CDA)' | t }}</label></div>
        <div class="field"><label for="pi">{{ 'Pièces fournies' | t }}</label><input id="pi" class="input mono" [value]="pieces()" (input)="pieces.set($any($event.target).value)" placeholder="P1, P2, P3" /></div>
        <div class="field full"><label for="ch">{{ 'Champs et groupes (JSON)' | t }}</label>
          <textarea id="ch" class="input code" style="min-height: 120px" [value]="champs()" (input)="champs.set($any($event.target).value)"></textarea></div>
      </div>
      <div class="row"><button class="btn" (click)="tester()"><nx-icon name="play" /> {{ 'Compiler et évaluer' | t }}</button></div>
      @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
      @if (resultat(); as r) {
        @if (r['compiled'] === false) { <div class="alert err"><ul>@for (e of $any(r['errors']); track $index) { <li>{{ e }}</li> }</ul></div> }
        @else {
          <div class="grid k2">
            @for (p of ['validation', 'frais']; track p) {
              <div class="bloc"><h3>{{ p }}</h3>
                @if ($any(r)[p]; as x) {
                  @for (e of x.errors; track $index) { <div class="alert err small">{{ e.champ }} — {{ e.message }}</div> }
                  @for (f of x.fees; track $index) { <div class="small">{{ f.beneficiaire }} · {{ f.libelle }} : <b>{{ f.montant }}</b></div> }
                  @if (p === 'frais') { <div><b>{{ 'Total' | t }} : {{ x.total }}</b></div> }
                  <div class="muted small">{{ 'Règles déclenchées' | t }} : {{ x.firedRules | json }}</div>
                }
              </div>
            }
          </div>
        }
      }
    </div>
    <div class="card pad stack">
      <div class="card-title"><nx-icon name="list" /><h2>{{ 'Règles de gestion (RG / RM)' | t }}</h2></div>
      <nx-grille [colonnes]="colRegles" [donnees]="$any(v().rules?.businessRules ?? [])" [lectureSeule]="!modifiable()"
        (enregistrer)="enregistrer.emit({ partie: 'rules', corps: { drl: v().rules?.drl ?? drl(), businessRules: $event.lignes }, motif: $event.motif })" />
    </div>
  `,
  styles: [`:host { display: flex; flex-direction: column; gap: 16px; } .bloc { border: 1px solid var(--line); border-radius: 12px; padding: 12px; display: flex; flex-direction: column; gap: 6px; }`],
})
export class OngletRegles {
  protected readonly api = inject(Api);
  protected readonly auth = inject(AuthService);
  readonly v = input.required<VersionComplete>();
  readonly modifiable = input(false);
  readonly enregistrer = output<Enregistrement>();
  protected readonly drl = signal('');
  protected readonly type = signal('');
  protected readonly nature = signal('INITIAL');
  protected readonly titulaire = signal(true);
  protected readonly mandataire = signal(false);
  protected readonly pieces = signal('');
  protected readonly champs = signal('{\n  "foreignerCitizen": "C",\n  "renewYear": "' + (new Date().getFullYear() + 1) + '"\n}');
  protected readonly resultat = signal<Record<string, unknown> | null>(null);
  protected readonly erreur = signal<string | null>(null);
  protected readonly natures = computed(() => this.v().requestTypes.find(t => t.code === this.type())?.natures ?? ['INITIAL']);
  protected readonly colRegles: Colonne[] = [
    { cle: 'code', libelle: 'Code', mono: true, largeur: 130 }, { cle: 'text', libelle: 'Règle', type: 'textarea', largeur: 420 },
    { cle: 'source', libelle: 'Source', largeur: 180 }, { cle: 'drlRule', libelle: 'Règle DRL', largeur: 280 }];

  constructor() {
    effect(() => {
      this.drl.set(this.v().rules?.drl ?? '');
      if (!this.type() && this.v().requestTypes.length) this.type.set(this.v().requestTypes[0].code);
    });
  }

  protected enregistrerDrl(): void {
    this.enregistrer.emit({ partie: 'rules', corps: { drl: this.drl(), businessRules: this.v().rules?.businessRules ?? [] }, motif: 'Modification des règles DRL' });
  }

  protected tester(): void {
    let donnees: Record<string, unknown>;
    try {
      donnees = JSON.parse(this.champs() || '{}');
    } catch {
      this.erreur.set('JSON des champs invalide.');
      return;
    }
    const groupes: Record<string, unknown> = {};
    const champs: Record<string, unknown> = {};
    for (const [k, val] of Object.entries(donnees)) (Array.isArray(val) ? groupes : champs)[k] = val;
    this.erreur.set(null);
    this.api.testerRegles(this.v().version.id, {
      drl: this.drl(), typeDemande: this.type(), nature: this.nature(), champs, groupes, titulaire: this.titulaire(), mandataire: this.mandataire(),
      pieces: this.pieces().split(',').map(s => s.trim()).filter(Boolean), antecedents: [],
    }).subscribe({ next: r => this.resultat.set(r), error: e => this.erreur.set(erreurs(e).join(' ')) });
  }
}
