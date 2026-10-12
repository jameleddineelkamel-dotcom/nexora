import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', title: 'Tableau de bord', loadComponent: () => import('./pages/tableau-de-bord').then(m => m.TableauDeBordPage) },
  { path: 'procedures', title: 'Procédures', loadComponent: () => import('./pages/procedures').then(m => m.ProceduresPage) },
  { path: 'procedures/:code', title: 'Procédure', loadComponent: () => import('./pages/procedure').then(m => m.ProcedurePage) },
  { path: 'versions/:id', title: 'Version de procédure', loadComponent: () => import('./pages/version/version-page').then(m => m.VersionPage) },
  { path: 'terminologie', title: 'Terminologie GUCE', loadComponent: () => import('./pages/terminologie').then(m => m.TerminologiePage) },
  { path: 'dictionnaire', title: 'Dictionnaire SCRDM', loadComponent: () => import('./pages/dictionnaire').then(m => m.DictionnairePage) },
  { path: 'agents', title: 'Agents IA', loadComponent: () => import('./pages/agents').then(m => m.AgentsPage) },
  { path: 'journal', title: 'Journal d\'audit', loadComponent: () => import('./pages/journal').then(m => m.JournalPage) },
  { path: '**', redirectTo: '' },
];
