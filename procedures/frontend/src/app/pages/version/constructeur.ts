import { Component, computed, effect, inject, input, output, signal, untracked, viewChild } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { CdkDrag, CdkDragDrop, CdkDragHandle, CdkDragPlaceholder, CdkDropList, CdkDropListGroup, moveItemInArray } from '@angular/cdk/drag-drop';
import { Field, Section, VersionComplete } from '../../core/api';
import { I18N, I18n } from '../../core/i18n';
import { ApercuFormulaire } from '../../shared/apercu-formulaire';
import { Icon } from '../../shared/icon';
import { ModificationChamp, ProprietesChamp, TYPES_CHAMP } from './constructeur-proprietes';
import { Enregistrement } from './general';
import { ReglageChamp, gabarit, reglagesChamps } from './ihm';

/** Objet glissé : composant de la palette, champ, onglet (section de premier niveau) ou sous-section. */
type Glisse = { kind: 'palette'; type: string } | { kind: 'champ'; code: string } | { kind: 'onglet'; code: string } | { kind: 'sous'; code: string };
type Selection = { kind: 'champ' | 'section'; code: string } | null;
type Edition = { kind: 'champ' | 'section'; code: string; fr: string; en: string } | null;

/**
 * Section de premier niveau (onglet) : sans parent, ou dont le parent est absent ou lui-même une sous-section (un seul
 * niveau d'imbrication ; une section plus profonde remonte au premier niveau plutôt que de disparaître).
 */
function estHaut(s: Section, toutes: Section[]): boolean {
  const p = s.parentCode ? toutes.find(x => x.code === s.parentCode) : null;
  return !p || !!p.parentCode;
}

/**
 * Constructeur visuel du formulaire, à la manière de form.io : sections de premier niveau en onglets, sous-sections en
 * accordéons (un seul niveau), champs glissés depuis la palette ou déplacés d'une section à l'autre (Angular CDK).
 * Le panneau de droite règle le champ sélectionné, l'aperçu reproduit le rendu e-Services. L'enregistrement remplace la
 * partie « form » (sections, champs, ordre de 10 en 10) et les réglages d'affichage par champ (« ihm.champs »).
 */
