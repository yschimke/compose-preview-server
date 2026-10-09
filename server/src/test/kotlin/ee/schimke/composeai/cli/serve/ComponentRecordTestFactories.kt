package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.BuilderOrphan
import ee.schimke.composeai.discovery.BuilderPolicy
import ee.schimke.composeai.discovery.COMPONENT_RECORD_SCHEMA_VERSION
import ee.schimke.composeai.discovery.ComponentBinding
import ee.schimke.composeai.discovery.ComponentCode
import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ComponentSlot
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.TargetParameter

// Since contracts 3.24.0 the component-record types (now in `component-catalog-protocol`) are built
// through Builders and their constructors are internal. These keep the tests' named-argument calls
// as they were: a visible function wins over the inaccessible constructor of the same name.

internal fun ComponentRecordFile(
  schemaVersion: Int = COMPONENT_RECORD_SCHEMA_VERSION,
  module: String,
  variant: String,
  components: List<ComponentRecord>,
  builderOrphans: List<BuilderOrphan> = emptyList(),
): ComponentRecordFile =
  ComponentRecordFile.Builder(module, variant, components)
    .apply {
      this.schemaVersion = schemaVersion
      this.builderOrphans = builderOrphans
    }
    .build()

internal fun ComponentRecord(
  canonicalId: String,
  componentIds: List<String> = emptyList(),
  symbol: ComponentSymbol,
  parameters: List<TargetParameter> = emptyList(),
  slots: List<ComponentSlot> = emptyList(),
  bindings: List<ComponentBinding> = emptyList(),
  code: ComponentCode? = null,
  signatureKnown: Boolean = false,
  callableFromAnotherFile: Boolean = true,
  hasTypeParameters: Boolean = false,
  hasContextReceivers: Boolean = false,
  overloadsCollided: Boolean = false,
  builder: BuilderPolicy? = null,
  requiredOptIns: List<String> = emptyList(),
  androidxOptIns: List<String> = emptyList(),
): ComponentRecord =
  ComponentRecord.Builder(canonicalId, symbol)
    .apply {
      this.componentIds = componentIds
      this.parameters = parameters
      this.slots = slots
      this.bindings = bindings
      this.code = code
      this.signatureKnown = signatureKnown
      this.callableFromAnotherFile = callableFromAnotherFile
      this.hasTypeParameters = hasTypeParameters
      this.hasContextReceivers = hasContextReceivers
      this.overloadsCollided = overloadsCollided
      this.builder = builder
      this.requiredOptIns = requiredOptIns
      this.androidxOptIns = androidxOptIns
    }
    .build()

internal fun TargetParameter(
  name: String,
  type: String,
  typeFqn: String? = null,
  hasDefault: Boolean = false,
  composableSlot: Boolean = false,
  composableSlotReceiver: String? = null,
  nullable: Boolean = false,
  noArgConstructible: Boolean = false,
  noArgFactory: String? = null,
  scopeDslReceiver: String? = null,
  lambdaReturnTypeFqn: String? = null,
): TargetParameter =
  TargetParameter.Builder(name, type)
    .apply {
      this.typeFqn = typeFqn
      this.hasDefault = hasDefault
      this.composableSlot = composableSlot
      this.composableSlotReceiver = composableSlotReceiver
      this.nullable = nullable
      this.noArgConstructible = noArgConstructible
      this.noArgFactory = noArgFactory
      this.scopeDslReceiver = scopeDslReceiver
      this.lambdaReturnTypeFqn = lambdaReturnTypeFqn
    }
    .build()

internal fun ComponentCode(
  call: String? = null,
  imports: List<String> = emptyList(),
  refusedReason: String? = null,
  requiredOptIns: List<String> = emptyList(),
  androidxOptIns: List<String> = emptyList(),
): ComponentCode =
  ComponentCode.Builder()
    .apply {
      this.call = call
      this.imports = imports
      this.refusedReason = refusedReason
      this.requiredOptIns = requiredOptIns
      this.androidxOptIns = androidxOptIns
    }
    .build()

internal fun ComponentSlot(
  name: String,
  required: Boolean,
  receiverScope: String? = null,
): ComponentSlot =
  ComponentSlot.Builder(name, required).apply { this.receiverScope = receiverScope }.build()

internal fun ComponentSymbol(
  jvmOwner: String,
  callable: String,
  name: String,
  origin: ComponentOrigin,
  jvmName: String? = null,
  descriptor: String? = null,
  sourceFile: String? = null,
  docs: String = "unavailable",
  receiver: String? = null,
): ComponentSymbol =
  ComponentSymbol.Builder(jvmOwner, callable, name, origin)
    .apply {
      this.jvmName = jvmName
      this.descriptor = descriptor
      this.sourceFile = sourceFile
      this.docs = docs
      this.receiver = receiver
    }
    .build()
