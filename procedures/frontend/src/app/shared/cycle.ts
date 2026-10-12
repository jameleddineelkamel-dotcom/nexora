import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { I18N } from '../core/i18n';

export const STATUTS: Record<string, { libelle: string; ton: string }> = {
  draft: { libelle: 'Brouillon', ton: 'neutral' }, in_review: { libelle: 'En revue', ton: 'warn' }, approved: { libelle: 'Approuvée', ton: 'info' },
  published: { libelle: 'Publiée', ton: 'ok' }, superseded: { libelle: 'Remplacée', ton: 'neutral' }, retired: { libelle: 'Retirée', ton: 'danger' },
};

/** Statut de cycle de vie d'une version (pastilles de la charte : gris brouillon, orange en revue, vert publié…). */
@Component({
  selector: 'nx-cycle',
  imports: [...I18N],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span [class]="'chip ' + def().ton">{{ def().libelle | t }}</span>`,
})
export class Cycle {
  readonly statut = input.required<string>();
  protected readonly def = computed(() => STATUTS[this.statut()] ?? { libelle: this.statut(), ton: 'neutral' });
}
