import { Component, DestroyRef, ElementRef, HostListener, computed, effect, inject, signal, viewChild } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, switchMap, of, catchError } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiService, SessionService } from './core/api.service';
import { SearchResults } from './core/models';
import { Icon } from './shared/icon';

interface Resultat { type: 'table' | 'code'; titre: string; sous: string; code: string; lien: string[]; query?: Record<string, string>; }

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icon],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionService);

  protected readonly nav = [
    { lien: '/', icone: 'home', libelle: 'Tableau de bord', exact: true },
    { lien: '/catalogue', icone: 'grid', libelle: 'Catalogue', exact: false },
    { lien: '/buy-ship-pay', icone: 'route', libelle: 'Buy · Ship · Pay', exact: false },
    { lien: '/historique', icone: 'history', libelle: 'Historique', exact: false },
    { lien: '/chargements', icone: 'upload', libelle: 'Chargements', exact: false },
  ];
  protected readonly menuOuvert = signal(false);

  // Palette de recherche (Ctrl+K)
  protected readonly palette = signal(false);
  protected readonly requete = signal('');
  protected readonly resultats = signal<SearchResults | null>(null);
  protected readonly recherche = signal(false);
  protected readonly selection = signal(0);
  private readonly saisie$ = new Subject<string>();
  private readonly champ = viewChild<ElementRef<HTMLInputElement>>('champ');
  protected readonly liste = computed<Resultat[]>(() => {
    const r = this.resultats();
    if (!r) return [];
    return [
      ...r.tables.map(t => ({ type: 'table' as const, titre: t.nameFr, sous: `${t.entryCount.toLocaleString('fr-FR')} codes${t.standards ? ' · ' + t.standards.split('\n')[0] : ''}`, code: t.code, lien: ['/tables', t.code] })),
      ...r.entries.map(e => ({ type: 'code' as const, titre: e.labelFr ?? e.code, sous: e.tableName, code: e.code, lien: ['/tables', e.tableCode], query: { q: e.code } })),
    ];
  });

  // Utilisateur courant (historique)
  protected readonly editionUtilisateur = signal(false);

  constructor() {
    effect(() => document.documentElement.setAttribute('data-theme', this.session.theme()));
    this.saisie$.pipe(
      debounceTime(180), distinctUntilChanged(),
      switchMap(q => {
        if (q.trim().length < 2) { this.recherche.set(false); return of(null); }
        this.recherche.set(true);
        return this.api.rechercher(q).pipe(catchError(() => of(null)));
      }),
      takeUntilDestroyed(inject(DestroyRef)),
    ).subscribe(r => { this.resultats.set(r); this.selection.set(0); this.recherche.set(false); });
    if (!this.session.utilisateur()) this.editionUtilisateur.set(true);
  }

  @HostListener('document:keydown', ['$event'])
  protected clavier(e: KeyboardEvent): void {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); this.ouvrirPalette(); return; }
    if (!this.palette()) return;
    const n = this.liste().length;
    if (e.key === 'Escape') this.fermerPalette();
    else if (e.key === 'ArrowDown' && n) { e.preventDefault(); this.selection.set((this.selection() + 1) % n); }
    else if (e.key === 'ArrowUp' && n) { e.preventDefault(); this.selection.set((this.selection() - 1 + n) % n); }
    else if (e.key === 'Enter' && n) { e.preventDefault(); this.aller(this.liste()[this.selection()]); }
  }

  protected ouvrirPalette(): void {
    this.palette.set(true);
    setTimeout(() => this.champ()?.nativeElement.focus());
  }

  protected fermerPalette(): void {
    this.palette.set(false);
  }

  protected saisir(q: string): void {
    this.requete.set(q);
    this.saisie$.next(q);
  }

  protected aller(r: Resultat): void {
    this.fermerPalette();
    this.router.navigate(r.lien, { queryParams: r.query });
  }

  protected enregistrerUtilisateur(nom: string): void {
    if (!nom.trim()) return;
    this.session.definirUtilisateur(nom);
    this.editionUtilisateur.set(false);
  }

  protected initiales(): string {
    return this.session.utilisateur().split(/[\s.\-_]+/).filter(Boolean).slice(0, 2).map(m => m[0].toUpperCase()).join('') || '?';
  }
}