@Component({
  selector: 'nx-constructeur',
  imports: [NgTemplateOutlet, CdkDropListGroup, CdkDropList, CdkDrag, CdkDragHandle, CdkDragPlaceholder, Icon, ProprietesChamp, ApercuFormulaire, ...I18N],
  template: `
    <div class="constructeur" cdkDropListGroup>
      <!-- Palette des composants -->
      <aside class="card pad palette">
        <div class="titre-col">{{ 'Composants' | t }}</div>
        <div cdkDropList id="nx-palette" class="composants" [cdkDropListSortingDisabled]="true" [cdkDropListEnterPredicate]="jamais" [cdkDropListDisabled]="!modifiable()">
          @for (t of types; track t.code) {
            <div class="composant" cdkDrag [cdkDragData]="{ kind: 'palette', type: t.code }" [title]="'Glisser dans une section' | t">
              <nx-icon [name]="t.icone" [size]="15" /> <span>{{ t.libelle | t }}</span>
              <div *cdkDragPlaceholder class="composant fantome"><nx-icon [name]="t.icone" [size]="15" /> {{ t.libelle | t }}</div>
            </div>
          }
        </div>
        @if (modifiable()) {
          <div class="sep"></div>
          <button class="btn sm" (click)="ajouterSection(null)"><nx-icon name="plus" [size]="14" /> {{ 'Onglet' | t }}</button>
          <button class="btn sm" (click)="ajouterSection(ongletActif())" [disabled]="!ongletActif()"><nx-icon name="plus" [size]="14" /> {{ 'Sous-section' | t }}</button>
          <p class="muted small">{{ 'Glissez un composant dans une section ; glissez les onglets, les accordéons et les champs pour les réordonner.' | t }}</p>
        } @else {
          <p class="muted small">{{ 'Lecture seule : seule une version brouillon se modifie.' | t }}</p>
        }
      </aside>

      <!-- Canevas : onglets, champs, accordéons -->
      <div class="card pad canevas">
        @if (!dispositionOnglets()) {
          <div class="alert info small"><nx-icon name="info" [size]="15" />
            <span>{{ 'Le gabarit d\\'IHM actuel n\\'affiche pas les sections en onglets et les sous-sections en accordéons : choisissez le modèle « Onglets et accordéons » dans le Gabarit d\\'IHM pour un rendu identique au constructeur.' | t }}</span></div>
        }
        <div cdkDropList id="nx-onglets" class="onglets" cdkDropListOrientation="horizontal" [cdkDropListEnterPredicate]="accepteOnglet"
          [cdkDropListDisabled]="!modifiable()" (cdkDropListDropped)="deposerOnglet($event)">
          @for (s of hauts(); track s.code) {
            <div class="onglet" cdkDrag [cdkDragData]="{ kind: 'onglet', code: s.code }" [class.on]="s.code === ongletActif()" (click)="ouvrirOnglet(s.code)">
              {{ s.labelFr || s.code }} <span class="nb">{{ total(s.code) }}</span>
            </div>
          }
          @if (modifiable()) { <button type="button" class="btn icon ghost sm" (click)="ajouterSection(null)" [attr.aria-label]="'Ajouter un onglet' | t" [title]="'Ajouter un onglet' | t"><nx-icon name="plus" [size]="15" /></button> }
        </div>

        @if (section(ongletActif()); as o) {
          <div class="entete" [class.on]="estChoisie('section', o.code)" (click)="choisir('section', o.code)">
            <ng-container *ngTemplateOutlet="libelle; context: { kind: 'section', s: o }" />
            @if (o.repeating) { <span class="chip">{{ 'Répétitif' | t }}</span> }
          </div>
          <ng-container *ngTemplateOutlet="liste; context: { s: o }" />

          <div cdkDropList [id]="'nx-sous-' + o.code" class="sous" [cdkDropListData]="o.code" [cdkDropListEnterPredicate]="accepteSous"
            [cdkDropListDisabled]="!modifiable()" (cdkDropListDropped)="deposerSous($event)">
            @for (e of enfants(o.code); track e.code) {
              <div class="accordeon" cdkDrag [cdkDragData]="{ kind: 'sous', code: e.code }" [class.ouvert]="!fermes().has(e.code)">
                <div class="acc-tete" [class.on]="estChoisie('section', e.code)" (click)="choisir('section', e.code)">
                  @if (modifiable()) { <span class="poignee" cdkDragHandle [title]="'Glisser pour réordonner' | t"><nx-icon name="selector" [size]="14" /></span> }
                  <ng-container *ngTemplateOutlet="libelle; context: { kind: 'section', s: e }" />
                  @if (e.repeating) { <span class="chip">{{ 'Répétitif' | t }}</span> }
                  <span class="muted small">{{ champsDe(e.code).length }} {{ 'champ(s)' | t }}</span>
                  <button type="button" class="btn icon ghost sm" (click)="basculer(e.code); $event.stopPropagation()" [attr.aria-expanded]="!fermes().has(e.code)"
                    [attr.aria-label]="'Ouvrir ou fermer' | t"><nx-icon name="chevron" [size]="15" class="chevron" /></button>
                </div>
                @if (!fermes().has(e.code)) { <div class="acc-corps"><ng-container *ngTemplateOutlet="liste; context: { s: e }" /></div> }
              </div>
            }
          </div>
          @if (modifiable()) {
            <button class="btn sm ajout-sous" (click)="ajouterSection(o.code)"><nx-icon name="plus" [size]="14" /> {{ 'Ajouter une sous-section (accordéon)' | t }}</button>
          }

          @if (modifiable() && ailleurs().length) {
            <div class="vers">
              <span class="muted small">{{ 'Déposer un champ dans un autre onglet :' | t }}</span>
              @for (s of ailleurs(); track s.code) {
                <div cdkDropList [id]="'nx-vers-' + s.code" class="cible" [cdkDropListData]="s.code" [cdkDropListSortingDisabled]="true"
                  [cdkDropListEnterPredicate]="accepteChampExistant" (cdkDropListDropped)="envoyerVers($event)">{{ s.parentCode ? '— ' : '' }}{{ s.labelFr || s.code }}</div>
              }
            </div>
          }
        } @else {
          <div class="empty small">{{ 'Aucune section : ajoutez un onglet pour commencer.' | t }}</div>
        }
      </div>

      <!-- Propriétés -->
      <aside class="card pad proprietes">
        @if (champChoisi(); as f) {
          <nx-proprietes-champ [champ]="f" [reglage]="reglages()[f.code] ?? {}" [sections]="sections()" [champs]="fields()" [requestTypes]="v().requestTypes"
            [drl]="v().rules?.drl ?? ''" [colonnes]="ihm().colonnes" [lectureSeule]="!modifiable()"
            (modifier)="modifierChamp($event)" (supprimer)="supprimerChamp(f.code)" (regles)="regles.emit()" />
        } @else if (sectionChoisie(); as s) {
          <div class="titre-col">{{ (s.parentCode ? 'Sous-section' : 'Onglet') | t }} <span class="code-tag">{{ s.code }}</span></div>
          <fieldset class="corps" [disabled]="!modifiable()">
            <div class="field"><label for="ps-fr">{{ 'Libellé (FR)' | t }}</label><input id="ps-fr" class="input" [value]="s.labelFr" (input)="poserSection(s.code, { labelFr: val($event) })" /></div>
            <div class="field"><label for="ps-en">{{ 'Libellé (EN)' | t }}</label><input id="ps-en" class="input" [value]="s.labelEn ?? ''" (input)="poserSection(s.code, { labelEn: val($event) || null })" /></div>
            <div class="field"><label for="ps-p">{{ 'Section parente' | t }}</label>
              <select id="ps-p" class="input" (change)="deplacerSection(s.code, val($event) || null)" [disabled]="enfants(s.code).length > 0">
                <option value="">{{ 'Aucune (onglet)' | t }}</option>
                @for (h of hauts(); track h.code) { @if (h.code !== s.code) { <option [value]="h.code" [selected]="s.parentCode === h.code">{{ h.labelFr }} ({{ h.code }})</option> } }
              </select>
              @if (enfants(s.code).length) { <span class="muted small">{{ 'Un onglet qui contient des sous-sections reste au premier niveau (un seul niveau).' | t }}</span> }
            </div>
            <label class="check"><input type="checkbox" [checked]="s.repeating" (change)="poserSection(s.code, { repeating: coche($event) })" /> {{ 'Groupe répétitif (tableau de lignes)' | t }}</label>
            @if (s.repeating) {
              <div class="field"><label for="ps-min">{{ 'Lignes min.' | t }}</label><input id="ps-min" class="input" type="number" min="0" [value]="s.minRows" (input)="poserSection(s.code, { minRows: +val($event) || 0 })" /></div>
            }
            <div class="field"><label for="ps-code">{{ 'Code' | t }}</label><input id="ps-code" class="input mono" [value]="s.code" (change)="renommerSection(s.code, val($event))" />
              @if (erreurSection()) { <span class="small err-txt">{{ erreurSection()! | t }}</span> }</div>
            <button class="btn sm danger" (click)="supprimerSection(s.code)"><nx-icon name="trash" [size]="14" /> {{ 'Supprimer la section' | t }}</button>
          </fieldset>
        } @else {
          <div class="titre-col">{{ 'Propriétés' | t }}</div>
          <p class="muted small">{{ 'Sélectionnez un champ, un onglet ou un accordéon pour régler ses propriétés.' | t }}</p>
        }
      </aside>
    </div>

    <!-- Enregistrement -->
    @if (modifiable()) {
      <div class="card pad row barre">
        @if (problemes().length) { <div class="alert err small grow">@for (p of problemes(); track p.m) { <div>{{ p.m | t: p.l }}</div> }</div> }
        @else { <span class="muted small grow">{{ modifie() ? ('Modifications non enregistrées.' | t) : ('Aucune modification.' | t) }}</span> }
        <button class="btn sm ghost" (click)="reinitialiser()" [disabled]="!modifie()">{{ 'Annuler les modifications' | t }}</button>
        <input class="input motif" [value]="motif()" (input)="motif.set(val($event))" [placeholder]="'Motif de la modification' | t" />
        <button class="btn primary" (click)="enregistrerTout()" [disabled]="!modifie() || problemes().length > 0"><nx-icon name="save" /> {{ 'Enregistrer' | t }}</button>
      </div>
    }

    <!-- Aperçu e-Services -->
    <div class="card pad stack">
      <div class="between">
        <div class="card-title"><nx-icon name="eye" /><h2>{{ 'Aperçu e-Services' | t }}</h2></div>
        <div class="row">
          <label class="small" for="ap-type">{{ 'Type de demande' | t }}</label>
          <select id="ap-type" class="input sm" (change)="typeApercu.set(val($event) || null)">
            <option value="">{{ 'Tous' | t }}</option>
            @for (t of v().requestTypes; track t.code) { <option [value]="t.code">{{ t.labelFr | lib: t.labelEn }}</option> }
          </select>
          <label class="small" for="ap-nat">{{ 'Nature' | t }}</label>
          <select id="ap-nat" class="input sm" (change)="natureApercu.set(val($event))">
            @for (n of [['INITIAL', 'Initiale'], ['RENEWAL', 'Renouvellement'], ['AMENDMENT', 'Amendement']]; track n[0]) { <option [value]="n[0]">{{ n[1] | t }}</option> }
          </select>
        </div>
      </div>
      <p class="muted small">{{ 'Rendu du formulaire du demandeur avec le gabarit d\\'IHM enregistré ; la saisie d\\'essai sert à vérifier l\\'affichage conditionnel et n\\'est pas conservée.' | t }}</p>
      <div class="cadre">
        <nx-apercu-formulaire [sections]="sections()" [fields]="fields()" [ihm]="ihm()" [reglages]="reglages()" [typeDemande]="typeApercu()"
          [sectionsMasquees]="['INSTRUCTION']" [champsMasques]="champsMasques()" [selection]="selection()?.kind === 'champ' ? selection()!.code : null"
          (choisi)="choisirDepuisApercu($event)" />
      </div>
    </div>

    <!-- Liste des champs d'une section -->
    <ng-template #liste let-s="s">
      <div cdkDropList [id]="'nx-champs-' + s.code" class="champs" [cdkDropListData]="s.code" [cdkDropListEnterPredicate]="accepteChamp"
        [cdkDropListDisabled]="!modifiable()" (cdkDropListDropped)="deposerChamp($event)">
        @for (f of champsDe(s.code); track f.code) {
          <div class="carte" cdkDrag [cdkDragData]="{ kind: 'champ', code: f.code }" [class.on]="estChoisie('champ', f.code)" (click)="choisir('champ', f.code)">
            @if (modifiable()) { <span class="poignee" cdkDragHandle [title]="'Glisser pour déplacer' | t"><nx-icon name="selector" [size]="14" /></span> }
            <div class="grow min0">
              <ng-container *ngTemplateOutlet="libelle; context: { kind: 'champ', s: f }" />
              <div class="meta">
                <span class="code-tag">{{ f.code }}</span>
                <span class="chip">{{ typeLibelle(f.dataType) | t }}</span>
                @if (f.mandatory === 'OUI') { <span class="chip req">{{ 'Obligatoire' | t }}</span> }
                @if (f.mandatory === 'CONDITIONNEL') { <span class="chip">{{ 'Conditionnel' | t }}</span> }
                @if (reglages()[f.code]?.largeur; as l) { <span class="chip">{{ l >= 4 ? ('Ligne' | t) : l + ' col.' }}</span> }
                @if (reglages()[f.code]?.visibleSi?.champ) { <span class="chip" [title]="'Affichage conditionnel' | t"><nx-icon name="eye" [size]="12" /> {{ reglages()[f.code]!.visibleSi!.champ }}</span> }
                @if (typesDe(f); as t) { <span class="chip" [title]="'Types de demande concernés' | t"><nx-icon name="tag" [size]="12" /> {{ t }}</span> }
                @if (f.biePath) { <span class="chip">BIE</span> }
              </div>
            </div>
            @if (modifiable()) {
              <button type="button" class="btn icon ghost sm" (click)="dupliquer(f.code); $event.stopPropagation()" [attr.aria-label]="'Dupliquer' | t" [title]="'Dupliquer' | t"><nx-icon name="layers" [size]="14" /></button>
              <button type="button" class="btn icon ghost sm danger" (click)="supprimerChamp(f.code); $event.stopPropagation()" [attr.aria-label]="'Supprimer' | t" [title]="'Supprimer' | t"><nx-icon name="trash" [size]="14" /></button>
            }
            <div *cdkDragPlaceholder class="carte fantome"></div>
          </div>
        } @empty { <div class="vide small">{{ 'Déposez des composants ici.' | t }}</div> }
      </div>
      @if (modifiable()) { <button class="btn sm ghost ajout" (click)="ajouterChamp(s.code, 'TEXT')"><nx-icon name="plus" [size]="14" /> {{ 'Champ' | t }}</button> }
    </ng-template>

    <!-- Libellé FR / EN, renommable en place (double-clic ou crayon) -->
    <ng-template #libelle let-kind="kind" let-s="s">
      @if (edition()?.kind === kind && edition()?.code === s.code) {
        <span class="edition" (click)="$event.stopPropagation()">
          <input class="input sm" [value]="edition()!.fr" (input)="editer('fr', val($event))" (keydown.enter)="validerEdition()" (keydown.escape)="edition.set(null)" placeholder="FR" />
          <input class="input sm" [value]="edition()!.en" (input)="editer('en', val($event))" (keydown.enter)="validerEdition()" (keydown.escape)="edition.set(null)" placeholder="EN" />
          <button type="button" class="btn icon sm primary" (click)="validerEdition()" [attr.aria-label]="'Valider' | t"><nx-icon name="check" [size]="14" /></button>
          <button type="button" class="btn icon sm ghost" (click)="edition.set(null)" [attr.aria-label]="'Annuler' | t"><nx-icon name="x" [size]="14" /></button>
        </span>
      } @else {
        <span class="lib" (dblclick)="commencerEdition(kind, s)">
          <b>{{ s.labelFr || s.code }}</b>@if (kind === 'champ' && s.mandatory === 'OUI') { <span class="req"> *</span> }
          @if (s.labelEn) { <span class="muted small"> · {{ s.labelEn }}</span> }
          @if (modifiable()) { <button type="button" class="btn icon ghost xs" (click)="commencerEdition(kind, s); $event.stopPropagation()" [attr.aria-label]="'Renommer' | t" [title]="'Renommer (FR / EN)' | t"><nx-icon name="edit" [size]="12" /></button> }
        </span>
      }
    </ng-template>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 16px; }
    .constructeur { display: grid; grid-template-columns: 200px minmax(0, 1fr) 340px; gap: 12px; align-items: start; }
    @media (max-width: 1200px) { .constructeur { grid-template-columns: 180px minmax(0, 1fr); } .proprietes { grid-column: 1 / -1; } }
    @media (max-width: 760px) { .constructeur { grid-template-columns: 1fr; } }
    .palette, .proprietes { display: flex; flex-direction: column; gap: 8px; position: sticky; top: 8px; max-height: calc(100vh - 16px); overflow-y: auto; }
    .titre-col { font-weight: 700; font-size: 13px; display: flex; align-items: center; gap: 6px; }
    .composants { display: flex; flex-direction: column; gap: 4px; }
    .composant { display: flex; align-items: center; gap: 8px; padding: 7px 9px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface);
      font-size: 12.5px; cursor: grab; user-select: none; }
    .composant:hover { border-color: var(--accent); color: var(--accent); }
    .sep { border-top: 1px solid var(--line); margin: 4px 0; }
    .canevas { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
    .onglets { display: flex; gap: 2px; border-bottom: 1px solid var(--line); overflow-x: auto; align-items: flex-end; }
    .onglet { display: inline-flex; align-items: center; gap: 6px; padding: 8px 12px; border-bottom: 2px solid transparent; margin-bottom: -1px;
      font-size: 13px; font-weight: 600; color: var(--muted); cursor: pointer; white-space: nowrap; background: var(--surface); user-select: none; }
    .onglet.on { color: var(--accent); border-bottom-color: var(--accent); }
    .onglet .nb { font-size: 11px; padding: 0 6px; border-radius: 999px; background: var(--chip); color: var(--muted); }
    .entete { display: flex; align-items: center; gap: 8px; padding: 6px 8px; border-radius: 8px; cursor: pointer; }
    .entete.on, .acc-tete.on { background: var(--accent-soft); }
    .champs { display: flex; flex-direction: column; gap: 6px; min-height: 44px; padding: 6px; border: 1px dashed var(--line); border-radius: 10px; }
    .champs.cdk-drop-list-receiving, .champs.cdk-drop-list-dragging { border-color: var(--accent); background: var(--accent-soft); }
    .carte { display: flex; align-items: center; gap: 8px; padding: 7px 9px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); cursor: pointer; }
    .carte:hover { border-color: var(--accent); }
    .carte.on { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
    .min0 { min-width: 0; }
    .meta { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 3px; }
    .meta .chip { font-size: 11px; padding: 0 7px; }
    .chip.req { color: var(--danger); }
    .req { color: var(--danger); }
    .poignee { cursor: grab; color: var(--muted); display: inline-flex; }
    .fantome { opacity: .5; border-style: dashed; min-height: 40px; background: var(--accent-soft); }
    .vide { padding: 10px; text-align: center; color: var(--muted); }
    .ajout { align-self: flex-start; }
    .sous { display: flex; flex-direction: column; gap: 8px; }
    .accordeon { border-radius: 10px; background: var(--surface-2); border: 1px solid var(--line); }
    .acc-tete { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 10px; cursor: pointer; }
    .acc-tete .chevron { transform: rotate(90deg); transition: transform .15s; }
    .accordeon.ouvert .acc-tete .chevron { transform: rotate(-90deg); }
    .acc-corps { display: flex; flex-direction: column; gap: 6px; padding: 0 10px 10px; }
    .ajout-sous { align-self: flex-start; }
    .vers { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; padding-top: 8px; border-top: 1px solid var(--line); }
    .cible { padding: 4px 10px; border: 1px dashed var(--line); border-radius: 999px; font-size: 12px; color: var(--muted); min-width: 60px; text-align: center; }
    .cible.cdk-drop-list-receiving { border-color: var(--accent); color: var(--accent); }
    .cible.cdk-drop-list-dragging { border-color: var(--accent); background: var(--accent-soft); color: var(--accent); }
    .cible .carte { display: none; }
    .lib { display: inline-flex; align-items: baseline; gap: 2px; flex-wrap: wrap; min-width: 0; }
    .edition { display: inline-flex; gap: 4px; align-items: center; flex-wrap: wrap; }
    .edition .input { height: 28px; width: 170px; font-size: 12.5px; }
    .btn.xs { padding: 0 4px; height: 20px; min-width: 20px; }
    .corps { display: flex; flex-direction: column; gap: 10px; border: 0; padding: 0; margin: 0; }
    .corps .input { height: 34px; font-size: 13px; }
    .barre { position: sticky; bottom: 8px; z-index: 3; }
    .motif { max-width: 300px; }
    .input.sm { height: 30px; font-size: 12.5px; width: auto; }
    .cadre { border: 1px solid var(--line); border-radius: 12px; padding: 14px; background: var(--surface); }
    .err-txt { color: var(--danger); }
    .cdk-drag-preview { box-shadow: var(--shadow); }
    .cdk-drag-animating, .champs.cdk-drop-list-dragging .carte:not(.cdk-drag-placeholder) { transition: transform 200ms cubic-bezier(0, 0, .2, 1); }
  `],
})
export class Constructeur {
  private readonly i18n = inject(I18n);
  readonly v = input.required<VersionComplete>();
  readonly modifiable = input(false);
  readonly enregistrer = output<Enregistrement>();
  /** Demande d'ouverture de l'onglet Règles DRL. */
  readonly regles = output<void>();
  protected readonly types = TYPES_CHAMP;
  protected readonly sections = signal<Section[]>([]);
  protected readonly fields = signal<Field[]>([]);
  protected readonly reglages = signal<Record<string, ReglageChamp>>({});
  protected readonly ongletActif = signal<string | null>(null);
  protected readonly selection = signal<Selection>(null);
  protected readonly edition = signal<Edition>(null);
  protected readonly fermes = signal<ReadonlySet<string>>(new Set());
  protected readonly motif = signal('');
  protected readonly erreurSection = signal<string | null>(null);
  protected readonly typeApercu = signal<string | null>(null);
  protected readonly natureApercu = signal('INITIAL');
  private readonly apercu = viewChild(ApercuFormulaire);
  private readonly origine = signal('');

