import { Component, computed, inject, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import { Acces, Permission, Role, erreurs } from '../core/acces';
import { I18N, I18n } from '../core/i18n';
import { Icon } from '../shared/icon';

/** Matrice des habilitations : droits (lignes) × rôles (colonnes). */
@Component({
  selector: 'nx-admin-roles',
  imports: [Icon, ...I18N],
  template: `
    <p class="muted">{{ 'Cochez les droits accordés à chaque rôle, puis enregistrez. Les nouveaux droits s\\'appliquent à la prochaine connexion des utilisateurs.' | t }}</p>
    <div class="card scroll-x">
      <table class="datagrid matrice">
        <thead><tr>
          <th>{{ 'Droit' | t }}</th>
          @for (r of brouillon(); track r.code) {
            <th class="role"><b>{{ r.labelFr | lib: r.labelEn }}</b><span class="small muted">{{ '{0} utilisateur(s)' | t: r.userCount }}</span></th>
          }
        </tr></thead>
        <tbody>
          @for (a of applications(); track a) {
            <tr class="app"><td [attr.colspan]="brouillon().length + 1">{{ libelleApplication(a) | t }}</td></tr>
            @for (p of droits(a); track p.code) {
              <tr>
                <td><b>{{ p.labelFr | lib: p.labelEn }}</b><div class="small muted">{{ p.description }}</div><code class="small">{{ p.code }}</code></td>
                @for (r of brouillon(); track r.code) {
                  <td class="case"><input type="checkbox" [checked]="r.permissions.includes(p.code)" (change)="basculer(r, p.code)"
                    [attr.aria-label]="r.labelFr + ' — ' + p.labelFr" [disabled]="r.code === 'ADMINISTRATEUR' && p.code === 'auth.administration'" /></td>
                }
              </tr>
            }
          }
          <tr class="actions"><td></td>
            @for (r of brouillon(); track r.code) {
              <td class="case">
                @if (modifie(r)) { <button class="btn primary sm" (click)="enregistrer(r)">{{ 'Enregistrer' | t }}</button> }
                @if (!r.system) { <button class="btn ghost icon sm danger" (click)="supprimer(r)" [attr.aria-label]="'Supprimer' | t"><nx-icon name="x" [size]="15" /></button> }
              </td>
            }
          </tr>
        </tbody>
      </table>
    </div>
    @if (messages().length) { <div class="alert err"><ul>@for (m of messages(); track m) { <li>{{ m }}</li> }</ul></div> }
    @if (ok()) { <div class="alert ok"><nx-icon name="check" /> {{ ok() }}</div> }

    <form class="card pad nouveau" (submit)="$event.preventDefault(); creer()">
      <h3>{{ 'Nouveau rôle' | t }}</h3>
      <input class="input mono" [placeholder]="'CODE_DU_ROLE' | t" [value]="code()" (input)="code.set($any($event.target).value.toUpperCase())" />
      <input class="input" [placeholder]="'Libellé' | t" [value]="libelle()" (input)="libelle.set($any($event.target).value)" />
      <button class="btn" type="submit" [disabled]="!code() || !libelle()"><nx-icon name="plus" /> {{ 'Créer le rôle' | t }}</button>
    </form>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 12px; }
    p { margin: 0; }
    .matrice th.role { text-align: center; min-width: 120px; }
    .matrice th.role b, .matrice th.role span { display: block; text-transform: none; letter-spacing: 0; }
    .matrice th.role b { color: var(--text); font-size: 13px; }
    .matrice tbody tr { cursor: default; }
    .matrice tr.app td { background: var(--surface-2); font-weight: 700; text-transform: uppercase; font-size: 11.5px; letter-spacing: .05em; color: var(--muted); }
    .case { text-align: center; vertical-align: middle; white-space: nowrap; }
    .case input { width: 18px; height: 18px; cursor: pointer; accent-color: var(--accent); }
    .nouveau { display: grid; grid-template-columns: auto 1fr 1.5fr auto; gap: 10px; align-items: center; }
    @media (max-width: 760px) { .nouveau { grid-template-columns: 1fr; } }
  `],
})
export class AdminRoles {
  private readonly acces = inject(Acces);
  private readonly i18n = inject(I18n);
  protected readonly roles = signal<Role[]>([]);
  protected readonly brouillon = signal<Role[]>([]);
  protected readonly permissions = signal<Permission[]>([]);
  protected readonly messages = signal<string[]>([]);
  protected readonly ok = signal<string | null>(null);
  protected readonly code = signal('');
  protected readonly libelle = signal('');
  protected readonly applications = computed(() => [...new Set(this.permissions().map(p => p.application))]);

  ngOnInit(): void { this.charger(); }

  private charger(): void {
    forkJoin([this.acces.roles(), this.acces.permissions()]).subscribe(([r, p]) => {
      this.roles.set(r);
      this.brouillon.set(r.map(x => ({ ...x, permissions: [...x.permissions] })));
      this.permissions.set(p);
    });
  }

  protected droits(app: string): Permission[] { return this.permissions().filter(p => p.application === app); }
  protected libelleApplication(a: string): string { return a === 'refdata' ? 'Référentiel Commun' : a === 'auth' ? 'Accès et habilitations' : a; }
  protected basculer(r: Role, code: string): void {
    this.brouillon.set(this.brouillon().map(x => x.code !== r.code ? x
      : { ...x, permissions: x.permissions.includes(code) ? x.permissions.filter(p => p !== code) : [...x.permissions, code] }));
    this.ok.set(null);
  }
  protected modifie(r: Role): boolean {
    const o = this.roles().find(x => x.code === r.code);
    return !!o && [...o.permissions].sort().join() !== [...r.permissions].sort().join();
  }

  protected enregistrer(r: Role): void {
    this.messages.set([]);
    this.acces.enregistrerRole(r).subscribe({
      next: () => { this.ok.set(this.i18n.t('Habilitations du rôle « {0} » enregistrées.', r.labelFr)); this.charger(); },
      error: e => this.messages.set(erreurs(e)),
    });
  }

  protected creer(): void {
    this.messages.set([]);
    this.acces.enregistrerRole({ code: this.code(), labelFr: this.libelle(), permissions: ['refdata.lire'] }).subscribe({
      next: () => { this.code.set(''); this.libelle.set(''); this.charger(); },
      error: e => this.messages.set(erreurs(e)),
    });
  }

  protected supprimer(r: Role): void {
    this.acces.supprimerRole(r.id).subscribe({ next: () => this.charger(), error: e => this.messages.set(erreurs(e)) });
  }
}
