import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { TitleStrategy, provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { TitreTraduit } from './core/titre';
import { routes } from './app.routes';
import { auditInterceptor } from './core/api.service';
import { AuthService, jetonInterceptor } from './core/auth.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding(), withInMemoryScrolling({ scrollPositionRestoration: 'top' })),
    provideHttpClient(withFetch(), withInterceptors([jetonInterceptor, auditInterceptor])),
    { provide: TitleStrategy, useClass: TitreTraduit },
    // Authentification unique : l'application ne démarre qu'avec un jeton valide
    provideAppInitializer(() => inject(AuthService).initialiser()),
  ],
};