  protected readonly ihm = computed(() => gabarit(this.v().version.settings['ihm']));
  protected readonly dispositionOnglets = computed(() => this.ihm().disposition === 'ONGLETS' && this.ihm().sousSections === 'ACCORDEONS');
  private readonly empreinte = computed(() => JSON.stringify({ s: this.sections(), f: this.fields(), r: this.reglages() }));
  readonly modifie = computed(() => this.empreinte() !== this.origine());

  /** Sections de premier niveau (onglets) ; une sous-section dont le parent n'existe plus remonte au premier niveau. */
  protected readonly hauts = computed(() => this.triees().filter(s => estHaut(s, this.sections())));
  private readonly triees = computed(() => [...this.sections()].sort((a, b) => a.sortOrder - b.sortOrder));
  /** Sections hors de l'onglet affiché : cibles de dépôt pour changer un champ d'onglet. */
  protected readonly ailleurs = computed(() => {
    const o = this.ongletActif();
    return this.ordreSections().filter(s => s.code !== o && s.parentCode !== o);
  });
  protected readonly champChoisi = computed(() => {
    const s = this.selection();
    return s?.kind === 'champ' ? this.fields().find(f => f.code === s.code) ?? null : null;
  });
  protected readonly sectionChoisie = computed(() => {
    const s = this.selection();
    return s?.kind === 'section' ? this.section(s.code) : null;
  });
  /** Champs masqués dans l'aperçu selon la nature simulée (année de renouvellement, demande précédente). */
  protected readonly champsMasques = computed(() => {
    const st = this.v().version.settings as Record<string, unknown>;
    const n = this.natureApercu(), m: string[] = [];
    if (n !== 'RENEWAL' && typeof st['renewalYearField'] === 'string') m.push(st['renewalYearField']);
    if (n === 'INITIAL' && typeof st['previousField'] === 'string') m.push(st['previousField']);
    return m;
  });

