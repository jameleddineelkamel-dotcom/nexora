import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Api, ProcedureResume, Terminologie } from '../core/api';
import { AuthService, DROITS } from '../core/auth.service';
import { I18N } from '../core/i18n';
import { erreurs } from '../core/session';
import { Cycle } from '../shared/cycle';
import { Icon } from '../shared/icon';

/**
 * Procédures du guichet unique, avec leur rattachement à la terminologie GUCE (processus › phase › circuit) et leurs
 * versions. Ajout d'une procédure : gabarit standard (socle commun prêt à compléter), copie d'une procédure existante
 * (ex. une procédure voisine de FIMEX), ou procédure vide.
 */
@Component({
  selector: 'nx-procedures',
  imports: [Icon, Cycle, ...I18N],
  template: `
    <div class="page-head">
      <div><h1>{{ 'Procédures' | t }}</h1><p class="muted">{{ 'Chaque procédure est rattachée à un circuit, une phase et un processus du GUCE (RG-TERM-01).' | t }}</p></div>
      @if (auth.a(d.modeliser)) { <button class="btn primary" (click)="ouvrir()"><nx-icon name="plus" /> {{ 'Nouvelle procédure' | t }}</button> }
    </div>
    <div class="card pad row"><nx-icon name="search" /><input class="input grow" [value]="q()" (input)="q.set($any($event.target).value)" [placeholder]="'Filtrer par code, libellé, autorité…' | t" /></div>
    @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
    <div class="card scroll-x">
      <table class="datagrid">
        <thead><tr><th>{{ 'Code' | t }}</th><th>{{ 'Procédure' | t }}</th><th>{{ 'Processus › Phase › Circuit' | t }}</th><th>{{ 'Publiée' | t }}</th><th>{{ 'Version de travail' | t }}</th></tr></thead>
        <tbody>
          @for (p of filtrees(); track p.code) {
            <tr (click)="router.navigate(['/procedures', p.code])">
              <td><span class="code-tag">{{ p.code }}</span></td>
              <td><b>{{ p.labelFr | lib: p.labelEn }}</b><div class="muted small">{{ p.authority }}</div></td>
              <td class="small">{{ p.processFr }} › {{ p.phaseFr }} › {{ p.circuitFr }} @if (p.bspArea) { <span class="chip">{{ p.bspArea }}</span> }</td>
              <td>@if (p.publishedVersionNo) { <span class="chip ok">v{{ p.publishedVersionNo }}</span> } @else { — }</td>
              <td>@if (p.workingStatus) { v{{ p.workingVersionNo }} <nx-cycle [statut]="p.workingStatus" /> } @else { — }</td>
            </tr>
          } @empty { <tr><td colspan="5" class="empty">{{ 'Aucune procédure.' | t }}</td></tr> }
        </tbody>
      </table>
    </div>

    @if (creation()) {
      <div class="overlay" (click)="creation.set(false)"></div>
      <aside class="drawer" role="dialog" [attr.aria-label]="'Nouvelle procédure' | t">
        <header class="between"><h2>{{ 'Nouvelle procédure' | t }}</h2><button class="btn icon ghost" (click)="creation.set(false)"><nx-icon name="x" /></button></header>
        <div class="body stack">
          <div class="field"><label>{{ 'Point de départ' | t }}</label>
            <div class="modeles">
              <button type="button" class="modele" [class.on]="modele() === 'GABARIT'" (click)="modele.set('GABARIT')">
                <b>{{ 'Gabarit standard' | t }}</b><span class="muted small">{{ 'Acteurs, statuts, identification du demandeur, lieu de traitement, messages et flux, livrable, workflow BPMN généré : à compléter.' | t }}</span></button>
              <button type="button" class="modele" [class.on]="modele() === 'COPIE'" (click)="modele.set('COPIE')">
                <b>{{ 'Copier une procédure existante' | t }}</b><span class="muted small">{{ 'Reprend toute la dernière version (formulaire, pièces, frais, messages, BPMN, DRL).' | t }}</span></button>
              <button type="button" class="modele" [class.on]="modele() === 'VIDE'" (click)="modele.set('VIDE')">
                <b>{{ 'Procédure vide' | t }}</b><span class="muted small">{{ 'Tout est à modéliser, par exemple avec le Document Agent à partir de la SFD.' | t }}</span></button>
            </div></div>
          @if (modele() === 'COPIE') {
            <div class="field"><label for="src">{{ 'Procédure à copier' | t }}</label>
              <select id="src" class="input" (change)="source.set($any($event.target).value)">
                @for (p of procedures(); track p.code) { <option [value]="p.code" [selected]="source() === p.code">{{ p.code }} — {{ p.labelFr }}</option> }</select></div>
          }
          <div class="form-grid">
            <div class="field"><label for="c">{{ 'Code' | t }} *</label><input id="c" class="input mono" [value]="n().code" (input)="poser('code', $any($event.target).value.toUpperCase())" placeholder="CERT_ORIGINE" /></div>
            @if (modele() === 'GABARIT') {
              <div class="field"><label for="cur">{{ 'Code CUR (3 lettres)' | t }}</label><input id="cur" class="input mono" maxlength="3" [value]="n().curCode" (input)="poser('curCode', $any($event.target).value.toUpperCase())" placeholder="COR" />
                <span class="hint">{{ 'CUR = PAYS ANNÉE CODE SÉQUENCE, ex. CM2026COR000001' | t }}</span></div>
            }
            <div class="field full"><label for="lf">{{ 'Libellé (français)' | t }} *</label><input id="lf" class="input" [value]="n().labelFr" (input)="poser('labelFr', $any($event.target).value)" /></div>
            <div class="field full"><label for="le">{{ 'Libellé (anglais)' | t }}</label><input id="le" class="input" [value]="n().labelEn" (input)="poser('labelEn', $any($event.target).value)" /></div>
            <div class="field full"><label for="ci">{{ 'Circuit GUCE' | t }} *</label>
              <select id="ci" class="input" (change)="poser('circuitCode', $any($event.target).value)"><option value=""></option>
                @for (c of terminologie()?.circuits ?? []; track c.code) { <option [value]="c.code" [selected]="n().circuitCode === c.code">{{ c.labelFr }} ({{ phase(c.phaseCode ?? '') }})</option> }</select></div>
            <div class="field full"><label for="au">{{ 'Administration responsable' | t }}</label><input id="au" class="input" [value]="n().authority" (input)="poser('authority', $any($event.target).value)" /></div>
            <div class="field full"><label for="de">{{ 'Description et cadre juridique' | t }}</label><textarea id="de" class="input" [value]="n().description" (input)="poser('description', $any($event.target).value)"></textarea></div>
          </div>
          @if (erreurCreation()) { <div class="alert err">{{ erreurCreation() }}</div> }
        </div>
        <footer><button class="btn ghost" (click)="creation.set(false)">{{ 'Annuler' | t }}</button>
          <button class="btn primary" (click)="creer()" [disabled]="occupe()"><nx-icon name="check" /> {{ 'Créer la version 1 (brouillon)' | t }}</button></footer>
      </aside>
    }
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 16px; }
    .modeles { display: flex; flex-direction: column; gap: 8px; }
    .modele { display: flex; flex-direction: column; gap: 3px; text-align: left; padding: 11px 13px; border: 1px solid var(--line); border-radius: 12px; background: var(--surface); font: inherit; color: var(--text); cursor: pointer; }
    .modele.on { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
  `],
})
export class ProceduresPage {
  private readonly api = inject(Api);
  private readonly route = inject(ActivatedRoute);
  protected readonly router = inject(Router);
  protected readonly auth = inject(AuthService);
  protected readonly d = DROITS;
  protected readonly procedures = signal<ProcedureResume[]>([]);
  protected readonly terminologie = signal<Terminologie | null>(null);
  protected readonly q = signal('');
  protected readonly creation = signal(false);
  protected readonly modele = signal<'GABARIT' | 'COPIE' | 'VIDE'>('GABARIT');
  protected readonly source = signal('');
  protected readonly n = signal({ code: '', curCode: '', labelFr: '', labelEn: '', circuitCode: '', authority: '', description: '' });
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly erreurCreation = signal<string | null>(null);
  protected readonly filtrees = computed(() => {
    const q = this.q().trim().toLowerCase();
    return this.procedures().filter(p => !q || [p.code, p.labelFr, p.labelEn, p.authority, p.circuitFr].some(x => (x ?? '').toLowerCase().includes(q)));
  });

  constructor() {
    this.api.procedures().subscribe({ next: p => { this.procedures.set(p); if (!this.source() && p.length) this.source.set(p[0].code); }, error: e => this.erreur.set(erreurs(e, 'Le studio').join(' ')) });
    this.api.terminologie().subscribe({ next: t => this.terminologie.set(t) });
    this.route.queryParamMap.subscribe(m => { this.q.set(m.get('q') ?? ''); if (m.get('nouvelle')) this.ouvrir(); });
  }

  protected ouvrir(): void {
    this.creation.set(true);
    this.erreurCreation.set(null);
  }

  protected phase(code: string): string {
    return this.terminologie()?.phases.find(p => p.code === code)?.labelFr ?? code;
  }

  protected poser(cle: string, v: string): void {
    this.n.update(x => ({ ...x, [cle]: v }));
  }

  protected creer(): void {
    this.occupe.set(true);
    const n = this.n();
    this.api.creerProcedure({ ...n, model: this.modele() === 'COPIE' ? this.source() : this.modele() }, 'Création de la procédure').subscribe({
      next: v => this.router.navigate(['/versions', v.version.id]),
      error: e => { this.occupe.set(false); this.erreurCreation.set(erreurs(e).join(' ')); },
    });
  }
}
