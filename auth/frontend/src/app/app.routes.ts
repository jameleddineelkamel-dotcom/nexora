import { Routes } from '@angular/router';
import { exigeSession } from './core/acces';

export const routes: Routes = [
  { path: 'login', title: 'Connexion', loadComponent: () => import('./pages/login').then(m => m.LoginPage) },
  { path: 'compte', title: 'Mon compte', canActivate: [exigeSession(false)], loadComponent: () => import('./pages/compte').then(m => m.ComptePage) },
  { path: 'admin', redirectTo: 'admin/utilisateurs', pathMatch: 'full' },
  { path: 'admin/:onglet', title: 'Administration des accès', canActivate: [exigeSession(true)], loadComponent: () => import('./pages/admin').then(m => m.AdminPage) },
  { path: '**', redirectTo: 'login' },
];
