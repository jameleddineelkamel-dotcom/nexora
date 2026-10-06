import { Component, computed, inject, input, output, signal } from '@angular/core';
import { Acces, erreurs } from '../core/acces';
import { I18N } from '../core/i18n';
import { Icon } from './icon';

/** Changement de mot de passe avec indicateur de robustesse (10 caractères, lettres et chiffres). */
@Component({
  selector: 'nx-mot-de-passe',
  imports: [Icon, ...I18N],
  template: `
    <form class="stack" (submit)="$event.preventDefault(); enregistrer()">
      @if (!actuel()) {
        <div class="field"><label for="m0">{{ 'Mot de passe actuel' | t }}</label>
          <input id="m0" class="input" type="password" autocomplete="current-password" [value]="saisiActuel()" (input)="saisiActuel.set($any($event.target).value)" /></div>
      }
      <div class="field"><label for="m1">{{ 'Nouveau mot de passe' | t }}</label>
        <input id="m1" class="input" type="password" autocomplete="new-password" [value]="nouveau()" (input)="nouveau.set($any($event.target).value)" />
        <div class="jauge"><i [style.width.%]="force() * 25" [attr.data-f]="force()"></i></div>
        <span class="hint">{{ 'Au moins 10 caractères, avec des lettres et des chiffres.' | t }}</span></div>
      <div class="field"><label for="m2">{{ 'Confirmation' | t }}</label>
        <input id="m2" class="input" type="password" autocomplete="new-password" [value]="confirmation()" (input)="confirmation.set($any($event.target).value)" /></div>
      @if (messages().length) { <div class="alert err"><ul>@for (m of messages(); track m) { <li>{{ m | t }}</li> }</ul></div> }
      @if (ok()) { <div class="alert ok"><nx-icon name="check" /> {{ 'Mot de passe modifié.' | t }}</div> }
      <button class="btn primary" type="submit" [disabled]="occupe() || !nouveau() || nouveau() !== confirmation()"><nx-icon name="check" /> {{ 'Changer le mot de passe' | t }}</button>
    </form>
  `,
  styles: [`
    .jauge { height: 5px; border-radius: 3px; background: var(--chip); overflow: hidden; }
    .jauge i { display: block; height: 100%; transition: width .2s; background: var(--danger); }
    .jauge i[data-f="2"] { background: var(--warn); } .jauge i[data-f="3"], .jauge i[data-f="4"] { background: var(--ok); }
  `],
})
export class MotDePasse {
  private readonly acces = inject(Acces);
  /** Mot de passe actuel déjà connu (connexion avec mot de passe provisoire). */
  readonly actuel = input('');
  readonly change = output<void>();
  protected readonly saisiActuel = signal('');
  protected readonly nouveau = signal('');
  protected readonly confirmation = signal('');
  protected readonly messages = signal<string[]>([]);
  protected readonly occupe = signal(false);
  protected readonly ok = signal(false);
  protected readonly force = computed(() => {
    const m = this.nouveau();
    return [m.length >= 10, /[a-z]/i.test(m) && /\d/.test(m), /[^A-Za-z0-9]/.test(m) || m.length >= 14, m.length >= 16].filter(Boolean).length;
  });

  protected enregistrer(): void {
    this.occupe.set(true);
    this.messages.set([]);
    this.acces.changerMotDePasse(this.actuel() || this.saisiActuel(), this.nouveau()).subscribe({
      next: () => { this.occupe.set(false); this.ok.set(true); this.nouveau.set(''); this.confirmation.set(''); this.saisiActuel.set(''); this.change.emit(); },
      error: e => { this.occupe.set(false); this.messages.set(erreurs(e)); },
    });
  }
}