  /** Contrôles avant enregistrement : codes uniques et renseignés, libellés français, champs rattachés à une section. */
  protected readonly problemes = computed(() => {
    const p: { m: string; l: string }[] = [];
    const doublons = (l: string[]) => [...new Set(l.filter((c, i) => l.indexOf(c) !== i))];
    const ds = doublons(this.sections().map(s => s.code)), df = doublons(this.fields().map(f => f.code));
    if (ds.length) p.push({ m: 'Codes de section en double : {0}.', l: ds.join(', ') });
    if (df.length) p.push({ m: 'Codes de champ en double : {0}.', l: df.join(', ') });
    const codes = new Set(this.sections().map(s => s.code));
    const orphelins = this.fields().filter(f => !codes.has(f.sectionCode)).map(f => f.code);
    if (orphelins.length) p.push({ m: 'Champs sans section : {0}.', l: orphelins.join(', ') });
    const sansLibelle = [...this.sections(), ...this.fields()].filter(x => !x.labelFr?.trim()).map(x => x.code);
    if (sansLibelle.length) p.push({ m: 'Libellé français manquant : {0}.', l: sansLibelle.join(', ') });
    return p;
  });

  constructor() {
    effect(() => { this.v(); untracked(() => this.reinitialiser()); });
    // L'aperçu suit la sélection (onglet, étape ou accordéon de la section choisie)
    effect(() => {
      const s = this.selection();
      const a = this.apercu();
      if (!s || !a) return;
      const section = s.kind === 'section' ? s.code : this.fields().find(f => f.code === s.code)?.sectionCode;
      if (section) untracked(() => a.montrer(section));
    });
  }

