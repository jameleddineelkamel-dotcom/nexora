import { Component } from '@angular/core';
import { HistoryList } from '../shared/history-list';
import { TPipe } from '../core/i18n';

@Component({
  selector: 'nx-journal',
  imports: [HistoryList, TPipe],
  template: `
    <div>
      <h1>{{ 'Historique des modifications' | t }}</h1>
      <p class="muted">{{ 'Toute création, modification, invalidation ou réactivation — par l\\'interface, l\\'API, un import ou un SQL direct — est tracée par la base de données avec son auteur, son canal, son motif et le détail avant / après.' | t }}</p>
    </div>
    <nx-history-list [filtres]="true" [taille]="40" />
  `,
  styles: [':host { display: flex; flex-direction: column; gap: 16px; } p { margin: 4px 0 0; max-width: 860px; }'],
})
export class JournalPage {}
