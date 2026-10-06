import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Phase } from '../core/models';
import { TPipe } from '../core/i18n';

/** Anneau de complétude de la fiche ISO 19115. */
@Component({
  selector: 'nx-ring',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg [attr.width]="size()" [attr.height]="size()" viewBox="0 0 36 36" role="img" [attr.aria-label]="value() + ' % complété'">
      <circle cx="18" cy="18" r="15.5" fill="none" stroke="var(--line)" stroke-width="3.2" />
      <circle cx="18" cy="18" r="15.5" fill="none" [attr.stroke]="couleur()" stroke-width="3.2" stroke-linecap="round"
        [attr.stroke-dasharray]="dash()" transform="rotate(-90 18 18)" />
      <text x="18" y="21.5" text-anchor="middle" [attr.font-size]="size() > 44 ? 10 : 9.5" font-weight="650" fill="var(--text)">{{ value() }}</text>
    </svg>`,
  styles: [':host { display: inline-flex; line-height: 0; }'],
})
export class Ring {
  readonly value = input(0);
  readonly size = input(38);
  protected readonly dash = computed(() => `${(Math.max(0, Math.min(100, this.value())) / 100) * 97.4} 97.4`);
  protected readonly couleur = computed(() => (this.value() >= 85 ? 'var(--ok)' : this.value() >= 60 ? 'var(--accent)' : 'var(--warn)'));
}

/** Pastilles Buy / Ship / Pay (modèle de référence de la chaîne logistique internationale UN/CEFACT). */
@Component({
  selector: 'nx-bsp',
  imports: [TPipe],
  template: `@for (p of tout; track p) {
      <span class="bsp" [class.on]="phases().includes(p)" [attr.data-p]="p" [title]="titres[p] | t">{{ p[0] }}</span>
    }`,
  styles: [`
    :host { display: inline-flex; gap: 3px; }
    .bsp { width: 20px; height: 20px; border-radius: 6px; display: inline-grid; place-items: center; font-size: 11px; font-weight: 700;
      color: var(--muted); background: var(--chip); opacity: .45; }
    .bsp.on { opacity: 1; color: #fff; }
    .bsp.on[data-p=BUY] { background: var(--buy); } .bsp.on[data-p=SHIP] { background: var(--ship); } .bsp.on[data-p=PAY] { background: var(--pay); }
  `],
})
export class Bsp {
  readonly phases = input<Phase[]>([]);
  protected readonly tout: Phase[] = ['BUY', 'SHIP', 'PAY'];
  protected readonly titres: Record<Phase, string> = { BUY: 'Buy — acheter', SHIP: 'Ship — expédier', PAY: 'Pay — payer' };
}

/** Courbe d'activité (SVG). */
@Component({
  selector: 'nx-spark',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg [attr.viewBox]="'0 0 ' + w + ' ' + h" preserveAspectRatio="none" class="spark" role="img" aria-label="Activité des 30 derniers jours">
      <defs><linearGradient id="g" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stop-color="var(--accent)" stop-opacity=".35" />
        <stop offset="1" stop-color="var(--accent)" stop-opacity="0" /></linearGradient></defs>
      <path [attr.d]="aire()" fill="url(#g)" />
      <path [attr.d]="ligne()" fill="none" stroke="var(--accent)" stroke-width="2" vector-effect="non-scaling-stroke" />
    </svg>`,
  styles: [':host { display: block; } .spark { width: 100%; height: 64px; display: block; }'],
})
export class Spark {
  readonly values = input<number[]>([]);
  protected readonly w = 300;
  protected readonly h = 64;
  private readonly points = computed(() => {
    const v = this.values().length ? this.values() : [0];
    const max = Math.max(1, ...v);
    return v.map((x, i) => [v.length === 1 ? this.w : (i / (v.length - 1)) * this.w, this.h - 4 - (x / max) * (this.h - 10)]);
  });
  protected readonly ligne = computed(() => this.points().map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(' '));
  protected readonly aire = computed(() => `${this.ligne()} L${this.w},${this.h} L0,${this.h} Z`);
}
