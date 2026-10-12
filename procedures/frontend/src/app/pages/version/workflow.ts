import { AfterViewInit, Component, ElementRef, OnDestroy, effect, inject, input, output, signal, viewChild } from '@angular/core';
import Modeler from 'bpmn-js/lib/Modeler';
import NavigatedViewer from 'bpmn-js/lib/NavigatedViewer';
import camunda from 'camunda-bpmn-moddle/resources/camunda.json';
import { Api, VersionComplete } from '../../core/api';
import { AuthService } from '../../core/auth.service';
import { I18N } from '../../core/i18n';
import { Icon } from '../../shared/icon';
import { Enregistrement } from './general';

interface Element { id: string; type: string; businessObject: Record<string, unknown> & { $attrs?: Record<string, string>; name?: string; extensionElements?: { values?: { $type: string; name?: string; stringValue?: string; values?: { name: string; value: string }[]; event?: string; delegateExpression?: string; fields?: { name: string; stringValue?: string }[] }[] } }; }

/**
 * Workflow BPMN 2.0 de la procédure, édité avec bpmn-js (le moteur de Camunda Modeler) et les extensions « camunda: »
 * exécutées par Operaton dans les e-Services. Le diagramme peut aussi être exporté vers Camunda Modeler puis réimporté.
 */
@Component({
  selector: 'nx-onglet-workflow',
  imports: [Icon, ...I18N],
  template: `
    <div class="card pad stack">
      <div class="between">
        <div class="card-title"><nx-icon name="workflow" /><h2>{{ 'Workflow BPMN' | t }}</h2>
          @if (v().process; as p) { <span class="code-tag">{{ p.processKey }}</span> }</div>
        <div class="row">
          <button class="btn sm" (click)="ajuster()"><nx-icon name="grid" [size]="15" /> {{ 'Ajuster' | t }}</button>
          @if (v().process) { <a class="btn sm" [href]="auth.lien(api.urlBpmn(v().version.id))"><nx-icon name="download" [size]="15" /> {{ 'Exporter .bpmn' | t }}</a> }
          @if (modifiable()) {
            <label class="btn sm"><nx-icon name="upload" [size]="15" /> {{ 'Importer .bpmn' | t }}<input type="file" accept=".bpmn,.xml" hidden (change)="importer($any($event.target))" /></label>
            <button class="btn primary sm" (click)="enregistrerXml()" [disabled]="!modifie()"><nx-icon name="save" [size]="15" /> {{ 'Enregistrer' | t }}</button>
          }
        </div>
      </div>
      <p class="muted small">{{ 'Délégués du Runtime (delegateExpression) : regles (champ point), paiement (message), cotation, message (code), delivrance (livrable, messages, statut), statut (code). Message BPMN corrélé : PAIEMENT_CONFIRME. Décisions des tâches humaines : propriété « decisions ».' | t }}</p>
      @if (erreur()) { <div class="alert err">{{ erreur() }}</div> }
      @if (!v().process) { <div class="alert warn">{{ 'Aucun workflow : lancez le Single Window Agent (onglet Agents IA) pour le générer.' | t }}</div> }
      <div class="toile" #toile></div>
      @if (selection(); as s) {
        <div class="proprietes">
          <div class="between"><b>{{ s.type.replace('bpmn:', '') }} · <span class="mono">{{ s.id }}</span></b></div>
          <div class="form-grid">
            <div class="field"><label for="nom">{{ 'Nom' | t }}</label><input id="nom" class="input" [disabled]="!modifiable()" [value]="s.businessObject.name ?? ''" (change)="propriete('name', $any($event.target).value)" /></div>
            @if (s.type === 'bpmn:ServiceTask') {
              <div class="field"><label for="de">camunda:delegateExpression</label><input id="de" class="input mono" [disabled]="!modifiable()" [value]="attr(s, 'delegateExpression')" (change)="propriete('camunda:delegateExpression', $any($event.target).value)" /></div>
            }
            @if (s.type === 'bpmn:UserTask') {
              <div class="field"><label for="as">camunda:assignee</label><input id="as" class="input mono" [disabled]="!modifiable()" [value]="attr(s, 'assignee')" (change)="propriete('camunda:assignee', $any($event.target).value)" /></div>
              <div class="field"><label for="fk">camunda:formKey</label><input id="fk" class="input mono" [disabled]="!modifiable()" [value]="attr(s, 'formKey')" (change)="propriete('camunda:formKey', $any($event.target).value)" /></div>
            }
          </div>
          @if (extensions(s).length) { <div class="small"><b>{{ 'Extensions' | t }}</b> : @for (x of extensions(s); track $index) { <span class="code-tag">{{ x }}</span> } </div> }
        </div>
      }
    </div>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 16px; }
    .toile { height: 560px; border: 1px solid var(--line); border-radius: 12px; background: #fff; overflow: hidden; }
    .proprietes { border: 1px solid var(--line); border-radius: 12px; padding: 12px; display: flex; flex-direction: column; gap: 8px; }
    .proprietes .code-tag { margin: 2px; display: inline-block; }
  `],
})
export class OngletWorkflow implements AfterViewInit, OnDestroy {
  protected readonly api = inject(Api);
  protected readonly auth = inject(AuthService);
  readonly v = input.required<VersionComplete>();
  readonly modifiable = input(false);
  readonly enregistrer = output<Enregistrement>();
  private readonly toile = viewChild.required<ElementRef<HTMLDivElement>>('toile');
  protected readonly modifie = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly selection = signal<Element | null>(null);
  private modeleur: Modeler | NavigatedViewer | null = null;
  private dernierXml = '';

