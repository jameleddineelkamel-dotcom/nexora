import { Component, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Acces, Application, Utilisateur, erreurs } from '../core/acces';
import { I18N, I18n, LANGUES } from '../core/i18n';
import { Icon } from '../shared/icon';
import { MotDePasse } from '../shared/mot-de-passe';

/** Page de connexion unique (SSO) de la plateforme NEXORA. */
@Component({
  selector: 'nx-login',
  imports: [RouterLink, Icon, MotDePasse, ...I18N],
  template: `
    <div class="fond">
      <div class="panneau card">
        <div class="haut">
          <img src="nexora-logo.webp" alt="NEXORA — AI-powered Trade Digital Platform" class="logo" />
          <div class="langues" role="group" [attr.aria-label]="'Langue' | t">
            @for (l of langues; track l.code) { <button class="btn sm" [class.on]="i18n.langue() === l.code" (click)="i18n.langue.set(l.code)">{{ l.code.toUpperCase() }}</button> }
          </div>
        </div>

        @switch (etape()) {
          @case ('chargement') { <div class="skeleton" style="height: 160px"></div> }
          @case ('connexion') {
            <h1>{{ 'Connexion' | t }}</h1>
            <p class="muted">{{ 'Une seule connexion pour toutes les applications de la plateforme.' | t }}</p>
            <form class="stack" (submit)="$event.preventDefault(); connecter()">
              <div class="field"><label for="u">{{ 'Identifiant' | t }}</label>
                <input id="u" class="input" autocomplete="username" [value]="identifiant()" (input)="identifiant.set($any($event.target).value)" autofocus /></div>
              <div class="field"><label for="p">{{ 'Mot de passe' | t }}</label>
                <div class="mdp">
                  <input id="p" class="input" [type]="voir() ? 'text' : 'password'" autocomplete="current-password" [value]="motDePasse()" (input)="motDePasse.set($any($event.target).value)" />
                  <button type="button" class="btn ghost icon sm" (click)="voir.set(!voir())" [attr.aria-label]="(voir() ? 'Masquer' : 'Afficher') | t"><nx-icon [name]="voir() ? 'ban' : 'search'" [size]="16" /></button>
                </div></div>
              @if (erreur()) { <div class="alert err"><nx-icon name="alert" /> {{ erreur() }}</div> }
              <button class="btn primary large" type="submit" [disabled]="occupe() || !identifiant() || !motDePasse()"><nx-icon name="check" /> {{ 'Se connecter' | t }}</button>
            </form>
            @if (redirect_uri()) { <p class="small muted retour">{{ 'Vous serez redirigé vers {0} après la connexion.' | t: hote() }}</p> }
          }
          @case ('changement') {
            <h1>{{ 'Nouveau mot de passe' | t }}</h1>
            <p class="muted">{{ 'Votre mot de passe est provisoire : choisissez-en un nouveau pour continuer.' | t }}</p>
            <nx-mot-de-passe [actuel]="motDePasse()" (change)="continuer()" />
          }
          @case ('portail') {
            <h1>{{ 'Bonjour {0}' | t: (utilisateur()?.fullName ?? '') }}</h1>
            <p class="muted">{{ 'Vous êtes connecté. Choisissez une application :' | t }}</p>
            <div class="apps">
              @for (a of applications(); track a.code) {
                <a class="app" [href]="a.url"><span class="ic"><nx-icon name="database" /></span><span class="grow"><b>{{ a.label | t }}</b><span class="small muted">{{ a.url }}</span></span><nx-icon name="chevron" /></a>
              }
              @if (acces.admin()) {
                <a class="app" routerLink="/admin"><span class="ic admin"><nx-icon name="landmark" /></span><span class="grow"><b>{{ 'Administration des accès' | t }}</b><span class="small muted">{{ 'Utilisateurs, groupes, rôles, journal' | t }}</span></span><nx-icon name="chevron" /></a>
              }
              <a class="app" routerLink="/compte"><span class="ic compte"><nx-icon name="user" /></span><span class="grow"><b>{{ 'Mon compte' | t }}</b><span class="small muted">{{ 'Profil et mot de passe' | t }}</span></span><nx-icon name="chevron" /></a>
            </div>
            <button class="btn ghost" (click)="acces.deconnecter()"><nx-icon name="back" /> {{ 'Se déconnecter' | t }}</button>
          }
        }
      </div>
      <p class="pied small">NEXORA · {{ 'Authentification unique' | t }}</p>
    </div>
  `,
  styles: [`
    .fond { min-height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 16px; padding: 24px 16px;
      background: radial-gradient(circle at 15% 15%, rgba(25,195,230,.18), transparent 40%), radial-gradient(circle at 85% 90%, rgba(122,201,67,.18), transparent 45%), var(--bg); }
    .panneau { width: min(440px, 100%); padding: 28px; display: flex; flex-direction: column; gap: 14px; box-shadow: var(--shadow-lg); }
    .haut { display: flex; justify-content: space-between; align-items: flex-start; gap: 10px; }
    .logo { width: 200px; max-width: 70%; height: auto; margin: -10px 0 -6px -8px; }
    :root[data-theme="dark"] .logo { background: #fff; border-radius: 10px; }
    h1 { font-size: 22px; }
    p { margin: 0; }
    .langues { display: flex; gap: 2px; padding: 2px; border: 1px solid var(--line); border-radius: 10px; }
    .langues .btn { height: 28px; border: 0; padding: 0 9px; font-size: 12px; }
    .langues .btn.on { background: var(--navy); color: #fff; }
    .mdp { position: relative; }
    .mdp .btn { position: absolute; right: 4px; top: 4px; }
    .large { height: 44px; justify-content: center; font-size: 15px; }
    .retour { text-align: center; }
    .apps { display: flex; flex-direction: column; gap: 8px; }
    .app { display: flex; align-items: center; gap: 12px; padding: 12px; border: 1px solid var(--line); border-radius: 12px; color: var(--text); }
    .app:hover { border-color: var(--accent); text-decoration: none; background: var(--accent-soft); }
    .app b { display: block; }
    .ic { width: 40px; height: 40px; border-radius: 11px; display: grid; place-items: center; background: var(--gradient); color: #fff; flex-shrink: 0; }
    .ic.admin { background: var(--navy); } .ic.compte { background: var(--cyan); }
    .pied { color: var(--muted); }
    @media (max-width: 480px) { .panneau { padding: 20px 16px; } }
  `],
})
export class LoginPage {
  protected readonly acces = inject(Acces);
  protected readonly i18n = inject(I18n);
  readonly redirect_uri = input<string | undefined>();
  readonly state = input<string | undefined>();

