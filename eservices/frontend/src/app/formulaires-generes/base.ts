import { Directive, computed, inject, input, output, signal } from '@angular/core';
import { Formulaire } from '../core/api';
import { I18n } from '../core/i18n';
import type { OngletSup } from '../shared/formulaire';

export type Donnees = Record<string, unknown>;
export type Ligne = Record<string, unknown>;
/** Libellé bilingue écrit par le générateur : [français, anglais]. */
export type Libelle = readonly [string, string | null];
/** Section de premier niveau (onglet, étape ou bloc) : code, libellé et sections qu'elle regroupe (elle-même puis ses sous-sections). */
export interface Bloc { code: string; libelle: Libelle; sections: readonly string[] }

/**
 * Base des formulaires générés en code Angular par le studio (réglage « Rendu dans e-Services : code généré » d'une
 * procédure). Le composant généré écrit en dur la structure, les libellés, les contrôles et les conditions de sa version ;
 * cette base porte les mêmes entrées et sorties que le rendu dynamique (nx-formulaire) et la mécanique commune : saisie,
 * groupes répétitifs, erreurs, amendement, onglets ou étapes, accordéons, onglets propres à la page.
 */
@Directive()
export abstract class FormulaireGenereBase {
  protected readonly i18n = inject(I18n);
  readonly form = input.required<Formulaire>();
  readonly donnees = input.required<Donnees>();
  readonly precedent = input<Donnees | null>(null);
  readonly nature = input('INITIAL');
  readonly typeDemande = input<string | null>(null);
  readonly lectureSeule = input(false);
  readonly sectionsMasquees = input<string[]>([]);
  readonly erreurs = input<{ champ: string; message: string; ligne: number | null }[]>([]);
  readonly ongletsSup = input<OngletSup[]>([]);
  readonly modification = output<Donnees>();

  // ------------------------------------------------------------------ écrit par le générateur
  protected abstract readonly disposition: 'CONTINU' | 'ONGLETS' | 'ETAPES';
  protected abstract readonly accordeons: 'AUCUN' | 'PREMIER' | 'TOUS';
  protected abstract readonly structure: readonly Bloc[];
  /** Champs de chaque section (hors groupes répétitifs compris), dans l'ordre d'affichage. */
  protected abstract readonly champsParSection: Readonly<Record<string, readonly string[]>>;
  /** Conditions propres au champ : types de demande concernés et affichage conditionnel « visible si ». */
  protected abstract condition(code: string, ligne: Ligne | null): boolean;

  /** Onglet ou étape affiché : sections de premier niveau visibles, puis onglets propres à la page. */
  protected readonly actif = signal(0);
  private readonly etatsAcc = signal<ReadonlyMap<string, boolean>>(new Map());
  private readonly prefixe = 'fg-' + Math.random().toString(36).slice(2, 8) + '-';

  protected readonly blocs = computed(() => this.structure.filter(b => !this.sectionsMasquees().includes(b.code) && b.sections.some(s => this.sectionAffichee(s))));
  protected readonly total = computed(() => this.blocs().length + this.ongletsSup().length);
  protected readonly actifBorne = computed(() => Math.max(0, Math.min(this.actif(), this.total() - 1)));
  protected readonly ongletsSupAffiches = computed(() => {
    const o = this.ongletsSup();
    if (this.disposition === 'CONTINU') return o;
    const k = this.actifBorne() - this.blocs().length;
    return k >= 0 && k < o.length ? [o[k]] : [];
  });

  // ------------------------------------------------------------------ libellés et visibilité
  protected l(x: Libelle | undefined): string {
    return x ? this.i18n.libelle(x[0], x[1]) : '';
  }

  /** Champ affiché : nature de la demande (année de renouvellement, demande précédente) puis conditions générées. */
  protected visible(code: string, ligne: Ligne | null = null): boolean {
    const st = this.form().settings;
    if (code === st.renewalYearField && this.nature() !== 'RENEWAL') return false;
    if (code === st.previousField && this.nature() === 'INITIAL') return false;
    return this.condition(code, ligne);
  }

  protected sectionAffichee(section: string): boolean {
    return !this.sectionsMasquees().includes(section) && (this.champsParSection[section] ?? []).some(c => this.visible(c));
  }

