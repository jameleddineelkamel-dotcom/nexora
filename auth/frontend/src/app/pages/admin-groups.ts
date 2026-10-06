import { Component, computed, inject, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import { Acces, Compte, Groupe, Role, erreurs } from '../core/acces';
import { I18N } from '../core/i18n';
import { Icon } from '../shared/icon';

/** Groupes : rôles attribués au groupe (hérités par les membres) et membres. */
@Component({
  selector: 'nx-admin-groups',
  imports: [Icon, ...I18N],
  template: `
    <div class="between"><p class="muted">{{ 'Un groupe réunit des utilisateurs (ex. une administration) ; ses rôles s\\'appliquent à tous ses membres.' | t }}</p>
      <button class="btn primary" (click)="ouvrir(null)"><nx-icon name="plus" /> {{ 'Nouveau groupe' | t }}</button></div>
    <div class="grid k3">
      @for (g of groupes(); track g.id) {
        <button class="card pad carte" (click)="ouvrir(g)">
          <div class="between"><span class="code-tag">{{ g.code }}</span><span class="chip">{{ '{0} membre(s)' | t: g.members.length }}</span></div>
          <h3>{{ g.labelFr }}</h3>
          @if (g.description) { <p class="small muted">{{ g.description }}</p> }
          <div class="row">@for (r of g.roles; track r) { <span class="chip accent">{{ libelleRole(r) }}</span> }</div>
        </button>
      }
    </div>

    @if (saisie(); as s) {
      <div class="overlay" (click)="saisie.set(null)"></div>
      <aside class="drawer" role="dialog">
        <header class="between"><h2>{{ s.id ? s.labelFr : ('Nouveau groupe' | t) }}</h2>
          <button class="btn icon ghost" (click)="saisie.set(null)" [attr.aria-label]="'Fermer' | t"><nx-icon name="x" /></button></header>
        <div class="body stack">
          <div class="form-grid">
            <div class="field"><label>{{ 'Code' | t }} *</label><input class="input mono" [value]="s.code" [readonly]="!!s.id" (input)="poser('code', $any($event.target).value.toUpperCase())" placeholder="DOUANE" /></div>
            <div class="field"><label>{{ 'Libellé (FR)' | t }} *</label><input class="input" [value]="s.labelFr" (input)="poser('labelFr', $any($event.target).value)" /></div>
            <div class="field"><label>{{ 'Libellé (EN)' | t }}</label><input class="input" [value]="s.labelEn ?? ''" (input)="poser('labelEn', $any($event.target).value)" /></div>
            <div class="field full"><label>{{ 'Description' | t }}</label><input class="input" [value]="s.description ?? ''" (input)="poser('description', $any($event.target).value)" /></div>
          </div>
          <div><h3>{{ 'Rôles du groupe' | t }}</h3>
            <div class="row">@for (r of roles(); track r.code) {
              <label class="check pastille" [class.on]="s.roles.includes(r.code)"><input type="checkbox" [checked]="s.roles.includes(r.code)" (change)="basculer('roles', r.code)" /> {{ r.labelFr }}</label>
            }</div></div>
          <div><h3>{{ 'Membres' | t }} ({{ s.members.length }})</h3>
            <input class="input" [placeholder]="'Filtrer…' | t" [value]="filtre()" (input)="filtre.set($any($event.target).value)" />
            <div class="membres">@for (c of candidats(); track c.username) {
              <label class="check"><input type="checkbox" [checked]="s.members.includes(c.username)" (change)="basculer('members', c.username)" />
                <span>{{ c.fullName }} <span class="muted small mono">{{ c.username }}</span></span></label>
            }</div></div>
          @if (messages().length) { <div class="alert err"><ul>@for (m of messages(); track m) { <li>{{ m }}</li> }</ul></div> }
        </div>
        <footer>
          @if (s.id) { <button class="btn danger" (click)="supprimer(s.id)"><nx-icon name="x" /> {{ 'Supprimer le groupe' | t }}</button> }
          <span class="grow"></span>
          <button class="btn" (click)="saisie.set(null)">{{ 'Annuler' | t }}</button>
          <button class="btn primary" (click)="enregistrer()"><nx-icon name="check" /> {{ 'Enregistrer' | t }}</button>
        </footer>
      </aside>
    }
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 12px; }
    p { margin: 0; }
    .carte { display: flex; flex-direction: column; gap: 8px; text-align: left; font: inherit; color: var(--text); cursor: pointer; }
    .carte:hover { border-color: var(--accent); }
    .pastille { padding: 6px 12px; border: 1px solid var(--line); border-radius: 999px; }
    .pastille.on { border-color: var(--accent); background: var(--accent-soft); color: var(--accent); }
    .membres { display: flex; flex-direction: column; gap: 6px; max-height: 300px; overflow-y: auto; margin-top: 8px; padding: 4px; }
    h3 { margin-bottom: 6px; }
  `],
})
export class AdminGroups {
  private readonly acces = inject(Acces);
  protected readonly groupes = signal<Groupe[]>([]);
  protected readonly roles = signal<Role[]>([]);
  protected readonly comptes = signal<Compte[]>([]);
  protected readonly saisie = signal<(Partial<Groupe> & { roles: string[]; members: string[]; code: string; labelFr: string }) | null>(null);
  protected readonly filtre = signal('');
  protected readonly messages = signal<string[]>([]);
  protected readonly candidats = computed(() => {
    const f = this.filtre().toLowerCase();
    return this.comptes().filter(c => !f || `${c.username} ${c.fullName}`.toLowerCase().includes(f));
  });

  ngOnInit(): void { this.charger(); }

  private charger(): void {
    forkJoin([this.acces.groupes(), this.acces.roles(), this.acces.comptes()]).subscribe(([g, r, c]) => { this.groupes.set(g); this.roles.set(r); this.comptes.set(c); });
  }

  protected libelleRole(code: string): string { return this.roles().find(r => r.code === code)?.labelFr ?? code; }
  protected ouvrir(g: Groupe | null): void {
    this.messages.set([]);
    this.saisie.set(g ? { ...g, roles: [...g.roles], members: [...g.members] } : { code: '', labelFr: '', labelEn: '', description: '', roles: [], members: [] });
  }
  protected poser(k: string, v: string): void { this.saisie.set({ ...this.saisie()!, [k]: v }); }
  protected basculer(k: 'roles' | 'members', v: string): void {
    const l = this.saisie()![k];
    this.saisie.set({ ...this.saisie()!, [k]: l.includes(v) ? l.filter(x => x !== v) : [...l, v] });
  }

  protected enregistrer(): void {
    this.acces.enregistrerGroupe(this.saisie()!).subscribe({ next: () => { this.saisie.set(null); this.charger(); }, error: e => this.messages.set(erreurs(e)) });
  }

  protected supprimer(id: number): void {
    this.acces.supprimerGroupe(id).subscribe({ next: () => { this.saisie.set(null); this.charger(); }, error: e => this.messages.set(erreurs(e)) });
  }
}
