import { Injectable, effect, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { I18n } from './i18n';

/** Titre de l'onglet du navigateur dans la langue de l'interface (« Page · NEXORA »). */
@Injectable({ providedIn: 'root' })
export class TitreTraduit extends TitleStrategy {
  private readonly title = inject(Title);
  private readonly i18n = inject(I18n);
  private courant = '';

  constructor() {
    super();
    effect(() => { this.i18n.langue(); this.appliquer(); });
  }

  override updateTitle(snapshot: RouterStateSnapshot): void {
    this.courant = this.buildTitle(snapshot) ?? '';
    this.appliquer();
  }

  private appliquer(): void {
    this.title.setTitle(this.courant ? `${this.i18n.t(this.courant)} · NEXORA` : 'NEXORA');
  }
}