  protected readonly langues = LANGUES;
  protected readonly etape = signal<'chargement' | 'connexion' | 'changement' | 'portail'>('chargement');
  protected readonly identifiant = signal('');
  protected readonly motDePasse = signal('');
  protected readonly voir = signal(false);
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly applications = signal<Application[]>([]);
  protected readonly utilisateur = signal<Utilisateur | null>(null);

  ngOnInit(): void {
    this.acces.config().subscribe(c => this.applications.set(c.applications));
    // Session SSO déjà ouverte : on continue sans redemander le mot de passe
    this.acces.session().subscribe({
      next: c => { if (c) { this.utilisateur.set(c.user); this.continuer(); } else this.etape.set('connexion'); },
      error: () => this.etape.set('connexion'),
    });
  }

  protected hote(): string {
    try { return new URL(this.redirect_uri()!).host; } catch { return ''; }
  }

  protected connecter(): void {
    this.occupe.set(true);
    this.erreur.set(null);
    this.acces.connecter(this.identifiant(), this.motDePasse()).subscribe({
      next: c => {
        this.occupe.set(false);
        this.utilisateur.set(c.user);
        if (c.user.language && c.user.language !== this.i18n.langue()) this.i18n.langue.set(c.user.language);
        if (c.user.mustChangePassword) this.etape.set('changement'); else this.continuer();
      },
      error: e => { this.occupe.set(false); this.erreur.set(erreurs(e).join(' ')); },
    });
  }

  /** Après connexion : retour à l'application d'origine (le service SSO y joint le jeton), sinon portail. */
  protected continuer(): void {
    const retour = this.redirect_uri();
    if (retour) {
      location.assign(`/sso/authorize?redirect_uri=${encodeURIComponent(retour)}${this.state() ? '&state=' + encodeURIComponent(this.state()!) : ''}`);
      return;
    }
    this.etape.set('portail');
  }
}
