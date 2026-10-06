import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Observable, forkJoin } from 'rxjs';
import { Acces, Compte, Groupe, Role, erreurs } from '../core/acces';
import { I18N } from '../core/i18n';
import { Icon } from '../shared/icon';

type Saisie = { id?: number; username: string; fullName: string; email: string; organisation: string; language: string; active: boolean;
  password: string; groups: string[]; roles: string[] };

/** Utilisateurs : création, modification, groupes et rôles directs, réinitialisation du mot de passe, déverrouillage. */
@Component({
  selector: 'nx-admin-users',
  imports: [DatePipe, Icon, ...I18N],
  template: `
    <div class="card pad barre">
      <input class="input" [placeholder]="'Rechercher un utilisateur…' | t" [value]="filtre()" (input)="filtre.set($any($event.target).value)" />
      <button class="btn primary" (click)="ouvrir(null)"><nx-icon name="plus" /> {{ 'Nouvel utilisateur' | t }}</button>
    </div>
    <div class="card scroll-x">
      <table class="datagrid">
        <thead><tr><th>{{ 'Utilisateur' | t }}</th><th>{{ 'Organisation' | t }}</th><th>{{ 'Groupes' | t }}</th><th>{{ 'Rôles' | t }}</th><th>{{ 'Statut' | t }}</th><th>{{ 'Dernière connexion' | t }}</th></tr></thead>
        <tbody>
          @for (c of liste(); track c.id) {
            <tr (click)="ouvrir(c)">
              <td><b>{{ c.fullName }}</b><div class="small muted mono">{{ c.username }}</div></td>
              <td class="small">{{ c.organisation }}</td>
              <td>@for (g of c.groups; track g) { <span class="chip">{{ libelleGroupe(g) }}</span> }</td>
              <td>@for (r of c.roles; track r) { <span class="chip accent">{{ libelleRole(r) }}</span> }</td>
              <td>
                @if (!c.active) { <span class="chip">{{ 'Désactivé' | t }}</span> }
                @else if (verrouille(c)) { <span class="chip danger">{{ 'Verrouillé' | t }}</span> }
                @else if (c.mustChangePassword) { <span class="chip warn">{{ 'Mot de passe à changer' | t }}</span> }
                @else { <span class="chip ok">{{ 'Actif' | t }}</span> }
              </td>
              <td class="small muted">{{ c.lastLoginAt ? (c.lastLoginAt | date: 'dd/MM/yyyy HH:mm') : '—' }}</td>
            </tr>
          } @empty { <tr><td colspan="6" class="empty">{{ 'Aucun utilisateur.' | t }}</td></tr> }
        </tbody>
      </table>
    </div>

    @if (saisie(); as s) {
      <div class="overlay" (click)="fermer()"></div>
      <aside class="drawer" role="dialog">
        <header class="between"><h2>{{ s.id ? s.fullName : ('Nouvel utilisateur' | t) }}</h2>
          <button class="btn icon ghost" (click)="fermer()" [attr.aria-label]="'Fermer' | t"><nx-icon name="x" /></button></header>
        <div class="body stack">
          <div class="form-grid">
            <div class="field"><label>{{ 'Identifiant' | t }} *</label><input class="input mono" [value]="s.username" [readonly]="!!s.id"
              (input)="poser('username', $any($event.target).value.toLowerCase())" placeholder="prenom.nom" /></div>
            <div class="field"><label>{{ 'Nom complet' | t }} *</label><input class="input" [value]="s.fullName" (input)="poser('fullName', $any($event.target).value)" /></div>
            <div class="field"><label>{{ 'E-mail' | t }}</label><input class="input" type="email" [value]="s.email" (input)="poser('email', $any($event.target).value)" /></div>
            <div class="field"><label>{{ 'Organisation' | t }}</label><input class="input" [value]="s.organisation" (input)="poser('organisation', $any($event.target).value)" placeholder="DOUANE, MINCOMMERCE…" /></div>
            <div class="field"><label>{{ 'Langue' | t }}</label>
              <select class="input" (change)="poser('language', $any($event.target).value)">
                <option value="fr" [selected]="s.language === 'fr'">Français</option><option value="en" [selected]="s.language === 'en'">English</option></select></div>
            @if (!s.id) {
              <div class="field"><label>{{ 'Mot de passe' | t }}</label><input class="input" type="password" autocomplete="new-password" [value]="s.password" (input)="poser('password', $any($event.target).value)" />
                <span class="hint">{{ 'Laisser vide pour générer un mot de passe provisoire (à changer à la première connexion).' | t }}</span></div>
            }
            <label class="check full"><input type="checkbox" [checked]="s.active" (change)="poser('active', $any($event.target).checked)" /> {{ 'Compte actif' | t }}</label>
          </div>
          <div><h3>{{ 'Groupes' | t }}</h3><p class="small muted">{{ 'Les rôles des groupes sont hérités par leurs membres.' | t }}</p>
            <div class="choix">@for (g of groupes(); track g.code) {
              <label class="check carte-choix" [class.on]="s.groups.includes(g.code)"><input type="checkbox" [checked]="s.groups.includes(g.code)" (change)="basculer('groups', g.code)" />
                <span><b>{{ g.labelFr }}</b><span class="small muted">{{ g.roles.join(', ') || '—' }}</span></span></label>
            }</div></div>
          <div><h3>{{ 'Rôles attribués directement' | t }}</h3>
            <div class="choix">@for (r of roles(); track r.code) {
              <label class="check carte-choix" [class.on]="s.roles.includes(r.code)"><input type="checkbox" [checked]="s.roles.includes(r.code)" (change)="basculer('roles', r.code)" />
                <span><b>{{ r.labelFr }}</b><span class="small muted">{{ '{0} droit(s)' | t: r.permissions.length }}</span></span></label>
            }</div></div>
          @if (s.id && courant(); as c) {
            <div class="alert info"><nx-icon name="info" /> <div>{{ 'Droits effectifs' | t }} : @for (p of c.permissions; track p) { <span class="chip mono">{{ p }}</span> } @empty { — }</div></div>
          }
          @if (provisoire()) {
            <div class="alert warn"><nx-icon name="alert" /><div>{{ 'Mot de passe provisoire à transmettre à l\\'utilisateur (affiché une seule fois) :' | t }}
              <code class="secret">{{ provisoire() }}</code></div></div>
          }
          @if (messages().length) { <div class="alert err"><ul>@for (m of messages(); track m) { <li>{{ m }}</li> }</ul></div> }
        </div>
        <footer>
          @if (s.id && courant(); as c) {
            @if (verrouille(c)) { <button class="btn" (click)="deverrouiller(c)"><nx-icon name="refresh" /> {{ 'Déverrouiller' | t }}</button> }
            <button class="btn" (click)="reinitialiser(c)"><nx-icon name="refresh" /> {{ 'Réinitialiser le mot de passe' | t }}</button>
          }
          <span class="grow"></span>
          <button class="btn" (click)="fermer()">{{ 'Fermer' | t }}</button>
          <button class="btn primary" (click)="enregistrer()" [disabled]="occupe()"><nx-icon name="check" /> {{ 'Enregistrer' | t }}</button>
        </footer>
      </aside>
    }
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 12px; }
    .barre { display: flex; gap: 10px; flex-wrap: wrap; padding: 12px; }
    .barre .input { flex: 1 1 240px; }
    td .chip { margin: 0 4px 4px 0; }
    .choix { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(220px, 100%), 1fr)); gap: 8px; margin-top: 6px; }
    .carte-choix { padding: 10px 12px; border: 1px solid var(--line); border-radius: 10px; align-items: flex-start; }
    .carte-choix.on { border-color: var(--accent); background: var(--accent-soft); }
    .carte-choix b, .carte-choix span span { display: block; }
    .secret { display: inline-block; margin-top: 6px; font-size: 15px; padding: 4px 10px; border-radius: 6px; background: var(--surface); color: var(--text); user-select: all; }
    h3 { margin-bottom: 2px; }
    p { margin: 0; }
  `],
})
export class AdminUsers {
  private readonly acces = inject(Acces);
  protected readonly comptes = signal<Compte[]>([]);
  protected readonly groupes = signal<Groupe[]>([]);
  protected readonly roles = signal<Role[]>([]);
  protected readonly filtre = signal('');
  protected readonly saisie = signal<Saisie | null>(null);
  protected readonly provisoire = signal<string | null>(null);
  protected readonly messages = signal<string[]>([]);
  protected readonly occupe = signal(false);
  protected readonly liste = computed(() => {
    const f = this.filtre().toLowerCase();
    return this.comptes().filter(c => !f || `${c.username} ${c.fullName} ${c.organisation ?? ''} ${c.email ?? ''}`.toLowerCase().includes(f));
  });
  protected readonly courant = computed(() => this.comptes().find(c => c.id === this.saisie()?.id) ?? null);