  /** Recharge l'état depuis la version (après enregistrement, ou pour abandonner les modifications). */
  reinitialiser(): void {
    const v = this.v();
    const sections = v.sections.map(s => ({ ...s, parentCode: s.parentCode ?? null }));
    const fields = v.fields.map(f => ({ ...f }));
    const r = this.renumeroter(sections, fields);
    this.sections.set(r.sections);
    this.fields.set(r.fields);
    this.reglages.set(reglagesChamps(v.version.settings['ihm']));
    this.origine.set(this.empreinte());
    this.edition.set(null);
    if (!this.ongletActif() || !r.sections.some(s => s.code === this.ongletActif())) this.ongletActif.set(this.hauts()[0]?.code ?? null);
    const sel = this.selection();
    if (sel && !(sel.kind === 'champ' ? r.fields : r.sections).some(x => x.code === sel.code)) this.selection.set(null);
  }

  // ------------------------------------------------------------------ lecture
  protected section(code: string | null): Section | null {
    return code ? this.sections().find(s => s.code === code) ?? null : null;
  }

  protected enfants(code: string): Section[] {
    return this.triees().filter(s => s.parentCode === code);
  }

  protected champsDe(section: string): Field[] {
    return this.fields().filter(f => f.sectionCode === section).sort((a, b) => a.sortOrder - b.sortOrder);
  }

