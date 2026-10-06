import { Injectable, Pipe, PipeTransform, computed, effect, inject, signal } from '@angular/core';
import { EN } from './i18n-en';

export type Langue = 'fr' | 'en';

/** Langues de l'interface : le français est la langue source, les autres sont des dictionnaires (ajout d'une langue = un fichier). */
export const LANGUES: { code: Langue; libelle: string; locale: string }[] = [
  { code: 'fr', libelle: 'Français', locale: 'fr-FR' },
  { code: 'en', libelle: 'English', locale: 'en-GB' },
];
const DICTIONNAIRES: Record<Langue, Record<string, string>> = { fr: {}, en: EN };

@Injectable({ providedIn: 'root' })
export class I18n {
  readonly langue = signal<Langue>(I18n.initiale());
  readonly locale = computed(() => LANGUES.find(l => l.code === this.langue())!.locale);

  constructor() {
    effect(() => {
      document.documentElement.lang = this.langue();
      try { localStorage.setItem('nexora.lang', this.langue()); } catch { /* stockage indisponible */ }
    });
  }

  /** Traduit un texte source (français) ; {0}, {1}… sont remplacés par les paramètres. */
  t(source: string, ...params: unknown[]): string {
    let s = DICTIONNAIRES[this.langue()][source] ?? source;
    params.forEach((p, i) => { s = s.replace(`{${i}}`, String(p)); });
    return s;
  }

  /** Libellé d'une donnée dans la langue courante, avec repli sur le français. */
  libelle(fr: string | null | undefined, en: string | null | undefined): string {
    return (this.langue() === 'en' && en ? en : fr ?? en) ?? '';
  }

  nombre(n: number | null | undefined): string {
    return n === null || n === undefined ? '–' : new Intl.NumberFormat(this.locale()).format(n);
  }

  private static initiale(): Langue {
    let l: string | null = null;
    try { l = localStorage.getItem('nexora.lang'); } catch { /* stockage indisponible */ }
    if (l === 'fr' || l === 'en') return l;
    return navigator.language?.toLowerCase().startsWith('fr') ? 'fr' : 'en';
  }
}

/** {{ 'Texte source' | t }} — impur pour suivre le changement de langue. */
@Pipe({ name: 't', pure: false })
export class TPipe implements PipeTransform {
  private readonly i18n = inject(I18n);
  transform(source: string, ...params: unknown[]): string {
    return this.i18n.t(source, ...params);
  }
}

/** {{ n | num }} — nombre au format de la langue courante. */
@Pipe({ name: 'num', pure: false })
export class NumPipe implements PipeTransform {
  private readonly i18n = inject(I18n);
  transform(n: number | null | undefined): string {
    return this.i18n.nombre(n);
  }
}

/** {{ fr | lib: en }} — libellé de donnée dans la langue courante. */
@Pipe({ name: 'lib', pure: false })
export class LibPipe implements PipeTransform {
  private readonly i18n = inject(I18n);
  transform(fr: string | null | undefined, en: string | null | undefined): string {
    return this.i18n.libelle(fr, en);
  }
}

export const I18N = [TPipe, NumPipe, LibPipe];