  /** Bloc (onglet, étape ou section) à afficher maintenant. */
  protected affiche(code: string): boolean {
    const i = this.blocs().findIndex(b => b.code === code);
    return i >= 0 && (this.disposition === 'CONTINU' || i === this.actifBorne());
  }

  protected pourTypes(types: readonly string[]): boolean {
    const t = this.typeDemande();
    return !t || types.some(x => x.toUpperCase() === t.toUpperCase());
  }

  protected valeurDans(champ: string, valeurs: readonly string[], ligne: Ligne | null): boolean {
    const v = this.lire(champ, ligne);
    return valeurs.some(x => x.toUpperCase() === v.toUpperCase());
  }

  protected renseigne(champ: string, ligne: Ligne | null): boolean {
    return this.lire(champ, ligne) !== '';
  }

  private lire(champ: string, ligne: Ligne | null): string {
    const v = ligne && champ in ligne ? ligne[champ] : this.donnees()[champ];
    return v === null || v === undefined ? '' : String(v).trim();
  }

  // ------------------------------------------------------------------ saisie
  protected valeur(code: string): unknown {
    return this.donnees()[code];
  }

  protected lignes(section: string): Ligne[] {
    const v = this.donnees()[section];
    return Array.isArray(v) ? (v as Ligne[]) : [];
  }

  protected poser(code: string, v: string, section: string | null = null, i = -1): void {
    const d: Donnees = { ...this.donnees() };
    if (section === null) d[code] = v;
    else {
      const l = this.lignes(section).map(x => ({ ...x }));
      l[i] = { ...l[i], [code]: v };
      d[section] = l;
    }
    this.modification.emit(d);
  }

  protected ajouterLigne(section: string): void {
    this.modification.emit({ ...this.donnees(), [section]: [...this.lignes(section), {}] });
  }

  protected retirerLigne(section: string, i: number): void {
    this.modification.emit({ ...this.donnees(), [section]: this.lignes(section).filter((_, k) => k !== i) });
  }

  protected obligatoire(code: string, obligation: string): boolean {
    const st = this.form().settings;
    return obligation === 'OUI' || (obligation === 'CONDITIONNEL' && (code === st.previousField || code === st.renewalYearField));
  }

  protected verrou(code: string, amendable: boolean): boolean {
    if (this.lectureSeule()) return true;
    const st = this.form().settings;
    if ([st.applicantField, st.previousField].includes(code)) return true;
    return this.nature() === 'AMENDMENT' && !amendable;
  }

  protected modifie(code: string): boolean {
    const p = this.precedent();
    if (!p || this.nature() !== 'AMENDMENT') return false;
    return JSON.stringify(p[code] ?? '') !== JSON.stringify(this.donnees()[code] ?? '');
  }

  protected affichage(code: string, v: unknown): string {
    if (v === null || v === undefined) return '';
    const c = this.form().fields.find(f => f.code === code)?.codeValues?.find(x => x.code === v);
    return c ? this.i18n.libelle(c.labelFr, c.labelEn) : String(v);
  }

  protected erreur(code: string): string | null {
    const e = this.erreurs().filter(x => x.champ === code);
    return e.length ? e.map(x => x.message).join(' ') : null;
  }

  protected erreursSections(sections: readonly string[]): number {
    const codes = new Set(sections.flatMap(s => [s, ...(this.champsParSection[s] ?? [])]));
    return this.erreurs().filter(e => codes.has(e.champ)).length;
  }

  // ------------------------------------------------------------------ navigation, accordéons
  protected ancre(code: string): string {
    return this.prefixe + code;
  }

  protected aller(code: string): void {
    document.getElementById(this.ancre(code))?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  protected ouvert(code: string, rang: number): boolean {
    const choix = this.etatsAcc().get(code);
    if (choix !== undefined) return choix;
    return this.accordeons === 'TOUS' || (this.accordeons === 'PREMIER' && rang === 0) || this.erreursSections([code]) > 0;
  }

  protected basculerAccordeon(code: string, rang: number): void {
    this.etatsAcc.set(new Map(this.etatsAcc()).set(code, !this.ouvert(code, rang)));
  }
}