  /** Nombre de champs d'un onglet, sous-sections comprises. */
  protected total(code: string): number {
    return this.champsDe(code).length + this.enfants(code).reduce((n, e) => n + this.champsDe(e.code).length, 0);
  }

  /** Ordre d'affichage : chaque onglet suivi de ses sous-sections. */
  private ordreSections(sections = this.sections()): Section[] {
    const triees = [...sections].sort((a, b) => a.sortOrder - b.sortOrder);
    return triees.filter(s => estHaut(s, sections)).flatMap(h => [h, ...triees.filter(s => s.parentCode === h.code)]);
  }

  protected typeLibelle(t: string): string {
    return TYPES_CHAMP.find(x => x.code === t)?.libelle ?? t;
  }

  protected typesDe(f: Field): string | null {
    const m = /^\s*typeDemande\s+in\s*\(([^)]*)\)\s*$/i.exec(f.conditionText ?? '');
    return m ? m[1].replace(/'/g, '').trim() : null;
  }

  protected estChoisie(kind: 'champ' | 'section', code: string): boolean {
    const s = this.selection();
    return s?.kind === kind && s.code === code;
  }

  protected val(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected coche(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }

  // ------------------------------------------------------------------ sélection
  protected choisir(kind: 'champ' | 'section', code: string): void {
    this.selection.set({ kind, code });
    this.erreurSection.set(null);
  }

  protected ouvrirOnglet(code: string): void {
    this.ongletActif.set(code);
    this.choisir('section', code);
  }

  protected choisirDepuisApercu(code: string): void {
    const f = this.fields().find(x => x.code === code);
    if (!f) return;
    const s = this.section(f.sectionCode);
    this.ongletActif.set(s?.parentCode && this.section(s.parentCode) ? s.parentCode : f.sectionCode);
    if (s?.parentCode) this.ouvrirAccordeon(s.code);
    this.choisir('champ', code);
  }

  protected basculer(code: string): void {
    const s = new Set(this.fermes());
    if (s.has(code)) s.delete(code); else s.add(code);
    this.fermes.set(s);
  }

  private ouvrirAccordeon(code: string): void {
    if (!this.fermes().has(code)) return;
    const s = new Set(this.fermes());
    s.delete(code);
    this.fermes.set(s);
  }

  // ------------------------------------------------------------------ glisser-déposer
  protected readonly jamais = () => false;
  protected readonly accepteOnglet = (d: CdkDrag<Glisse>) => d.data?.kind === 'onglet';
  protected readonly accepteSous = (d: CdkDrag<Glisse>) => d.data?.kind === 'sous';
  protected readonly accepteChamp = (d: CdkDrag<Glisse>) => d.data?.kind === 'champ' || d.data?.kind === 'palette';
  protected readonly accepteChampExistant = (d: CdkDrag<Glisse>) => d.data?.kind === 'champ';

  protected deposerOnglet(e: CdkDragDrop<unknown>): void {
    const codes = this.hauts().map(s => s.code);
    moveItemInArray(codes, e.previousIndex, e.currentIndex);
    this.ordonner(codes);
  }

  protected deposerSous(e: CdkDragDrop<string>): void {
    const parent = e.container.data;
    const codes = this.enfants(parent).map(s => s.code);
    moveItemInArray(codes, e.previousIndex, e.currentIndex);
    this.ordonner(codes);
  }

  /** Nouvel ordre relatif d'un groupe de sections (onglets ou sous-sections d'un même onglet). */
  private ordonner(codes: string[]): void {
    const rang = new Map(codes.map((c, i) => [c, i]));
    const base = Math.min(...this.sections().filter(s => rang.has(s.code)).map(s => s.sortOrder));
    this.appliquer(this.sections().map(s => (rang.has(s.code) ? { ...s, sortOrder: base + rang.get(s.code)! / 100 } : s)), this.fields());
  }

  protected deposerChamp(e: CdkDragDrop<string, unknown, Glisse>): void {
    const cible = e.container.data;
    const d = e.item.data;
    if (d.kind === 'palette') { this.ajouterChamp(cible, d.type, e.currentIndex); return; }
    if (d.kind !== 'champ') return;
    this.placer(d.code, cible, e.currentIndex);
  }

  protected envoyerVers(e: CdkDragDrop<string, unknown, Glisse>): void {
    const d = e.item.data;
    if (d.kind !== 'champ') return;
    this.placer(d.code, e.container.data, Number.MAX_SAFE_INTEGER);
    this.choisir('champ', d.code);
  }

  /** Place un champ dans une section à un rang donné (déplacement dans la section ou vers une autre). */
  private placer(code: string, section: string, rang: number): void {
    const champ = this.fields().find(f => f.code === code);
    if (!champ) return;
    const liste = this.champsDe(section).filter(f => f.code !== code);
    liste.splice(Math.min(rang, liste.length), 0, { ...champ, sectionCode: section });
    const ordre = new Map(liste.map((f, i) => [f.code, (i + 1) * 10]));
    this.appliquer(this.sections(), this.fields().map(f => (ordre.has(f.code) ? { ...f, sectionCode: section, sortOrder: ordre.get(f.code)! } : f)));
  }

  // ------------------------------------------------------------------ ajout, suppression, renommage
  private codeLibre(prefixe: string, pris: string[]): string {
    let i = 1;
    while (pris.includes(prefixe + i)) i++;
    return prefixe + i;
  }

  protected ajouterChamp(section: string, type: string, rang = Number.MAX_SAFE_INTEGER): void {
    const code = this.codeLibre('nouveauChamp', this.fields().map(f => f.code));
    const libelle = TYPES_CHAMP.find(t => t.code === type)?.libelle ?? 'Champ';
    const f: Field = { code, sectionCode: section, labelFr: libelle, labelEn: null, dataType: type, maxLength: null, pattern: null, mandatory: 'NON',
      conditionText: null, refdataTable: null, codeValues: null, untdedRef: null, wcoRef: null, cclDen: null, cclUid: null, biePath: null, searchable: false,
      prefill: null, amendable: true, helpFr: null, helpEn: null, sortOrder: 0 };
    this.fields.set([...this.fields(), f]);
    this.placer(code, section, rang);
    const s = this.section(section);
    if (s?.parentCode) this.ouvrirAccordeon(section);
    this.choisir('champ', code);
  }

  protected dupliquer(code: string): void {
    const f = this.fields().find(x => x.code === code);
    if (!f) return;
    const copie = { ...structuredClone(f), code: this.codeLibre(code + '_', this.fields().map(x => x.code)), labelFr: f.labelFr + ' (copie)' };
    const rang = this.champsDe(f.sectionCode).findIndex(x => x.code === code) + 1;
    this.fields.set([...this.fields(), copie]);
    if (this.reglages()[code]) this.reglages.set({ ...this.reglages(), [copie.code]: structuredClone(this.reglages()[code]) });
    this.placer(copie.code, f.sectionCode, rang);
    this.choisir('champ', copie.code);
  }

  protected supprimerChamp(code: string): void {
    const f = this.fields().find(x => x.code === code);
    if (!f || !confirm(this.i18n.t('Supprimer le champ « {0} » ({1}) ?', f.labelFr, code))) return;
    const r = { ...this.reglages() };
    delete r[code];
    this.reglages.set(r);
    this.appliquer(this.sections(), this.fields().filter(x => x.code !== code));
    if (this.estChoisie('champ', code)) this.selection.set(null);
  }

  protected ajouterSection(parent: string | null): void {
    const code = this.codeLibre(parent ? 'SOUS_SECTION_' : 'ONGLET_', this.sections().map(s => s.code));
    const freres = parent ? this.enfants(parent) : this.hauts();
    const apres = freres.length ? freres[freres.length - 1] : parent ? this.section(parent) : null;
    // Ordre provisoire : juste après le dernier frère (et ses sous-sections pour un onglet)
    const fin = apres ? Math.max(apres.sortOrder, ...this.enfants(apres.code).map(e => e.sortOrder)) + 0.5 : Number.MAX_SAFE_INTEGER;
    const s: Section = { code, labelFr: parent ? 'Nouvelle sous-section' : 'Nouvel onglet', labelEn: null, repeating: false, minRows: 0, sortOrder: fin, parentCode: parent };
    this.appliquer([...this.sections(), s], this.fields());
    if (!parent) this.ongletActif.set(code);
    this.choisir('section', code);
    this.commencerEdition('section', s);
  }

  protected supprimerSection(code: string): void {
    const s = this.section(code);
    if (!s) return;
    if (this.enfants(code).length) { alert(this.i18n.t('Supprimez ou déplacez d\'abord les sous-sections de cet onglet.')); return; }
    const n = this.champsDe(code).length;
    if (!confirm(n ? this.i18n.t('Supprimer la section « {0} » et ses {1} champ(s) ?', s.labelFr, n) : this.i18n.t('Supprimer la section « {0} » ?', s.labelFr))) return;
    const retires = new Set(this.champsDe(code).map(f => f.code));
    const r = { ...this.reglages() };
    retires.forEach(c => delete r[c]);
    this.reglages.set(r);
    this.appliquer(this.sections().filter(x => x.code !== code), this.fields().filter(f => !retires.has(f.code)));
    this.selection.set(null);
    if (this.ongletActif() === code) this.ongletActif.set(s.parentCode ?? this.hauts()[0]?.code ?? null);
  }

  protected poserSection(code: string, p: Partial<Section>): void {
    this.sections.set(this.sections().map(s => (s.code === code ? { ...s, ...p } : s)));
  }

  /** Change le parent d'une section : onglet ↔ sous-section, ou sous-section d'un autre onglet (placée en dernier). */
  protected deplacerSection(code: string, parent: string | null): void {
    const fin = Math.max(0, ...this.sections().map(s => s.sortOrder)) + 1;
    this.appliquer(this.sections().map(s => (s.code === code ? { ...s, parentCode: parent, sortOrder: fin } : s)), this.fields());
    this.ongletActif.set(parent ?? code);
  }

  protected renommerSection(ancien: string, code: string): void {
    const c = code.trim();
    if (!c || c === ancien) { this.erreurSection.set(c ? null : 'Le code est obligatoire.'); return; }
    if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(c)) { this.erreurSection.set('Code : lettres, chiffres et « _ », en commençant par une lettre.'); return; }
    if (this.sections().some(s => s.code === c)) { this.erreurSection.set('Ce code est déjà utilisé par une autre section.'); return; }
    this.erreurSection.set(null);
    this.sections.set(this.sections().map(s => (s.code === ancien ? { ...s, code: c } : s.parentCode === ancien ? { ...s, parentCode: c } : s)));
    this.fields.set(this.fields().map(f => (f.sectionCode === ancien ? { ...f, sectionCode: c } : f)));
    if (this.ongletActif() === ancien) this.ongletActif.set(c);
    this.selection.set({ kind: 'section', code: c });
  }