  constructor() {
    effect(() => {
      const xml = this.v().process?.bpmnXml ?? '';
      if (this.modeleur && xml && xml !== this.dernierXml) this.charger(xml);
    });
  }

  ngAfterViewInit(): void {
    const options = { container: this.toile().nativeElement, moddleExtensions: { camunda } };
    this.modeleur = this.modifiable() ? new Modeler(options) : new NavigatedViewer(options);
    const bus = this.modeleur.get('eventBus') as { on: (e: string, f: (x: { element?: Element; newSelection?: Element[] }) => void) => void };
    bus.on('commandStack.changed', () => this.modifie.set(true));
    bus.on('selection.changed', e => this.selection.set(e.newSelection?.[0] ?? null));
    bus.on('element.click', e => { if (!this.modifiable() && e.element) this.selection.set(e.element); });
    const xml = this.v().process?.bpmnXml;
    if (xml) this.charger(xml);
  }

  ngOnDestroy(): void {
    this.modeleur?.destroy();
  }

  private charger(xml: string): void {
    this.dernierXml = xml;
    this.modeleur!.importXML(xml).then(() => { this.ajuster(); this.modifie.set(false); this.erreur.set(null); })
      .catch((e: Error) => this.erreur.set('BPMN illisible : ' + e.message));
  }

  protected ajuster(): void {
    (this.modeleur?.get('canvas') as { zoom: (z: string) => void } | undefined)?.zoom('fit-viewport');
  }

  protected attr(s: Element, nom: string): string {
    const bo = s.businessObject as Record<string, unknown> & { $attrs?: Record<string, string> };
    return String(bo[nom] ?? bo['$attrs']?.['camunda:' + nom] ?? '');
  }

  protected extensions(s: Element): string[] {
    return (s.businessObject.extensionElements?.values ?? []).flatMap(x => {
      if (x.$type === 'camunda:Field') return [`${x.name}=${x.stringValue ?? ''}`];
      if (x.$type === 'camunda:Properties') return (x.values ?? []).map(p => `${p.name}=${p.value}`);
      if (x.$type === 'camunda:ExecutionListener') return [`${x.event}:${x.delegateExpression ?? ''}${(x.fields ?? []).map(f => ` ${f.name}=${f.stringValue}`).join('')}`];
      return [x.$type];
    });
  }

  protected propriete(nom: string, valeur: string): void {
    const s = this.selection();
    if (!s || !(this.modeleur instanceof Modeler)) return;
    (this.modeleur.get('modeling') as { updateProperties: (e: unknown, p: Record<string, unknown>) => void }).updateProperties(s, { [nom]: valeur || undefined });
  }

  protected importer(champ: HTMLInputElement): void {
    const f = champ.files?.[0];
    if (!f) return;
    f.text().then(xml => { this.charger(xml); this.modifie.set(true); champ.value = ''; });
  }

  protected async enregistrerXml(): Promise<void> {
    const { xml } = await this.modeleur!.saveXML({ format: true });
    const cle = /<bpmn:process id="([^"]+)"/.exec(xml ?? '')?.[1] ?? this.v().process?.processKey ?? this.v().procedure.code.toLowerCase();
    this.dernierXml = xml ?? '';
    this.enregistrer.emit({ partie: 'process', corps: { processKey: cle, bpmnXml: xml }, motif: 'Modification du workflow BPMN' });
    this.modifie.set(false);
  }
}