  ngOnInit(): void { this.charger(); }

  private charger(): void {
    forkJoin([this.acces.comptes(), this.acces.groupes(), this.acces.roles()]).subscribe(([c, g, r]) => {
      this.comptes.set(c); this.groupes.set(g); this.roles.set(r);
    });
  }

  protected libelleGroupe(code: string): string { return this.groupes().find(g => g.code === code)?.labelFr ?? code; }
  protected libelleRole(code: string): string { return this.roles().find(r => r.code === code)?.labelFr ?? code; }
  protected verrouille(c: Compte): boolean { return !!c.lockedUntil && new Date(c.lockedUntil) > new Date(); }

  protected ouvrir(c: Compte | null): void {
    this.messages.set([]);
    this.provisoire.set(null);
    this.saisie.set(c ? { id: c.id, username: c.username, fullName: c.fullName, email: c.email ?? '', organisation: c.organisation ?? '',
      language: c.language, active: c.active, password: '', groups: [...c.groups], roles: [...c.directRoles] }
      : { username: '', fullName: '', email: '', organisation: '', language: 'fr', active: true, password: '', groups: [], roles: [] });
  }

  protected fermer(): void { this.saisie.set(null); this.provisoire.set(null); }
  protected poser<K extends keyof Saisie>(k: K, v: Saisie[K]): void { this.saisie.set({ ...this.saisie()!, [k]: v }); }
  protected basculer(k: 'groups' | 'roles', code: string): void {
    const l = this.saisie()![k];
    this.poser(k, l.includes(code) ? l.filter(x => x !== code) : [...l, code]);
  }

