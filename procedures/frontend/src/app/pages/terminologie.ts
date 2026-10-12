import { Component, inject, signal } from '@angular/core';
import { Api, Terme, Terminologie } from '../core/api';
import { AuthService, DROITS } from '../core/auth.service';
import { I18N } from '../core/i18n';
import { erreurs } from '../core/session';
import { Icon } from '../shared/icon';

/** Terminologie du GUCE : Processus › Phase › Circuit (› Procédure), natures de demande ; ajout de circuits. */
@Component({
  selector: 'nx-terminologie',
  imports: [Icon, ...I18N],
  template: `
    <div class="page-head"><div><h1>{{ 'Terminologie GUCE' | t }}</h1>
      <p class="muted">{{ 'Processus › Phase › Circuit › Procédure › Nature de la demande › Étape › Statut (dossier de conception v4.2, §10.9).' | t }}</p></div></div>
    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
    @if (t(); as x) {
      <div class="grid k2">
        <div class="card pad stack">
          <h2>{{ 'Processus, phases et circuits' | t }}</h2>
          @for (p of x.processes; track p.code) {
            <div class="processus"><b>{{ p.labelFr | lib: p.labelEn }}</b> <span class="code-tag">{{ p.code }}</span>
              @for (ph of phases(p.code); track ph.code) {
                <div class="phase">{{ ph.labelFr | lib: ph.labelEn }} @if (ph.bspArea) { <span class="chip">{{ ph.bspArea }}</span> }
                  @for (c of circuits(ph.code); track c.code) { <div class="circuit"><nx-icon name="route" [size]="14" /> {{ c.labelFr }} <span class="muted small">{{ c.sharedRequirements }}</span></div> }
                </div>
              }
            </div>
          }
        </div>
        <div class="stack">
          <div class="card pad stack">
            <h2>{{ 'Natures de demande' | t }}</h2>
            @for (n of x.natures; track n.code) { <div><span class="code-tag">{{ n.code }}</span> {{ n.labelFr | lib: n.labelEn }} <span class="muted small">{{ n.description }}</span></div> }
          </div>
          @if (auth.a(d.modeliser)) {
            <div class="card pad stack">
              <h2>{{ 'Ajouter ou modifier un circuit' | t }}</h2>
              <div class="form-grid">
                <div class="field"><label for="cc">{{ 'Code' | t }}</label><input id="cc" class="input mono" [value]="c().code" (input)="poser('code', $any($event.target).value.toUpperCase())" /></div>
                <div class="field"><label for="cp">{{ 'Phase' | t }}</label><select id="cp" class="input" (change)="poser('phaseCode', $any($event.target).value)"><option value=""></option>
                  @for (ph of x.phases; track ph.code) { <option [value]="ph.code">{{ ph.labelFr }}</option> }</select></div>
                <div class="field full"><label for="cl">{{ 'Libellé' | t }}</label><input id="cl" class="input" [value]="c().labelFr" (input)="poser('labelFr', $any($event.target).value)" /></div>
                <div class="field full"><label for="cs">{{ 'Exigences partagées' | t }}</label><input id="cs" class="input" [value]="c().sharedRequirements" (input)="poser('sharedRequirements', $any($event.target).value)" /></div>
              </div>
              <div class="row"><button class="btn primary" (click)="enregistrer()"><nx-icon name="save" /> {{ 'Enregistrer' | t }}</button></div>
            </div>
          }
        </div>
      </div>
    }
  `,
  styles: [`:host { display: flex; flex-direction: column; gap: 16px; } .processus { padding: 10px 0; border-top: 1px solid var(--line); }
    .phase { margin: 6px 0 0 16px; } .circuit { margin: 4px 0 0 18px; display: flex; gap: 6px; align-items: center; }`],
})
export class TerminologiePage {
  private readonly api = inject(Api);
  protected readonly auth = inject(AuthService);
  protected readonly d = DROITS;
  protected readonly t = signal<Terminologie | null>(null);
  protected readonly c = signal({ code: '', phaseCode: '', labelFr: '', sharedRequirements: '', sortOrder: 100 });
  protected readonly erreur = signal<string | null>(null);

  constructor() {
    this.api.terminologie().subscribe({ next: t => this.t.set(t), error: e => this.erreur.set(erreurs(e).join(' ')) });
  }

  protected phases(p: string): Terme[] { return this.t()?.phases.filter(x => x.processCode === p) ?? []; }
  protected circuits(p: string): Terme[] { return this.t()?.circuits.filter(x => x.phaseCode === p) ?? []; }
  protected poser(k: string, v: string): void { this.c.update(x => ({ ...x, [k]: v })); }

  protected enregistrer(): void {
    this.api.circuit(this.c()).subscribe({ next: t => { this.t.set(t); this.erreur.set(null); }, error: e => this.erreur.set(erreurs(e).join(' ')) });
  }
}
