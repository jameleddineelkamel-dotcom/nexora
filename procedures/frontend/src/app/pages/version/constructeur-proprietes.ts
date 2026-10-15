import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { Api, Bie, CodeValue, Field, RequestType, Section } from '../../core/api';
import { I18N } from '../../core/i18n';
import { erreurs } from '../../core/session';
import { Icon } from '../../shared/icon';
import { ReglageChamp } from './ihm';

export const TYPES_CHAMP: { code: string; libelle: string; icone: string }[] = [
  { code: 'TEXT', libelle: 'Texte', icone: 'edit' }, { code: 'LONGTEXT', libelle: 'Texte long', icone: 'file' },
  { code: 'NUMBER', libelle: 'Nombre entier', icone: 'tag' }, { code: 'DECIMAL', libelle: 'Nombre décimal', icone: 'money' },
  { code: 'DATE', libelle: 'Date', icone: 'calendar' }, { code: 'YEAR', libelle: 'Année', icone: 'clock' },
  { code: 'CODE', libelle: 'Liste de codes', icone: 'list' }, { code: 'EMAIL', libelle: 'Adresse électronique', icone: 'mail' },
  { code: 'PHONE', libelle: 'Téléphone', icone: 'send' }, { code: 'BOOLEAN', libelle: 'Oui / Non', icone: 'check' },
  { code: 'GEOLOCATION', libelle: 'Localisation', icone: 'pin' },
];

/** Modification émise par le panneau : champ et réglages d'affichage complets, code d'origine (renommage). */
export interface ModificationChamp { ancienCode: string; champ: Field; reglage: ReglageChamp }

type Onglet = 'affichage' | 'donnees' | 'validation' | 'logique';
const CONDITION_TYPE = /^\s*typeDemande\s+in\s*\(([^)]*)\)\s*$/i;

/**
 * Propriétés du champ sélectionné dans le constructeur, à la manière de form.io : Affichage (libellés, aide, largeur,
 * texte indicatif), Données (type, liste, pré-remplissage, chemin BIE SCRDM), Validation (obligation, longueur, format,
 * règles DRL qui citent le champ) et Logique (types de demande, affichage conditionnel).
 */
