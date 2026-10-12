import { Component, effect, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { Api, Procedure, Terminologie, Version } from '../core/api';
import { AuthService, DROITS } from '../core/auth.service';
import { I18N } from '../core/i18n';
import { erreurs } from '../core/session';
import { Cycle } from '../shared/cycle';
import { Icon } from '../shared/icon';

/** Fiche d'une procédure : identité, rattachement GUCE, versions et création d'une nouvelle version de travail. */
@Component({
  selector: 'nx-procedure',
  imports: [RouterLink, DatePipe, Icon, Cycle, ...I18N],
  template: `
    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
    @if (p(); as x) {
      <div class="page-head">
        <div><a routerLink="/procedures" class="small"><nx-icon name="back" [size]="14" /> {{ 'Procédures' | t }}</a>
          <h1><span class="code-tag">{{ x.code }}</span> {{ x.labelFr | lib: x.labelEn }}</h1><p class="muted">{{ x.authority }}</p></div>
        @if (auth.a(d.modeliser) && !travail()) { <button class="btn primary" (click)="nouvelle()" [disabled]="occupe()"><nx-icon name="plus" /> {{ 'Nouvelle version' | t }}</button> }
      </div>
      <div class="grid k2">
        <div class="card pad stack">
          <h2>{{ 'Identité' | t }}</h2>
          <fieldset class="lecture stack" [disabled]="!auth.a(d.modeliser)">
            <div class="field"><label for="lf">{{ 'Libellé (français)' | t }}</label><input id="lf" class="input" [value]="x.labelFr" (input)="maj('labelFr', $any($event.target).value)" /></div>
            <div class="field"><label for="le">{{ 'Libellé (anglais)' | t }}</label><input id="le" class="input" [value]="x.labelEn ?? ''" (input)="maj('labelEn', $any($event.target).value)" /></div>
            <div class="field"><label for="ci">{{ 'Circuit GUCE' | t }}</label><select id="ci" class="input" (change)="maj('circuitCode', $any($event.target).value)">
              @for (c of terminologie()?.circuits ?? []; track c.code) { <option [value]="c.code" [selected]="x.circuitCode === c.code">{{ c.labelFr }}</option> }</select></div>
            <div class="field"><label for="au">{{ 'Administration responsable' | t }}</label><input id="au" class="input" [value]="x.authority ?? ''" (input)="maj('authority', $any($event.target).value)" /></div>
            <div class="field"><label for="de">{{ 'Description et cadre juridique' | t }}</label><textarea id="de" class="input" [value]="x.description ?? ''" (input)="maj('description', $any($event.target).value)"></textarea></div>
          </fieldset>
          @if (auth.a(d.modeliser)) { <div class="row"><button class="btn" (click)="enregistrer()" [disabled]="!modifie()"><nx-icon name="save" /> {{ 'Enregistrer' | t }}</button></div> }
        </div>
        <div class="card pad stack">
          <h2>{{ 'Versions' | t }}</h2>
          @for (v of versions(); track v.id) {
            <a class="version" [routerLink]="['/versions', v.id]">
              <b>v{{ v.versionNo }}</b><nx-cycle [statut]="v.lifecycleStatus" />
              <span class="grow small muted">{{ v.changeNote }}</span>
              <span class="small muted">{{ (v.publishedAt ?? v.updatedAt) | date: 'dd/MM/yyyy' }}</span>
            </a>
          }
          <p class="muted small">{{ 'Une seule version de travail à la fois ; une version publiée est immuable et reste celle des dossiers déposés sous elle (RG-TRX-01).' | t }}</p>
        </div>
      </div>
    }
  `,
  styles: [`:host { display: flex; flex-direction: column; gap: 16px; } .version { display: flex; gap: 10px; align-items: center; padding: 10px; border: 1px solid var(--line); border-radius: 12px; color: var(--text); }
    .version:hover { border-color: var(--accent); text-decoration: none; }`],
})
export class ProcedurePage {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  protected readonly auth = inject(AuthService);
  protected readonly d = DROITS;
  readonly code = input.required<string>();
  protected readonly p = signal<Procedure | null>(null);
  protected readonly versions = signal<Version[]>([]);
  protected readonly terminologie = signal<Terminologie | null>(null);
  protected readonly modifie = signal(false);
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly travail = () => this.versions().find(v => ['draft', 'in_review', 'approved'].includes(v.lifecycleStatus));

  constructor() {
    effect(() => this.charger(this.code()));
    this.api.terminologie().subscribe(t => this.terminologie.set(t));
  }

  private charger(code: string): void {
    this.api.procedure(code).subscribe({ next: r => { this.p.set(r.procedure); this.versions.set(r.versions); this.modifie.set(false); }, error: e => this.erreur.set(erreurs(e).join(' ')) });
  }

  protected maj(cle: string, v: string): void {
    this.p.update(x => x ? { ...x, [cle]: v } : x);
    this.modifie.set(true);
  }

  protected enregistrer(): void {
    this.api.modifierProcedure(this.code(), this.p()!, 'Mise à jour de la fiche').subscribe({ next: () => this.charger(this.code()), error: e => this.erreur.set(erreurs(e).join(' ')) });
  }

  protected nouvelle(): void {
    this.occupe.set(true);
    this.api.nouvelleVersion(this.code(), '').subscribe({ next: v => this.router.navigate(['/versions', v.version.id]), error: e => { this.occupe.set(false); this.erreur.set(erreurs(e).join(' ')); } });
  }
}
