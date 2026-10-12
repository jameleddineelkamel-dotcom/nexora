import { ChangeDetectionStrategy, Component, ElementRef, Injectable, computed, inject, input, model, signal, viewChild } from '@angular/core';
import { Observable, of, shareReplay, map, catchError } from 'rxjs';
import { Api, CaseStatus, Valeur } from '../core/api';
import { I18N, I18n } from '../core/i18n';
import { Icon } from './icon';

/** Statut d'un dossier (terminologie GUCE), coloré selon le paquet publié. */
@Component({
  selector: 'nx-statut',
  imports: [...I18N],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="chip" [class]="'chip ' + ton()">{{ libelle() }}</span>`,
})
export class Statut {
  private readonly i18n = inject(I18n);
  readonly code = input.required<string>();
  readonly statuts = input<CaseStatus[]>([]);
  private readonly def = computed(() => this.statuts().find(s => s.code === this.code()));
  protected readonly ton = computed(() => this.def()?.tone ?? ({ BROUILLON: 'neutral', VALIDE: 'ok', REJETE: 'danger', ANNULE: 'neutral' } as Record<string, string>)[this.code()] ?? 'info');
  protected readonly libelle = computed(() => this.def() ? this.i18n.libelle(this.def()!.labelFr, this.def()!.labelEn) : this.i18n.t(LIBELLES[this.code()] ?? this.code()));
}

/** Libellés des statuts génériques, lorsque le paquet n'est pas chargé (listes). */
export const LIBELLES: Record<string, string> = {
  BROUILLON: 'Brouillon', EN_ATTENTE_PAIEMENT: 'En attente de paiement', A_COTER: 'À coter', EN_ATTENTE_TRAITEMENT: 'En attente de traitement',
  COMPLEMENT_DEMANDE: 'Complément d\'information demandé', EN_ATTENTE_SIGNATURE: 'En attente de signature', VALIDE: 'Validé',
  REJETE: 'Rejeté', ANNULE: 'Annulé',
};
export const NATURES: Record<string, string> = { INITIAL: 'Demande initiale', RENEWAL: 'Renouvellement', AMENDMENT: 'Amendement', EXTENSION: 'Prorogation', CANCELLATION: 'Annulation' };

/** Listes de codes projetées depuis le Référentiel Commun, chargées une fois par table. */
@Injectable({ providedIn: 'root' })
export class Listes {
  private readonly api = inject(Api);
  private readonly cache = new Map<string, Observable<Valeur[]>>();

  valeurs(table: string): Observable<Valeur[]> {
    if (!this.cache.has(table))
      this.cache.set(table, this.api.valeurs(table).pipe(catchError(() => of([] as Valeur[])), shareReplay(1)));
    return this.cache.get(table)!;
  }

  libelle(table: string, code: string): Observable<string> {
    return this.valeurs(table).pipe(map(l => l.find(v => v.code === code)?.labelFr ?? ''));
  }
}

/** Sélecteur d'un code d'une liste du Référentiel Commun (recherche sur le code et le libellé). */
@Component({
  selector: 'nx-code-picker',
  imports: [Icon, ...I18N],
  template: `
    <div class="picker">
      <input #champ class="input" [class.err]="erreur()" [disabled]="disabled()" [value]="texte()" (input)="saisir($any($event.target).value)"
        (focus)="ouvert.set(true)" (blur)="fermerPlusTard()" (keydown.escape)="ouvert.set(false)" [attr.aria-label]="label()" autocomplete="off"
        [placeholder]="'Code ou libellé…' | t" />
      @if (value() && !disabled()) { <button type="button" class="btn icon ghost sm efface" (mousedown)="choisir(null)" [attr.aria-label]="'Effacer' | t"><nx-icon name="x" [size]="14" /></button> }
      @if (ouvert() && !disabled()) {
        <div class="liste card">
          @for (v of suggestions(); track v.code) {
            <button type="button" class="opt" (mousedown)="choisir(v)"><span class="code-tag">{{ v.code }}</span> <span class="grow">{{ v.labelFr | lib: v.labelEn }}</span></button>
          } @empty { <div class="muted small vide">{{ (charge() ? 'Aucune valeur' : 'Chargement…') | t }}</div> }
        </div>
      }
    </div>`,
  styles: [`
    .picker { position: relative; }
    .efface { position: absolute; right: 4px; top: 4px; height: 30px; width: 30px; }
    .liste { position: absolute; left: 0; right: 0; top: calc(100% + 4px); z-index: 35; max-height: 260px; overflow-y: auto; padding: 4px; box-shadow: var(--shadow-lg); }
    .opt { display: flex; gap: 8px; align-items: center; width: 100%; padding: 7px 8px; border: 0; border-radius: 8px; background: none; font: inherit;
      color: var(--text); text-align: left; cursor: pointer; }
    .opt:hover { background: var(--accent-soft); }
    .vide { padding: 8px; }
  `],
})
export class CodePicker {
  private readonly listes = inject(Listes);
  private readonly i18n = inject(I18n);
  readonly table = input.required<string>();
  readonly value = model<string | null>(null);
  readonly label = input('');
  readonly disabled = input(false);
  readonly erreur = input(false);
  protected readonly ouvert = signal(false);
  protected readonly requete = signal<string | null>(null);
  protected readonly charge = signal(false);
  private readonly toutes = signal<Valeur[]>([]);
  private readonly champ = viewChild<ElementRef<HTMLInputElement>>('champ');

  constructor() {
    queueMicrotask(() => this.listes.valeurs(this.table()).subscribe(l => { this.toutes.set(l); this.charge.set(true); }));
  }

  protected readonly texte = computed(() => {
    if (this.requete() !== null) return this.requete()!;
    const v = this.value();
    if (!v) return '';
    const d = this.toutes().find(x => x.code === v);
    return d ? `${v} — ${this.i18n.libelle(d.labelFr, d.labelEn)}` : v;
  });

  protected readonly suggestions = computed(() => {
    const q = (this.requete() ?? '').trim().toLowerCase();
    const l = this.toutes();
    const r = !q ? l : l.filter(v => v.code.toLowerCase().startsWith(q) || (v.labelFr ?? '').toLowerCase().includes(q) || (v.labelEn ?? '').toLowerCase().includes(q));
    return r.slice(0, 40);
  });

  protected saisir(t: string): void {
    this.requete.set(t);
    this.ouvert.set(true);
  }

  protected choisir(v: Valeur | null): void {
    this.value.set(v?.code ?? null);
    this.requete.set(null);
    this.ouvert.set(false);
  }

  protected fermerPlusTard(): void {
    setTimeout(() => {
      this.ouvert.set(false);
      // Saisie libre exacte d'un code connu : acceptée
      const q = this.requete();
      if (q !== null) {
        const exact = this.toutes().find(v => v.code.toLowerCase() === q.trim().toLowerCase());
        if (exact) this.value.set(exact.code);
        this.requete.set(null);
      }
    }, 150);
  }

  focus(): void { this.champ()?.nativeElement.focus(); }
}

/** Villes et quartiers proposés en saisie manuelle de la localisation (autocomplétion, RG1 SFD FIMEX v1.3). */
const LIEUX = ['Douala — Akwa', 'Douala — Bonanjo', 'Douala — Bonapriso', 'Douala — Deido', 'Douala — Bali', 'Douala — Bonabéri', 'Douala — Makepe',
  'Douala — Logbessou', 'Douala — Zone industrielle de Bassa', 'Yaoundé — Bastos', 'Yaoundé — Centre administratif', 'Yaoundé — Mvog-Mbi', 'Yaoundé — Essos',
  'Yaoundé — Biyem-Assi', 'Yaoundé — Melen', 'Yaoundé — Nlongkak', 'Garoua', 'Maroua', 'Ngaoundéré', 'Bertoua', 'Ebolowa', 'Bafoussam', 'Bamenda', 'Buea',
  'Limbe', 'Kribi', 'Kumba', 'Nkongsamba', 'Edéa', 'Dschang', 'Foumban', 'Kousseri', 'Mbalmayo', 'Sangmélima'];

/** Champ de localisation intelligent : géolocalisation GPS, repli en saisie manuelle avec autocomplétion. */
@Component({
  selector: 'nx-localisation',
  imports: [Icon, ...I18N],
  template: `
    <div class="row loc">
      <input class="input grow" [class.err]="erreur()" [disabled]="disabled()" [value]="value() ?? ''" (input)="value.set($any($event.target).value)"
        list="nx-lieux" [placeholder]="'Position GPS ou lieu / quartier' | t" />
      <datalist id="nx-lieux">@for (l of lieux; track l) { <option [value]="l"></option> }</datalist>
      @if (!disabled()) {
        <button type="button" class="btn" (click)="localiser()" [disabled]="enCours()"><nx-icon name="pin" /> {{ (enCours() ? 'Localisation…' : 'Me localiser') | t }}</button>
      }
    </div>
    @if (message()) { <span class="hint">{{ message()! | t }}</span> }`,
  styles: [`.loc { flex-wrap: nowrap; } @media (max-width: 480px) { .loc { flex-wrap: wrap; } }`],
})
export class Localisation {
  readonly value = model<string | null>(null);
  readonly disabled = input(false);
  readonly erreur = input(false);
  protected readonly enCours = signal(false);
  protected readonly message = signal<string | null>(null);
  protected readonly lieux = LIEUX;

  protected localiser(): void {
    if (!('geolocation' in navigator)) { this.message.set('Géolocalisation indisponible : saisissez le lieu ou le quartier.'); return; }
    this.enCours.set(true);
    navigator.geolocation.getCurrentPosition(
      p => {
        this.enCours.set(false);
        this.message.set(null);
        this.value.set(`${p.coords.latitude.toFixed(5)}, ${p.coords.longitude.toFixed(5)} (± ${Math.round(p.coords.accuracy)} m)`);
      },
      () => { this.enCours.set(false); this.message.set('Géolocalisation refusée ou indisponible : saisissez le lieu ou le quartier.'); },
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 60000 });
  }
}