  protected enregistrer(): void {
    const s = this.saisie()!;
    this.occupe.set(true);
    this.messages.set([]);
    const corps = { username: s.username, fullName: s.fullName, email: s.email, organisation: s.organisation, language: s.language, active: s.active,
      groups: s.groups, roles: s.roles, ...(s.id ? {} : { password: s.password }) };
    const req: Observable<Compte | { user: Compte; temporaryPassword: string | null }> = s.id ? this.acces.modifierCompte(s.id, corps) : this.acces.creerCompte(corps);
    req.subscribe({
      next: (r: Compte | { user: Compte; temporaryPassword: string | null }) => {
        this.occupe.set(false);
        const u = 'user' in r ? r.user : r;
        if ('user' in r && r.temporaryPassword) { this.provisoire.set(r.temporaryPassword); this.saisie.set({ ...s, id: u.id }); }
        else this.saisie.set(null);
        this.charger();
      },
      error: (e: unknown) => { this.occupe.set(false); this.messages.set(erreurs(e)); },
    });
  }

  protected reinitialiser(c: Compte): void {
    this.acces.reinitialiser(c.id).subscribe({ next: r => { this.provisoire.set(r.temporaryPassword); this.charger(); }, error: e => this.messages.set(erreurs(e)) });
  }

  protected deverrouiller(c: Compte): void {
    this.acces.deverrouiller(c.id).subscribe({ next: () => this.charger(), error: e => this.messages.set(erreurs(e)) });
  }
}
