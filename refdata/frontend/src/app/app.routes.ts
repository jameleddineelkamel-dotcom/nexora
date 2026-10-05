import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', title: 'Tableau de bord · NEXORA Référentiel', loadComponent: () => import('./pages/dashboard').then(m => m.DashboardPage) },
  { path: 'catalogue', title: 'Catalogue · NEXORA Référentiel', loadComponent: () => import('./pages/catalogue').then(m => m.CataloguePage) },
  { path: 'catalogue/:category', title: 'Catalogue · NEXORA Référentiel', loadComponent: () => import('./pages/catalogue').then(m => m.CataloguePage) },
  { path: 'tables/:code', title: 'Table · NEXORA Référentiel', loadComponent: () => import('./pages/table/table-page').then(m => m.TablePage) },
  { path: 'nouvelle-table', title: 'Nouvelle table · NEXORA Référentiel', loadComponent: () => import('./pages/new-table').then(m => m.NewTablePage) },
  { path: 'buy-ship-pay', title: 'Buy-Ship-Pay · NEXORA Référentiel', loadComponent: () => import('./pages/bsp').then(m => m.BspPage) },
  { path: 'historique', title: 'Historique · NEXORA Référentiel', loadComponent: () => import('./pages/journal').then(m => m.JournalPage) },
  { path: 'chargements', title: 'Chargements · NEXORA Référentiel', loadComponent: () => import('./pages/imports').then(m => m.ImportsPage) },
  { path: '**', redirectTo: '' },
];
