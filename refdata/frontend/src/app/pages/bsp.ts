import { Component, computed, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { PHASES, Phase, TableSummary } from '../core/models';
import { Icon } from '../shared/icon';

/** Les tables du référentiel vues à travers le modèle de référence de la chaîne logistique internationale (UN/CEFACT ISCRM). */
@Component({
  selector: 'nx-bsp-page',
  imports: [RouterLink, DecimalPipe, Icon],
  template: `
    <div>
      <h1>Buy · Ship · Pay</h1>
      <p class="muted">Modèle de référence de la chaîne logistique internationale de l'UN/CEFACT : chaque table de référence est rattachée
        aux phases de la transaction commerciale où elle intervient.</p>
    </div>
    <div class="chaine">
      @for (p of phases; track p.code) {
        <section class="phase card" [attr.data-p]="p.code">
          <header>
            <span class="lettre">{{ p.label[0] }}</span>
            <div><h2>{{ p.label }} <span class="muted">— {{ p.sub }}</span></h2><span class="small muted">{{ tables(p.code).length }} tables</span></div>
          </header>
          <div class="processus">@for (x of p.processes; track x) { <span class="chip">{{ x }}</span> }</div>
          <input class="input" placeholder="Filtrer…" [value]="filtre()[p.code] ?? ''" (input)="filtrer(p.code, $any($event.target).value)" [attr.aria-label]="'Filtrer ' + p.label" />
          <div class="liste">
            @for (t of tables(p.code); track t.code) {
              <a [routerLink]="['/tables', t.code]" class="item">
                <span class="grow"><b>{{ t.nameFr }}</b><span class="code-tag">{{ t.code }}</span></span>
                @if (t.entryCount) { <span class="mono small">{{ t.entryCount | number: '1.0-0' : 'fr-FR' }}</span> } @else { <span class="chip warn">vide</span> }
              </a>
            }
          </div>
        </section>
        @if (!$last) { <div class="fleche"><nx-icon name="chevron" [size]="26" /></div> }
      }
    </div>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 16px; }
    p { margin: 4px 0 0; max-width: 820px; }
    .chaine { display: grid; grid-template-columns: 1fr auto 1fr auto 1fr; gap: 8px; align-items: start; }
    .fleche { align-self: center; color: var(--muted); }
    .phase { display: flex; flex-direction: column; gap: 10px; padding: 16px; border-top: 4px solid var(--c); }
    .phase[data-p=BUY] { --c: var(--buy); } .phase[data-p=SHIP] { --c: var(--ship); } .phase[data-p=PAY] { --c: var(--pay); }
    header { display: flex; gap: 12px; align-items: center; }
    .lettre { width: 44px; height: 44px; border-radius: 13px; background: var(--c); color: #fff; display: grid; place-items: center; font-size: 22px; font-weight: 800; }
    .processus { display: flex; flex-wrap: wrap; gap: 4px; }
    .liste { display: flex; flex-direction: column; gap: 2px; max-height: 62vh; overflow-y: auto; }
    .item { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 9px; color: var(--text); }
    .item:hover { background: color-mix(in srgb, var(--c) 8%, transparent); text-decoration: none; }
    .item b { display: block; font-weight: 600; }
    @media (max-width: 1100px) { .chaine { grid-template-columns: 1fr; } .fleche { transform: rotate(90deg); justify-self: center; } }
  `],
})
export class BspPage {
  private readonly api = inject(ApiService);
  protected readonly phases = PHASES;
  protected readonly toutes = signal<TableSummary[]>([]);
  protected readonly filtre = signal<Record<string, string>>({});

  constructor() {
    this.api.tables().subscribe(t => this.toutes.set(t));
  }

  protected tables(p: Phase): TableSummary[] {
    const f = (this.filtre()[p] ?? '').toLowerCase();
    return this.toutes().filter(t => t.bspPhases.includes(p) && (!f || `${t.code} ${t.nameFr}`.toLowerCase().includes(f)))
      .sort((a, b) => b.entryCount - a.entryCount || a.nameFr.localeCompare(b.nameFr));
  }

  protected filtrer(p: Phase, v: string): void { this.filtre.set({ ...this.filtre(), [p]: v }); }
}
