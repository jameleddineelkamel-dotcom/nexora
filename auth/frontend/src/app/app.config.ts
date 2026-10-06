import { ApplicationConfig, Injectable, effect, inject, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy, provideRouter, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';
import { jetonInterceptor } from './core/acces';
import { I18n } from './core/i18n';

/** Titre de l'onglet dans la langue de l'interface. */
@Injectable({ providedIn: 'root' })
class TitreTraduit extends TitleStrategy {
  private readonly title = inject(Title);
  private readonly i18n = inject(I18n);
  private courant = '';
  constructor() { super(); effect(() => { this.i18n.langue(); this.appliquer(); }); }
  override updateTitle(s: RouterStateSnapshot): void { this.courant = this.buildTitle(s) ?? ''; this.appliquer(); }
  private appliquer(): void { this.title.setTitle(this.courant ? `${this.i18n.t(this.courant)} · NEXORA` : 'NEXORA'); }
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withFetch(), withInterceptors([jetonInterceptor])),
    { provide: TitleStrategy, useClass: TitreTraduit },
  ],
};
