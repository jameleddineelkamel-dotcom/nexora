import { Component, DestroyRef, inject, input, model, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, catchError, debounceTime, of, switchMap } from 'rxjs';
import { ApiService } from '../core/api.service';
import { LookupItem } from '../core/models';

/** Saisie d'un code d'une autre table de référence, avec suggestions (service lookup). */
@Component({
  selector: 'nx-ref-picker',
  template: `
    <div class="picker">
      <input class="input mono" [value]="value() ?? ''" [placeholder]="'Code ' + table()" [attr.aria-label]="label()"
        (input)="saisir($any($event.target).value)" (focus)="saisir(value() ?? '')" (blur)="fermer()" autocomplete="off" />
      @if (ouvert() && suggestions().length) {
        <ul class="liste">
          @for (s of suggestions(); track s.code) {
            <li (mousedown)="choisir(s)"><span class="code-tag">{{ s.code }}</span> {{ s.label }}</li>
          }
        </ul>
      }
    </div>`,
  styles: [`
    .picker { position: relative; }
    .liste { position: absolute; z-index: 5; left: 0; right: 0; top: calc(100% + 4px); margin: 0; padding: 4px; list-style: none;
      background: var(--surface); border: 1px solid var(--line); border-radius: 10px; box-shadow: var(--shadow-lg); max-height: 240px; overflow-y: auto; }
    li { padding: 7px 9px; border-radius: 7px; cursor: pointer; font-size: 13px; }
    li:hover { background: var(--accent-soft); }
  `],
})
export class RefPicker {
  private readonly api = inject(ApiService);
  readonly table = input.required<string>();
  readonly label = input('');
  readonly value = model<string | null>(null);
  protected readonly suggestions = signal<LookupItem[]>([]);
  protected readonly ouvert = signal(false);
  private readonly q$ = new Subject<string>();

  constructor() {
    this.q$.pipe(debounceTime(150), switchMap(q => this.api.lookup(this.table(), q, 12).pipe(catchError(() => of([])))),
      takeUntilDestroyed(inject(DestroyRef))).subscribe(s => this.suggestions.set(s));
  }

  protected saisir(v: string): void {
    this.value.set(v.trim() ? v : null);
    this.ouvert.set(true);
    this.q$.next(v);
  }

  protected choisir(s: LookupItem): void {
    this.value.set(s.code);
    this.ouvert.set(false);
  }

  protected fermer(): void {
    setTimeout(() => this.ouvert.set(false), 120);
  }
}
