import { Component, ComponentRef, OutputEmitterRef, Type, ViewContainerRef, computed, effect, input, output, signal, untracked, viewChild } from '@angular/core';
import { Formulaire } from '../core/api';
import { FORMULAIRES_GENERES } from '../formulaires-generes';
import { FormulaireDynamique, OngletSup } from './formulaire';

type Donnees = Record<string, unknown>;

/**
 * Formulaire d'une procédure : code Angular généré par le studio quand la procédure le demande (réglage « rendu » :
 * GENERE) et que le composant de cette version est compilé dans e-Services ; sinon rendu dynamique (nx-formulaire).
 * Les deux reçoivent les mêmes entrées et émettent la même sortie.
 */
@Component({
  selector: 'nx-formulaire-procedure',
  imports: [FormulaireDynamique],
  template: `
    @if (!genere()) {
      <nx-formulaire [form]="form()" [donnees]="donnees()" [precedent]="precedent()" [nature]="nature()" [typeDemande]="typeDemande()"
        [lectureSeule]="lectureSeule()" [sectionsMasquees]="sectionsMasquees()" [erreurs]="erreurs()" [ongletsSup]="ongletsSup()"
        (modification)="modification.emit($event)" />
    }
    <ng-container #hote />
  `,
  host: { '[attr.data-rendu]': 'genere() ? "GENERE" : "DYNAMIQUE"', style: 'display: block' },
})
export class FormulaireProcedure {
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
  private readonly hote = viewChild('hote', { read: ViewContainerRef });
  protected readonly genere = signal<Type<unknown> | null>(null);
  private readonly ref = signal<ComponentRef<{ modification: OutputEmitterRef<Donnees> }> | null>(null);
  /** Composant généré attendu pour cette version, s'il est compilé dans e-Services. */
  private readonly charge = computed(() => {
    const f = this.form();
    return f.settings.rendu === 'GENERE' ? FORMULAIRES_GENERES[`${f.procedure.code}@${f.versionNo}`] ?? null : null;
  });

  constructor() {
    effect(() => {
      const c = this.charge();
      if (!c) { this.genere.set(null); return; }
      c().then(t => this.genere.set(t), () => this.genere.set(null));
    });
    // Création du composant généré dans l'hôte ; sa sortie est relayée
    effect(() => {
      const t = this.genere(), h = this.hote();
      if (!h) return;
      untracked(() => {
        h.clear();
        this.ref.set(null);
        if (!t) return;
        const r = h.createComponent(t) as ComponentRef<{ modification: OutputEmitterRef<Donnees> }>;
        // Entrées obligatoires posées avant le premier rendu du composant
        this.poserEntrees(r);
        r.instance.modification.subscribe(d => this.modification.emit(d));
        this.ref.set(r);
      });
    });
    effect(() => {
      const r = this.ref();
      if (r) this.poserEntrees(r);
    });
  }

  private poserEntrees(r: ComponentRef<unknown>): void {
    r.setInput('form', this.form());
    r.setInput('donnees', this.donnees());
    r.setInput('precedent', this.precedent());
    r.setInput('nature', this.nature());
    r.setInput('typeDemande', this.typeDemande());
    r.setInput('lectureSeule', this.lectureSeule());
    r.setInput('sectionsMasquees', this.sectionsMasquees());
    r.setInput('erreurs', this.erreurs());
    r.setInput('ongletsSup', this.ongletsSup());
  }
}