  /** Modification venue du panneau de propriétés ; un renommage reporte le code dans les réglages et les conditions d'affichage. */
  protected modifierChamp(m: ModificationChamp): void {
    const { ancienCode, champ } = m;
    const r = { ...this.reglages() };
    delete r[ancienCode];
    if (Object.keys(m.reglage).length) r[champ.code] = m.reglage;
    if (champ.code !== ancienCode)
      for (const [k, x] of Object.entries(r)) if (x.visibleSi?.champ === ancienCode) r[k] = { ...x, visibleSi: { ...x.visibleSi, champ: champ.code } };
    this.reglages.set(r);
    const deplace = this.fields().find(f => f.code === ancienCode)?.sectionCode !== champ.sectionCode;
    this.fields.set(this.fields().map(f => (f.code === ancienCode ? champ : f)));
    if (deplace) this.placer(champ.code, champ.sectionCode, Number.MAX_SAFE_INTEGER);
    if (ancienCode !== champ.code) this.selection.set({ kind: 'champ', code: champ.code });
  }

  protected commencerEdition(kind: 'champ' | 'section', s: { code: string; labelFr: string; labelEn: string | null }): void {
    if (!this.modifiable()) return;
    this.edition.set({ kind, code: s.code, fr: s.labelFr ?? '', en: s.labelEn ?? '' });
  }