@Component({
  selector: 'nx-proprietes-champ',
  imports: [Icon, ...I18N],
  template: `
    @let f = champ();
    <div class="tete">
      <div class="grow"><b>{{ f.labelFr || '—' }}</b> <span class="code-tag">{{ f.code }}</span></div>
      @if (!lectureSeule()) {
        <button class="btn icon ghost sm danger" (click)="supprimer.emit()" [attr.aria-label]="'Supprimer le champ' | t" [title]="'Supprimer le champ' | t"><nx-icon name="trash" [size]="15" /></button>
      }
    </div>
    <div class="sous-onglets" role="tablist">
      @for (o of onglets; track o.code) {
        <button type="button" role="tab" [class.on]="onglet() === o.code" [attr.aria-selected]="onglet() === o.code" (click)="onglet.set(o.code)">{{ o.libelle | t }}</button>
      }
    </div>
    <fieldset class="corps" [disabled]="lectureSeule()">
      @switch (onglet()) {
        @case ('affichage') {
          <div class="field"><label for="pc-lfr">{{ 'Libellé (FR)' | t }}</label><input id="pc-lfr" class="input" [value]="f.labelFr" (input)="poser('labelFr', val($event))" /></div>
          <div class="field"><label for="pc-len">{{ 'Libellé (EN)' | t }}</label><input id="pc-len" class="input" [value]="f.labelEn ?? ''" (input)="poser('labelEn', val($event) || null)" /></div>
          <div class="field"><label for="pc-hfr">{{ 'Aide (FR)' | t }}</label><textarea id="pc-hfr" class="input" rows="2" [value]="f.helpFr ?? ''" (input)="poser('helpFr', val($event) || null)"></textarea></div>
          <div class="field"><label for="pc-hen">{{ 'Aide (EN)' | t }}</label><textarea id="pc-hen" class="input" rows="2" [value]="f.helpEn ?? ''" (input)="poser('helpEn', val($event) || null)"></textarea></div>
          <div class="deux">
            <div class="field"><label for="pc-pfr">{{ 'Texte indicatif (FR)' | t }}</label><input id="pc-pfr" class="input" [value]="reglage().placeholderFr ?? ''" (input)="regler({ placeholderFr: val($event) || undefined })" /></div>
            <div class="field"><label for="pc-pen">{{ 'Texte indicatif (EN)' | t }}</label><input id="pc-pen" class="input" [value]="reglage().placeholderEn ?? ''" (input)="regler({ placeholderEn: val($event) || undefined })" /></div>
          </div>
          <div class="field"><label>{{ 'Largeur dans la grille' | t }}</label>
            <div class="largeurs" role="radiogroup">
              @for (l of largeurs; track l.n) {
                <button type="button" role="radio" class="largeur" [class.on]="(reglage().largeur ?? 0) === l.n" [attr.aria-checked]="(reglage().largeur ?? 0) === l.n"
                  (click)="regler({ largeur: l.n || undefined })"><span class="barre" [style.width.%]="l.n ? l.n * 25 : 25" [class.auto]="!l.n"></span>{{ l.libelle | t }}</button>
              }
            </div>
            <span class="muted small">{{ 'En colonnes du gabarit d\\'IHM ({0}) ; « Auto » : 1 colonne, toute la ligne pour un texte long ou une localisation.' | t: colonnes() }}</span>
          </div>
          <div class="field"><label for="pc-sec">{{ 'Section' | t }}</label>
            <select id="pc-sec" class="input" (change)="poser('sectionCode', val($event))">
              @for (s of sectionsTriees(); track s.code) { <option [value]="s.code" [selected]="s.code === f.sectionCode">{{ s.parentCode ? '— ' : '' }}{{ s.labelFr }} ({{ s.code }})</option> }
            </select></div>
          <div class="field"><label for="pc-code">{{ 'Code' | t }}</label>
            <input id="pc-code" class="input mono" [value]="f.code" (change)="renommer(val($event))" />
            @if (erreurCode()) { <span class="small err-txt">{{ erreurCode()! | t }}</span> }
            @else { <span class="muted small">{{ 'Clé de la donnée dans le dossier, les messages et les règles DRL : à changer avec prudence.' | t }}</span> }
          </div>
        }
        @case ('donnees') {
          <div class="field"><label for="pc-type">{{ 'Type' | t }}</label>
            <select id="pc-type" class="input" (change)="poser('dataType', val($event))">
              @for (t of types; track t.code) { <option [value]="t.code" [selected]="t.code === f.dataType">{{ t.libelle | t }} ({{ t.code }})</option> }
            </select></div>
          @if (f.dataType === 'CODE') {
            <div class="field"><label for="pc-ref">{{ 'Liste (REF_ / @)' | t }}</label>
              <input id="pc-ref" class="input mono" list="pc-refs" [value]="f.refdataTable ?? ''" (input)="poser('refdataTable', val($event).trim() || null)" placeholder="REF_COUNTRY" />
              <datalist id="pc-refs">@for (r of listesConnues(); track r) { <option [value]="r"></option> }</datalist>
              <span class="muted small">{{ 'Table REF_xxx du Référentiel Commun ou « @LIEUX_TRAITEMENT » (lieux du Runtime). Ignorée si des valeurs locales sont saisies.' | t }}</span></div>
            <div class="field"><label for="pc-val">{{ 'Valeurs locales' | t }}</label>
              <textarea id="pc-val" class="input mono" rows="4" [value]="valeursTexte()" (change)="poserValeurs(val($event))" placeholder="AS=Autorisation spéciale|Special authorisation"></textarea>
              <span class="muted small">{{ 'Une valeur par ligne : code=libellé FR|libellé EN.' | t }}</span></div>
          }
          <div class="field"><label for="pc-pre">{{ 'Pré-remplissage' | t }}</label>
            <input id="pc-pre" class="input mono" [value]="f.prefill ?? ''" (input)="poser('prefill', val($event).trim() || null)" />
            <span class="muted small">{{ 'Attribut du registre des opérateurs recopié à l\\'ouverture de la demande.' | t }}</span></div>
          <div class="checks">
            <label class="check"><input type="checkbox" [checked]="f.amendable" (change)="poser('amendable', coche($event))" /> {{ 'Amendable' | t }}</label>
            <label class="check"><input type="checkbox" [checked]="f.searchable" (change)="poser('searchable', coche($event))" /> {{ 'Critère de recherche' | t }}</label>
          </div>
          <div class="sep"></div>
          <div class="field"><label for="pc-bie">{{ 'Chemin BIE (SCRDM)' | t }}</label>
            <input id="pc-bie" class="input mono" [value]="f.biePath ?? ''" (input)="poser('biePath', val($event).trim() || null)" /></div>
          <div class="row"><input class="input grow" [value]="q()" (input)="q.set(val($event))" (keydown.enter)="chercher()" [placeholder]="'Assistant SCRDM : city name, gross weight…' | t" />
            <button type="button" class="btn sm" (click)="chercher()"><nx-icon name="search" [size]="14" /></button></div>
          @if (erreurBie()) { <div class="alert err small">{{ erreurBie() }}</div> }
          @if (resultats().length) {
            <div class="resultats">
              @for (b of resultats(); track b.abie + b.property) {
                <button type="button" class="bie" [class.on]="choisi() === b" (click)="choisirBie(b)"><b>{{ b.den }}</b><span class="muted small">{{ b.kind }} · {{ b.cardinality }}</span></button>
              }
            </div>
          }
          @if (choisi(); as b) {
            @if (b.definition) { <div class="muted small">{{ b.definition }}</div> }
            @for (p of chemins(); track p) {
              <div class="row chemin"><code class="grow">{{ p }}</code><button type="button" class="btn sm" (click)="poser('biePath', p)">{{ 'Utiliser' | t }}</button></div>
            } @empty { <div class="muted small">{{ 'Calcul des chemins…' | t }}</div> }
          }
          <div class="deux">
            <div class="field"><label for="pc-unt">UNTDED</label><input id="pc-unt" class="input mono" [value]="f.untdedRef ?? ''" (input)="poser('untdedRef', val($event).trim() || null)" /></div>
            <div class="field"><label for="pc-uid">{{ 'UID CCL' | t }}</label><input id="pc-uid" class="input mono" [value]="f.cclUid ?? ''" (input)="poser('cclUid', val($event).trim() || null)" /></div>
          </div>
          <div class="field"><label for="pc-den">{{ 'DEN UN/CCL' | t }}</label><input id="pc-den" class="input" [value]="f.cclDen ?? ''" (input)="poser('cclDen', val($event) || null)" /></div>
          <div class="field"><label for="pc-wco">{{ 'Modèle OMD' | t }}</label><input id="pc-wco" class="input" [value]="f.wcoRef ?? ''" (input)="poser('wcoRef', val($event) || null)" /></div>
        }
        @case ('validation') {
          <div class="field"><label for="pc-obl">{{ 'Obligatoire' | t }}</label>
            <select id="pc-obl" class="input" (change)="poser('mandatory', val($event))">
              @for (o of [['OUI', 'Oui'], ['NON', 'Non'], ['CONDITIONNEL', 'Conditionnel']]; track o[0]) { <option [value]="o[0]" [selected]="f.mandatory === o[0]">{{ o[1] | t }}</option> }
            </select></div>
          @if (f.mandatory === 'CONDITIONNEL') {
            <div class="field"><label for="pc-cond">{{ 'Condition' | t }}</label>
              <textarea id="pc-cond" class="input" rows="2" [value]="f.conditionText ?? ''" (input)="poser('conditionText', val($event) || null)"></textarea>
              <span class="muted small">{{ 'Texte de la condition (documentation). Le contrôle effectif est une règle DRL : voir ci-dessous.' | t }}</span></div>
          }
          <div class="deux">
            <div class="field"><label for="pc-max">{{ 'Long. max' | t }}</label>
              <input id="pc-max" class="input" type="number" min="1" [value]="f.maxLength ?? ''" (input)="poser('maxLength', val($event) === '' ? null : +val($event))" /></div>
            <div class="field"><label for="pc-pat">{{ 'Format (regex)' | t }}</label>
              <input id="pc-pat" class="input mono" [value]="f.pattern ?? ''" (input)="poser('pattern', val($event) || null)" placeholder="^[A-Z]{2}[0-9]+$" /></div>
          </div>
          @if (f.pattern) {
            <div class="field"><label for="pc-essai">{{ 'Tester le format' | t }}</label>
              <input id="pc-essai" class="input mono" [value]="essai()" (input)="essai.set(val($event))" />
              @if (regexInvalide()) { <span class="small err-txt">{{ 'Expression régulière invalide.' | t }}</span> }
              @else if (essai()) { <span class="small" [class.ok-txt]="essaiOk()" [class.err-txt]="!essaiOk()">{{ (essaiOk() ? 'Valeur conforme.' : 'Valeur non conforme.') | t }}</span> }
            </div>
          }
          <div class="sep"></div>
          <div class="field"><label>{{ 'Contrôles inter-champs (règles DRL Drools)' | t }}</label>
            @for (r of reglesLiees(); track r) { <div class="regle"><nx-icon name="scale" [size]="14" /> {{ r }}</div> }
            @empty { <span class="muted small">{{ 'Aucune règle DRL ne cite ce champ.' | t }}</span> }
          </div>
          <div class="field"><label for="pc-autre">{{ 'Modèle de règle avec un autre champ' | t }}</label>
            <div class="row"><select id="pc-autre" class="input grow" (change)="autre.set(val($event))">
              <option value="">—</option>
              @for (c of autresChamps(); track c.code) { <option [value]="c.code" [selected]="autre() === c.code">{{ c.labelFr }} ({{ c.code }})</option> }
            </select>
            <button type="button" class="btn sm" [disabled]="!autre()" (click)="copierModele()"><nx-icon name="link" [size]="14" /> {{ 'Copier' | t }}</button></div>
            @if (modeleCopie()) { <span class="muted small">{{ 'Modèle copié : collez-le dans l\\'onglet Règles DRL puis adaptez la condition.' | t }}</span> }
          </div>
          <button type="button" class="btn sm" (click)="regles.emit()"><nx-icon name="scale" [size]="14" /> {{ 'Ouvrir les règles DRL' | t }}</button>
        }
        @case ('logique') {
          <div class="field"><label>{{ 'Types de demande concernés' | t }}</label>
            @if (conditionLibre()) {
              <span class="muted small">{{ 'La condition de ce champ est un texte libre (« {0} ») : le champ s\\'affiche pour tous les types.' | t: f.conditionText }}</span>
              <button type="button" class="btn sm" (click)="poser('conditionText', null)">{{ 'Effacer la condition' | t }}</button>
            } @else {
              <div class="checks">
                @for (t of requestTypes(); track t.code) {
                  <label class="check"><input type="checkbox" [checked]="typesChoisis().includes(t.code)" (change)="basculerType(t.code, coche($event))" /> {{ t.labelFr | lib: t.labelEn }} <span class="code-tag">{{ t.code }}</span></label>
                }
              </div>
              <span class="muted small">{{ 'Aucun type coché : le champ s\\'applique à tous les types (condition « typeDemande in (…) »).' | t }}</span>
            }
          </div>
          <div class="sep"></div>
          <div class="field"><label for="pc-si">{{ 'Affichage conditionnel : visible si' | t }}</label>
            <select id="pc-si" class="input" (change)="poserSi(val($event))">
              <option value="">{{ 'Toujours visible' | t }}</option>
              @for (c of autresChamps(); track c.code) { <option [value]="c.code" [selected]="reglage().visibleSi?.champ === c.code">{{ c.labelFr }} ({{ c.code }})</option> }
            </select></div>
          @if (cible(); as c) {
            @if (valeursCible().length) {
              <div class="field"><label>{{ 'vaut l\\'une des valeurs' | t }}</label>
                <div class="checks">
                  @for (x of valeursCible(); track x.code) {
                    <label class="check"><input type="checkbox" [checked]="(reglage().visibleSi?.valeurs ?? []).includes(x.code)" (change)="basculerValeur(x.code, coche($event))" /> {{ x.labelFr | lib: x.labelEn }}</label>
                  }
                </div></div>
            } @else {
              <div class="field"><label for="pc-siv">{{ 'vaut l\\'une des valeurs' | t }}</label>
                <input id="pc-siv" class="input" [value]="(reglage().visibleSi?.valeurs ?? []).join(', ')" (change)="poserValeursSi(val($event))" /></div>
            }
            <span class="muted small">{{ 'Aucune valeur : visible dès que « {0} » est renseigné. Un champ masqué n\\'est pas contrôlé au dépôt.' | t: c.labelFr }}</span>
          }
        }
      }
    </fieldset>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
    .tete { display: flex; align-items: center; gap: 8px; }
    .sous-onglets { display: flex; gap: 2px; border-bottom: 1px solid var(--line); }
    .sous-onglets button { border: 0; border-bottom: 2px solid transparent; margin-bottom: -1px; background: none; font: inherit; font-size: 12.5px; font-weight: 600;
      padding: 6px 9px; color: var(--muted); cursor: pointer; }
    .sous-onglets button.on { color: var(--accent); border-bottom-color: var(--accent); }
    .corps { display: flex; flex-direction: column; gap: 10px; border: 0; padding: 0; margin: 0; min-width: 0; }
    .corps .input { height: 34px; font-size: 13px; }
    .corps textarea.input { height: auto; padding: 6px 9px; }
    .deux { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
    .checks { display: flex; flex-direction: column; gap: 6px; font-size: 13px; }
    .largeurs { display: grid; grid-template-columns: repeat(5, 1fr); gap: 4px; }
    .largeur { display: flex; flex-direction: column; gap: 4px; align-items: stretch; padding: 6px 4px; border: 1px solid var(--line); border-radius: 8px;
      background: var(--surface); font: inherit; font-size: 11.5px; color: var(--text); cursor: pointer; }
    .largeur.on { border-color: var(--accent); box-shadow: 0 0 0 2px var(--accent-soft); }
    .barre { height: 6px; border-radius: 3px; background: var(--accent); }
    .barre.auto { background: var(--line); }
    .resultats { display: flex; flex-direction: column; gap: 4px; max-height: 200px; overflow-y: auto; }
    .bie { display: flex; flex-direction: column; text-align: left; padding: 6px 8px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); font: inherit; font-size: 12.5px; color: var(--text); cursor: pointer; }
    .bie.on { border-color: var(--accent); }
    .chemin code { padding: 4px 6px; background: var(--surface-2); border-radius: 6px; overflow-wrap: anywhere; font-size: 11.5px; }
    .regle { display: flex; gap: 6px; align-items: center; font-size: 12.5px; }
    .sep { border-top: 1px solid var(--line); }
    .err-txt { color: var(--danger); }
    .ok-txt { color: var(--ok); }
  `],
})
export class ProprietesChamp {
  private readonly api = inject(Api);
  readonly champ = input.required<Field>();
  readonly reglage = input<ReglageChamp>({});
  readonly sections = input.required<Section[]>();
  readonly champs = input.required<Field[]>();
  readonly requestTypes = input<RequestType[]>([]);
  readonly drl = input('');
  readonly colonnes = input(3);
  readonly lectureSeule = input(false);
  readonly modifier = output<ModificationChamp>();
  readonly supprimer = output<void>();
  readonly regles = output<void>();
  protected readonly types = TYPES_CHAMP;
  protected readonly onglet = signal<Onglet>('affichage');
  protected readonly onglets: { code: Onglet; libelle: string }[] = [
    { code: 'affichage', libelle: 'Affichage' }, { code: 'donnees', libelle: 'Données' }, { code: 'validation', libelle: 'Validation' }, { code: 'logique', libelle: 'Logique' }];
  protected readonly largeurs = [{ n: 0, libelle: 'Auto' }, { n: 1, libelle: '1 col.' }, { n: 2, libelle: '2 col.' }, { n: 3, libelle: '3 col.' }, { n: 4, libelle: 'Ligne' }];
  protected readonly erreurCode = signal<string | null>(null);
  protected readonly essai = signal('');
  protected readonly autre = signal('');
  protected readonly modeleCopie = signal(false);
  protected readonly q = signal('');
  protected readonly resultats = signal<Bie[]>([]);
  protected readonly choisi = signal<Bie | null>(null);
  protected readonly chemins = signal<string[]>([]);
  protected readonly erreurBie = signal<string | null>(null);

