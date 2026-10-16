import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { Api, CodeAngular, VersionComplete } from '../../core/api';
import { AuthService, DROITS } from '../../core/auth.service';
import { I18N } from '../../core/i18n';
import { erreurs } from '../../core/session';
import { Icon } from '../../shared/icon';
import { Enregistrement } from './general';

type Rendu = 'DYNAMIQUE' | 'GENERE';

/**
 * Rendu du formulaire dans e-Services (réglage « rendu » de la version) : dynamique, interprété à l'exécution, ou code
 * Angular généré par le studio et compilé dans e-Services. Seul un administrateur de la plateforme le change ; tout
 * modélisateur peut consulter et télécharger le code généré.
 */
@Component({
  selector: 'nx-rendu-formulaire',
  imports: [Icon, ...I18N],
  template: `
    <div class="card pad stack">
      <div class="between">
        <div class="card-title"><nx-icon name="code" /><h2>{{ 'Rendu dans e-Services' | t }}</h2></div>
        <button class="btn sm" (click)="voirCode()" [disabled]="charge()"><nx-icon name="eye" [size]="15" /> {{ 'Voir le code Angular généré' | t }}</button>
      </div>
      <div class="choix" role="radiogroup">
        @for (o of options; track o.code) {
          <label class="option" [class.on]="rendu() === o.code" [class.lecture]="!peutChoisir()">
            <input type="radio" name="rendu" [value]="o.code" [checked]="rendu() === o.code" [disabled]="!peutChoisir()" (change)="rendu.set(o.code)" />
            <span><b>{{ o.libelle | t }}</b><span class="muted small">{{ o.aide | t }}</span></span>
          </label>
        }
      </div>
      @if (!estAdmin()) { <p class="muted small">{{ 'Choix réservé aux administrateurs de la plateforme.' | t }}</p> }
      @else if (!modifiable()) { <p class="muted small">{{ 'Le rendu se choisit dans une version brouillon.' | t }}</p> }
      @if (peutChoisir() && rendu() !== initial()) {
        <div class="row"><button class="btn primary sm" (click)="enregistrerRendu()"><nx-icon name="save" [size]="15" /> {{ 'Enregistrer' | t }}</button>
          <span class="muted small">{{ 'Après publication et déploiement, un administrateur d\\'e-Services prépare le code généré puis reconstruit e-Services.' | t }}</span></div>
      }
      @if (erreur()) { <div class="alert err small">{{ erreur() }}</div> }
    </div>

    @if (code(); as c) {
      <div class="overlay" (click)="code.set(null)"></div>
      <aside class="drawer large" role="dialog" [attr.aria-label]="'Code Angular généré' | t">
        <div class="between">
          <div><h2>{{ 'Code Angular généré' | t }}</h2>
            <div class="muted small"><span class="mono">{{ c.component }}</span> · <span class="mono">&lt;{{ c.selector }}&gt;</span> · {{ 'format {0}' | t: c.format }}</div></div>
          <button class="btn icon ghost" (click)="code.set(null)" [attr.aria-label]="'Fermer' | t"><nx-icon name="x" /></button>
        </div>
        <p class="muted small">{{ 'Composant autonome pour e-Services (dossier src/app/formulaires-generes), qui étend FormulaireGenereBase. Il est produit à partir de la version affichée ; e-Services le récupère pour les versions publiées en rendu « code généré ».' | t }}</p>
        <div class="row">
          @for (f of c.files; track f.path) {
            <button class="btn sm" [class.primary]="fichier() === f.path" (click)="fichier.set(f.path)">{{ f.path }}</button>
          }
          <span class="grow"></span>
          @if (fichierChoisi(); as f) { <button class="btn sm" (click)="telecharger(f.path, f.content)"><nx-icon name="download" [size]="15" /> {{ 'Télécharger' | t }}</button> }
        </div>
        @if (fichierChoisi(); as f) { <pre class="source">{{ f.content }}</pre> }
      </aside>
    }
  `,
  styles: [`
    .choix { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(280px, 100%), 1fr)); gap: 8px; }
    .option { display: flex; gap: 10px; align-items: flex-start; padding: 10px 12px; border: 1px solid var(--line); border-radius: 10px; cursor: pointer; }
    .option > span { display: flex; flex-direction: column; gap: 2px; }
    .option.on { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
    .option.lecture { cursor: default; }
    .drawer.large { width: min(960px, 100vw); display: flex; flex-direction: column; gap: 10px; padding: 18px; }
    .source { flex: 1; overflow: auto; margin: 0; padding: 12px; background: var(--surface-2); border: 1px solid var(--line); border-radius: 10px;
      font-family: "Cascadia Code", ui-monospace, Consolas, monospace; font-size: 12px; line-height: 1.45; white-space: pre; }
  `],
})
export class RenduFormulaire {
  private readonly api = inject(Api);
  private readonly auth = inject(AuthService);
  readonly v = input.required<VersionComplete>();
  readonly modifiable = input(false);
  readonly enregistrer = output<Enregistrement>();
  protected readonly options: { code: Rendu; libelle: string; aide: string }[] = [
    { code: 'DYNAMIQUE', libelle: 'Dynamique (recommandé)', aide: 'Formulaire construit à l\'exécution à partir du paquet publié : aucune recompilation, chaque version s\'affiche telle que publiée.' },
    { code: 'GENERE', libelle: 'Code Angular généré', aide: 'Composant Angular écrit par le studio pour chaque version publiée, compilé dans e-Services (reconstruction et redéploiement requis) ; repli dynamique tant qu\'il n\'est pas compilé.' }];
  protected readonly estAdmin = computed(() => this.auth.a(DROITS.administration));
  protected readonly peutChoisir = computed(() => this.modifiable() && this.estAdmin());
  protected readonly initial = computed<Rendu>(() => (this.v().version.settings['rendu'] === 'GENERE' ? 'GENERE' : 'DYNAMIQUE'));
  protected readonly rendu = signal<Rendu>('DYNAMIQUE');
  protected readonly code = signal<CodeAngular | null>(null);
  protected readonly fichier = signal<string | null>(null);
  protected readonly fichierChoisi = computed(() => this.code()?.files.find(f => f.path === this.fichier()) ?? null);
  protected readonly charge = signal(false);
  protected readonly erreur = signal<string | null>(null);

  constructor() {
    effect(() => this.rendu.set(this.initial()));
  }

  protected enregistrerRendu(): void {
    const v = this.v().version;
    this.enregistrer.emit({ partie: 'settings', corps: { settings: { ...v.settings, rendu: this.rendu() }, changeNote: v.changeNote },
      motif: this.rendu() === 'GENERE' ? 'Rendu du formulaire : code Angular généré' : 'Rendu du formulaire : dynamique' });
  }

  protected voirCode(): void {
    this.charge.set(true);
    this.erreur.set(null);
    this.api.codeAngular(this.v().version.id).subscribe({
      next: c => { this.charge.set(false); this.code.set(c); this.fichier.set(c.files[0]?.path ?? null); },
      error: e => { this.charge.set(false); this.erreur.set(erreurs(e).join(' ')); },
    });
  }

  protected telecharger(nom: string, contenu: string): void {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([contenu], { type: 'text/plain;charset=utf-8' }));
    a.download = nom;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  }
}
