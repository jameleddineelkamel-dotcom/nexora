import { Routes } from '@angular/router';
import { DROITS, exigeDroit } from './core/auth.service';

export const routes: Routes = [
  { path: '', title: 'Tableau de bord', loadComponent: () => import('./pages/tableau-de-bord').then(m => m.TableauDeBordPage) },
  { path: 'operateurs', title: 'Opérateurs économiques', loadComponent: () => import('./pages/operateurs').then(m => m.OperateursPage) },
  { path: 'operateurs/:niu', title: 'Opérateur', loadComponent: () => import('./pages/operateur').then(m => m.OperateurPage) },
  { path: 'nouvel-operateur', title: 'Enrôler un opérateur', canActivate: [exigeDroit(DROITS.gerer)], loadComponent: () => import('./pages/operateur').then(m => m.OperateurPage) },
  { path: '**', redirectTo: '' },
];