  constructor() {
    // Changement de champ sélectionné : remise à zéro des aides de saisie
    effect(() => {
      this.champ().code;
      this.erreurCode.set(null);
      this.essai.set('');
      this.modeleCopie.set(false);
      this.choisi.set(null);
    });
  }

  protected readonly sectionsTriees = computed(() => {
    const l = [...this.sections()].sort((a, b) => a.sortOrder - b.sortOrder);
    return l;
  });
  protected readonly autresChamps = computed(() => this.champs().filter(c => c.code !== this.champ().code));
  protected readonly listesConnues = computed(() => [...new Set(['@LIEUX_TRAITEMENT', ...this.champs().map(c => c.refdataTable).filter((x): x is string => !!x)])].sort());
  protected readonly valeursTexte = computed(() => (this.champ().codeValues ?? []).map(c => `${c.code}=${c.labelFr}${c.labelEn ? '|' + c.labelEn : ''}`).join('\n'));
  protected readonly conditionLibre = computed(() => !!this.champ().conditionText?.trim() && !CONDITION_TYPE.test(this.champ().conditionText!));
  protected readonly typesChoisis = computed(() => {
    const m = CONDITION_TYPE.exec(this.champ().conditionText ?? '');
    return m ? m[1].split(',').map(x => x.trim().replace(/'/g, '')).filter(Boolean) : [];
  });
  protected readonly cible = computed(() => this.champs().find(c => c.code === this.reglage().visibleSi?.champ) ?? null);
  protected readonly valeursCible = computed<CodeValue[]>(() => {
    const c = this.cible();
    if (!c) return [];
    if (c.dataType === 'BOOLEAN') return [{ code: 'true', labelFr: 'Oui', labelEn: 'Yes' }, { code: 'false', labelFr: 'Non', labelEn: 'No' }];
    return c.codeValues ?? [];
  });
  protected readonly regexInvalide = computed(() => { try { new RegExp(this.champ().pattern ?? ''); return false; } catch { return true; } });
  /** Le Runtime applique le motif à toute la valeur (Matcher.matches). */
  protected readonly essaiOk = computed(() => { try { return new RegExp('^(?:' + (this.champ().pattern ?? '') + ')$').test(this.essai()); } catch { return false; } });

  /** Règles DRL dont le texte cite le code du champ entre guillemets (ex. texte("taxIdentifier")). */
  protected readonly reglesLiees = computed(() => {
    const code = this.champ().code;
    const r: string[] = [];
    for (const m of this.drl().matchAll(/rule\s+"([^"]+)"([\s\S]*?)\bend\b/g)) if (m[2].includes(`"${code}"`)) r.push(m[1]);
    return r;
  });

  protected val(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected coche(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }

  private emettre(champ: Field, reglage: ReglageChamp = this.reglage()): void {
    this.modifier.emit({ ancienCode: this.champ().code, champ, reglage });
  }

  protected poser<K extends keyof Field>(k: K, v: Field[K]): void {
    this.emettre({ ...this.champ(), [k]: v });
  }

  protected regler(r: Partial<ReglageChamp>): void {
    const n: ReglageChamp = { ...this.reglage(), ...r };
    for (const k of Object.keys(n) as (keyof ReglageChamp)[]) if (n[k] === undefined) delete n[k];
    this.emettre(this.champ(), n);
  }

  protected renommer(code: string): void {
    const c = code.trim();
    if (!c) { this.erreurCode.set('Le code est obligatoire.'); return; }
    if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(c)) { this.erreurCode.set('Code : lettres, chiffres et « _ », en commençant par une lettre.'); return; }
    if (c !== this.champ().code && this.champs().some(x => x.code === c)) { this.erreurCode.set('Ce code est déjà utilisé par un autre champ.'); return; }
    this.erreurCode.set(null);
    this.poser('code', c);
  }

  protected poserValeurs(texte: string): void {
    const valeurs = texte.split(/\n/).map(t => {
      const [code, reste = ''] = t.split('=');
      const [fr, en] = reste.split('|');
      return { code: code.trim(), labelFr: (fr || code).trim(), labelEn: en?.trim() || null } as CodeValue;
    }).filter(c => c.code);
    this.poser('codeValues', valeurs.length ? valeurs : null);
  }

  protected basculerType(code: string, oui: boolean): void {
    const l = this.typesChoisis().filter(x => x !== code);
    if (oui) l.push(code);
    const ordre = this.requestTypes().map(t => t.code);
    l.sort((a, b) => ordre.indexOf(a) - ordre.indexOf(b));
    this.poser('conditionText', l.length ? `typeDemande in (${l.join(', ')})` : null);
  }

  protected poserSi(champ: string): void {
    this.regler({ visibleSi: champ ? { champ, valeurs: [] } : undefined });
  }

  protected basculerValeur(code: string, oui: boolean): void {
    const si = this.reglage().visibleSi;
    if (!si) return;
    const l = si.valeurs.filter(x => x !== code);
    if (oui) l.push(code);
    this.regler({ visibleSi: { ...si, valeurs: l } });
  }

  protected poserValeursSi(texte: string): void {
    const si = this.reglage().visibleSi;
    if (si) this.regler({ visibleSi: { ...si, valeurs: texte.split(',').map(x => x.trim()).filter(Boolean) } });
  }

  /** Modèle de règle de validation croisant deux champs, à coller dans l'onglet Règles DRL. */
  protected copierModele(): void {
    const a = this.champ(), b = this.champs().find(c => c.code === this.autre());
    if (!b) return;
    const drl = `rule "Contrôle ${a.labelFr} / ${b.labelFr}"\n    agenda-group "validation"\nwhen\n    Demande(renseigne("${a.code}"), !renseigne("${b.code}"))\n`
      + `then\n    resultat.erreur("${b.code}", "« ${b.labelFr} » est obligatoire lorsque « ${a.labelFr} » est renseigné.");\nend\n`;
    navigator.clipboard?.writeText(drl).then(() => this.modeleCopie.set(true), () => this.modeleCopie.set(false));
  }

  protected chercher(): void {
    if (!this.q().trim()) return;
    this.choisi.set(null);
    this.api.rechercherBie(this.q()).subscribe({ next: r => { this.resultats.set(r); this.erreurBie.set(null); }, error: e => this.erreurBie.set(erreurs(e).join(' ')) });
  }

  protected choisirBie(b: Bie): void {
    this.choisi.set(b);
    this.chemins.set([]);
    this.api.abie(b.abie).subscribe({ next: r => this.chemins.set((r.paths.length ? r.paths : ['']).map(p => (p ? p + '/' : '') + b.property)) });
  }
}