  protected editer(langue: 'fr' | 'en', v: string): void {
    const e = this.edition();
    if (e) this.edition.set({ ...e, [langue]: v });
  }

  protected validerEdition(): void {
    const e = this.edition();
    if (!e) return;
    const p = { labelFr: e.fr.trim() || e.code, labelEn: e.en.trim() || null };
    if (e.kind === 'section') this.poserSection(e.code, p);
    else this.fields.set(this.fields().map(f => (f.code === e.code ? { ...f, ...p } : f)));
    this.edition.set(null);
  }

  // ------------------------------------------------------------------ ordre et enregistrement
  private appliquer(sections: Section[], fields: Field[]): void {
    const r = this.renumeroter(sections, fields);
    this.sections.set(r.sections);
    this.fields.set(r.fields);
  }

  /** Ordre d'affichage recalculé de 10 en 10 : sections (onglet puis ses sous-sections), puis champs section par section. */
  private renumeroter(sections: Section[], fields: Field[]): { sections: Section[]; fields: Field[] } {
    const ordre = this.ordreSections(sections).map((s, i) => ({ ...s, sortOrder: (i + 1) * 10 }));
    const rang = new Map(ordre.map((s, i) => [s.code, i]));
    const champs = fields.map((f, i) => ({ f, i }))
      .sort((a, b) => (rang.get(a.f.sectionCode) ?? 1e9) - (rang.get(b.f.sectionCode) ?? 1e9) || a.f.sortOrder - b.f.sortOrder || a.i - b.i)
      .map(({ f }, i) => ({ ...f, sortOrder: (i + 1) * 10 }));
    return { sections: ordre, fields: champs };
  }

  protected enregistrerTout(): void {
    if (this.problemes().length) return;
    const codes = new Set(this.fields().map(f => f.code));
    const champs = Object.fromEntries(Object.entries(this.reglages()).filter(([c, r]) => codes.has(c) && Object.keys(r).length));
    this.enregistrer.emit({ partie: 'form', corps: { sections: this.sections(), fields: this.fields(), champs },
      motif: this.motif() || 'Constructeur de formulaire' });
    this.motif.set('');
  }
}
