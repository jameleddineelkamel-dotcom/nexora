import { Routes } from '@angular/router';
import { DROITS, exigeDroit } from './core/auth.service';

const AGENTS = [DROITS.traiter, DROITS.signer];
const SUPERVISION = [DROITS.superviser, DROITS.administrer];

export const routes: Routes = [
  { path: '', title: 'Accueil', loadComponent: () => import('./pages/accueil').then(m => m.AccueilPage) },
  { path: 'nouvelle-demande', title: 'Nouvelle demande', canActivate: [exigeDroit(DROITS.deposer)], loadComponent: () => import('./pages/nouvelle-demande').then(m => m.NouvelleDemandePage) },
  { path: 'demandes', title: 'Mes demandes', canActivate: [exigeDroit(DROITS.deposer)], loadComponent: () => import('./pages/liste-dossiers').then(m => m.ListeDossiersPage), data: { scope: 'mine' } },
  { path: 'demandes/:id', title: 'Demande', loadComponent: () => import('./pages/dossier/dossier-page').then(m => m.DossierPage) },
  { path: 'taches', title: 'Mes tâches', loadComponent: () => import('./pages/taches').then(m => m.TachesPage) },
  { path: 'dossiers', title: 'Dossiers', canActivate: [exigeDroit(...AGENTS, ...SUPERVISION)], loadComponent: () => import('./pages/liste-dossiers').then(m => m.ListeDossiersPage), data: { scope: 'assigned' } },
  { path: 'supervision', title: 'Supervision', canActivate: [exigeDroit(...SUPERVISION)], loadComponent: () => import('./pages/supervision').then(m => m.SupervisionPage) },
  { path: 'supervision/dossiers', title: 'Tous les dossiers', canActivate: [exigeDroit(...SUPERVISION)], loadComponent: () => import('./pages/liste-dossiers').then(m => m.ListeDossiersPage), data: { scope: 'all' } },
  { path: 'paiements', title: 'Encaissements', canActivate: [exigeDroit(...SUPERVISION)], loadComponent: () => import('./pages/paiements').then(m => m.PaiementsPage) },
  { path: 'messages', title: 'Messages', canActivate: [exigeDroit(...SUPERVISION)], loadComponent: () => import('./pages/messages').then(m => m.MessagesPage) },
  { path: 'administration', title: 'Administration', canActivate: [exigeDroit(DROITS.administrer)], loadComponent: () => import('./pages/administration').then(m => m.AdministrationPage) },
  { path: 'verifier', title: 'Vérifier un document', loadComponent: () => import('./pages/verifier').then(m => m.VerifierPage) },
  { path: 'verifier/:no', title: 'Vérifier un document', loadComponent: () => import('./pages/verifier').then(m => m.VerifierPage) },
  { path: '**', redirectTo: '' },
];
