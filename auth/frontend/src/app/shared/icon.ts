import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** Icônes au trait (24×24), dessinées en ligne pour ne dépendre d'aucune bibliothèque. */
const ICONES: Record<string, string> = {
  home: 'M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z',
  grid: 'M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z',
  globe: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM3 12h18M12 3c2.5 2.7 3.8 5.7 3.8 9s-1.3 6.3-3.8 9c-2.5-2.7-3.8-5.7-3.8-9S9.5 5.7 12 3z',
  flag: 'M5 21V4M5 4h11l-2 4 2 4H5',
  landmark: 'M3 21h18M5 21V10M9 21V10M15 21V10M19 21V10M2 10l10-6 10 6z',
  layers: 'M12 3 2 8l10 5 10-5zM2 13l10 5 10-5M2 18l10 5 10-5',
  stamp: 'M8 3h8l-1 7h-6zM5 14h14v3H5zM4 21h16',
  ship: 'M3 17c3 3 6 3 9 0 3 3 6 3 9 0M5 15l-1-5h16l-1 5M8 10V5h8v5M12 2v3',
  microscope: 'M9 3h4l1 5h-6zM10 8v6a4 4 0 0 0 8 0M6 21h12M10 21v-3',
  leaf: 'M5 21c0-9 5-15 15-16-1 10-7 15-15 16zM5 21l8-8',
  car: 'M5 16h14l-1.5-5.5A2 2 0 0 0 15.6 9H8.4a2 2 0 0 0-1.9 1.5zM4 16v3h3v-2M17 17v2h3v-3M7.5 13h.01M16.5 13h.01',
  search: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM21 21l-5-5',
  plus: 'M12 5v14M5 12h14',
  upload: 'M12 16V4M7 9l5-5 5 5M4 20h16',
  download: 'M12 4v12M7 11l5 5 5-5M4 20h16',
  history: 'M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5M12 7v5l3 3',
  table: 'M3 5h18v14H3zM3 10h18M9 5v14',
  x: 'M6 6l12 12M18 6 6 18',
  check: 'M5 12l5 5 9-10',
  edit: 'M4 20h4L19 9l-4-4L4 16zM14 6l4 4',
  ban: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM5.6 5.6l12.8 12.8',
  refresh: 'M20 11a8 8 0 0 0-14.9-3M4 4v4h4M4 13a8 8 0 0 0 14.9 3M20 20v-4h-4',
  info: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 11v6M12 7.5h.01',
  code: 'M8 7l-5 5 5 5M16 7l5 5-5 5M14 4l-4 16',
  database: 'M12 3c4.4 0 8 1.3 8 3s-3.6 3-8 3-8-1.3-8-3 3.6-3 8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3',
  sun: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4',
  moon: 'M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5z',
  sparkles: 'M12 3l1.8 4.7L18.5 9.5l-4.7 1.8L12 16l-1.8-4.7L5.5 9.5l4.7-1.8zM19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z',
  file: 'M6 3h8l5 5v13H6zM14 3v5h5',
  alert: 'M12 3 2 20h20zM12 10v4M12 17h.01',
  chevron: 'M9 6l6 6-6 6',
  back: 'M15 6l-6 6 6 6',
  user: 'M12 4a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM4 21a8 8 0 0 1 16 0',
  tag: 'M3 12V4h8l10 10-8 8zM7.5 7.5h.01',
  link: 'M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1',
  calendar: 'M4 5h16v16H4zM4 9h16M8 3v4M16 3v4',
  archive: 'M3 4h18v4H3zM5 8v12h14V8M10 12h4',
  tree: 'M12 3v6M12 9H6v4M12 9h6v4M4 13h4v4H4zM16 13h4v4h-4zM10 13h4v4h-4z',
  route: 'M6 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM18 9a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM6 15V9a3 3 0 0 1 3-3h7M18 9v6a3 3 0 0 1-3 3H8',
  wallet: 'M3 7h16a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H3zM3 7l12-4v4M17 13h.01',
  cart: 'M3 4h2l2.5 11h11L21 7H7M9 20h.01M18 20h.01',
  command: 'M9 6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3z',
};

@Component({
  selector: 'nx-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<svg [attr.width]="size()" [attr.height]="size()" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    [attr.stroke-width]="stroke()" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path [attr.d]="d()" /></svg>`,
  styles: [':host { display: inline-flex; flex-shrink: 0; line-height: 0; }'],
})
export class Icon {
  readonly name = input.required<string>();
  readonly size = input(18);
  readonly stroke = input(1.8);
  protected readonly d = computed(() => ICONES[this.name()] ?? ICONES['tag']);
}
