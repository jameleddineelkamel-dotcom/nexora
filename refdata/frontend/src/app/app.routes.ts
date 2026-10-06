import { Routes } from '@angular/router';
import { DROITS, exigeDroit } from './core/auth.service';

export const routes: Routes = [
  { path: '', title: 'Tableau de bord', loadComponent: () => import('./pages/dashboard').then(m => m.DashboardPage) },
  { path: 'catalogue', title: 'Catalogue', loadComponent: () => import('./pages/catalogue').then(m => m.CataloguePage) },
  { path: 'catalogue/:category', title: 'Catalogue', loadComponent: () => import('./pages/catalogue').then(m => m.CataloguePage) },
  { path: 'tables/:code', title: 'Table', loadComponent: () => import('./pages/table/table-page').then(m => m.TablePage) },
  { path: 'nouvelle-table', title: 'Nouvelle table', canActivate: [exigeDroit(DROITS.structure)], loadComponent: () => import('./pages/new-table').then(m => m.NewTablePage) },
  { path: 'buy-ship-pay', title: 'Buy-Ship-Pay', loadComponent: () => import('./pages/bsp').then(m => m.BspPage) },
  { path: 'historique', title: 'Historique', loadComponent: () => import('./pages/journal').then(m => m.JournalPage) },
  { path: 'chargements', title: 'Chargements', loadComponent: () => import('./pages/imports').then(m => m.ImportsPage) },
  { path: '**', redirectTo: '' },
];
