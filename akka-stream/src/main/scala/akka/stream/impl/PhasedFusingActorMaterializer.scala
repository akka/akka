/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import java.util
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean

import akka.NotUsed
import akka.actor.{ ActorContext, ActorRef, ActorRefFactory, ActorSystem, Cancellable, Deploy, ExtendedActorSystem, PoisonPill }
import akka.annotation.{ DoNotInherit, InternalApi }
import akka.dispatch.Dispatchers
import akka.event.{ Logging, LoggingAdapter }
import akka.stream.Attributes.InputBuffer
import akka.stream._
import akka.stream.impl.StreamLayout.AtomicModule
import akka.stream.impl.fusing.ActorGraphInterpreter.{ ActorOutputBoundary, BatchingActorInputBoundary }
import akka.stream.impl.fusing.GraphInterpreter.Connection
import akka.stream.impl.fusing._
import akka.stream.impl.io.{ TLSActor, TlsModule }
import akka.stream.stage.{ GraphStageLogic, InHandler, OutHandler }
import org.reactivestreams.{ Processor, Publisher, Subscriber }

import scala.collection.immutable.Map
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContextExecutor
import scala.annotation.tailrec
import akka.util.OptionVal

/**
 * INTERNAL API
 */
@InternalApi private[akka] object PhasedFusingActorMaterializer {

  val Debug = false

  val DefaultPhase: Phase[Any] = new Phase[Any] {
    override def apply(settings: ActorMaterializerSettings, materializer: PhasedFusingActorMaterializer, islandName: String): PhaseIsland[Any] =
      new GraphStageIsland(settings, materializer, islandName, subflowFuser = OptionVal.None).asInstanceOf[PhaseIsland[Any]]
  }

  val DefaultPhases: Map[IslandTag, Phase[Any]] = Map[IslandTag, Phase[Any]](
    SinkModuleIslandTag → new Phase[Any] {
      override def apply(settings: ActorMaterializerSettings, materializer: PhasedFusingActorMaterializer,
                         islandName: String): PhaseIsland[Any] =
        new SinkModulePhase(materializer, islandName).asInstanceOf[PhaseIsland[Any]]
    },
    SourceModuleIslandTag → new Phase[Any] {
      override def apply(settings: ActorMaterializerSettings, materializer: PhasedFusingActorMaterializer,
                         islandName: String): PhaseIsland[Any] =
        new SourceModulePhase(materializer, islandName).asInstanceOf[PhaseIsland[Any]]
    },
    ProcessorModuleIslandTag → new Phase[Any] {
      override def apply(settings: ActorMaterializerSettings, materializer: PhasedFusingActorMaterializer,
                         islandName: String): PhaseIsland[Any] =
        new ProcessorModulePhase(materializer, islandName).asInstanceOf[PhaseIsland[Any]]
    },
    TlsModuleIslandTag → new Phase[Any] {
      def apply(settings: ActorMaterializerSettings, materializer: PhasedFusingActorMaterializer, islandName: String): PhaseIsland[Any] =
        new TlsModulePhase(settings, materializer, islandName).asInstanceOf[PhaseIsland[Any]]
    },
    GraphStageTag → DefaultPhase)

  @InternalApi private[akka] def apply(settings: ActorMaterializerSettings)(implicit context: ActorRefFactory): ActorMaterializer = {
    val haveShutDown = new AtomicBoolean(false)
    val system = actorSystemOf(context)
    val materializerSettings = ActorMaterializerSettings(system)

    val streamSupervisor = context.actorOf(StreamSupervisor.props(materializerSettings, haveShutDown)
      .withDispatcher(materializerSettings.dispatcher), StreamSupervisor.nextName())

    PhasedFusingActorMaterializer(
      system,
      materializerSettings,
      system.dispatchers,
      streamSupervisor,
      haveShutDown,
      FlowNames(system).name.copy("flow"))
  }

  private def actorSystemOf(context: ActorRefFactory): ActorSystem = {
    val system = context match {
      case s: ExtendedActorSystem ⇒ s
      case c: ActorContext        ⇒ c.system
      case null                   ⇒ throw new IllegalArgumentException("ActorRefFactory context must be defined")
      case _ ⇒
        throw new IllegalArgumentException(s"ActorRefFactory context must be an ActorSystem or ActorContext, got [${context.getClass.getName}]")
    }
    system
  }

}

private final case class SegmentInfo(
  globalislandOffset: Int, // The island to which the segment belongs
  length:             Int, // How many slots are contained by the segment
  globalBaseOffset:   Int, // The global slot where this segment starts
  relativeBaseOffset: Int, // the local offset of the slot where this segment starts
  phase:              PhaseIsland[Any]) {

  override def toString: String =
    s"""
       | Segment
       |  globalislandOffset = $globalislandOffset
       |  length             = $length
       |  globalBaseOffset   = $globalBaseOffset
       |  relativeBaseOffset = $relativeBaseOffset
       |  phase              = $phase
       """.stripMargin
}

private final case class ForwardWire(
  islandGlobalOffset: Int,
  from:               OutPort,
  toGlobalOffset:     Int,
  outStage:           Any,
  phase:              PhaseIsland[Any]) {

  override def toString: String = s"ForwardWire(islandId = $islandGlobalOffset, from = $from, toGlobal = $toGlobalOffset, phase = $phase)"
}

private final case class SavedIslandData(islandGlobalOffset: Int, lastVisitedOffset: Int, skippedSlots: Int, phase: PhaseIsland[Any])

@InternalApi private[akka] class IslandTracking(
  val phases:       Map[IslandTag, Phase[Any]],
  val settings:     ActorMaterializerSettings,
  defaultPhase:     Phase[Any],
  val materializer: PhasedFusingActorMaterializer,
  islandNamePrefix: String) {

  import PhasedFusingActorMaterializer.Debug

  private var islandNameCounter = 0
  private def nextIslandName(): String = {
    val s = islandNamePrefix + islandNameCounter
    islandNameCounter += 1
    s
  }

  private var currentGlobalOffset = 0
  private var currentSegmentGlobalOffset = 0
  private var currentIslandGlobalOffset = 0
  // The number of slots that belong to segments of other islands encountered so far, from the
  // beginning of the island
  private var currentIslandSkippetSlots = 0

  private var segments: java.util.ArrayList[SegmentInfo] = null
  private var activePhases: java.util.ArrayList[PhaseIsland[Any]] = null
  private var forwardWires: java.util.ArrayList[ForwardWire] = null
  private var islandStateStack: java.util.ArrayList[SavedIslandData] = null

  private var currentPhase: PhaseIsland[Any] = defaultPhase.apply(settings, materializer, nextIslandName())

  @InternalApi private[akka] def getCurrentPhase: PhaseIsland[Any] = currentPhase
  @InternalApi private[akka] def getCurrentOffset: Int = currentGlobalOffset

  private def completeSegment(): Unit = {
    val length = currentGlobalOffset - currentSegmentGlobalOffset

    if (activePhases eq null) {
      activePhases = new util.ArrayList[PhaseIsland[Any]](8)
      islandStateStack = new java.util.ArrayList[SavedIslandData](4)
    }

    if (length > 0) {
      // We just finished a segment by entering an island.
      val previousSegment = SegmentInfo(
        globalislandOffset = currentIslandGlobalOffset,
        length = currentGlobalOffset - currentSegmentGlobalOffset,
        globalBaseOffset = currentSegmentGlobalOffset,
        relativeBaseOffset = currentSegmentGlobalOffset - currentIslandGlobalOffset - currentIslandSkippetSlots,
        currentPhase)

      // Segment tracking is by demand, we only allocate this list if it is used.
      // If there are no islands, then there is no need to track segments
      if (segments eq null) segments = new java.util.ArrayList[SegmentInfo](8)
      segments.add(previousSegment)
      if (Debug) println(s"Completed segment $previousSegment")
    } else {
      if (Debug) println(s"Skipped zero length segment")
    }
  }

  @InternalApi private[akka] def enterIsland(tag: IslandTag, attributes: Attributes): Unit = {
    completeSegment()
    val previousPhase = currentPhase
    val previousIslandOffset = currentIslandGlobalOffset
    islandStateStack.add(SavedIslandData(previousIslandOffset, currentGlobalOffset, currentIslandSkippetSlots, previousPhase))

    val effectiveSettings = materializer.effectiveSettings(attributes)
    currentPhase = phases(tag)(effectiveSettings, materializer, nextIslandName())
    activePhases.add(currentPhase)

    // Resolve the phase to be used to materialize this island
    currentIslandGlobalOffset = currentGlobalOffset

    // The base offset of this segment is the current global offset
    currentSegmentGlobalOffset = currentGlobalOffset
    currentIslandSkippetSlots = 0
    if (Debug) println(s"Entering island starting at offset = $currentIslandGlobalOffset phase = $currentPhase")
  }

  @InternalApi private[akka] def exitIsland(): Unit = {
    val parentIsland = islandStateStack.remove(islandStateStack.size() - 1)
    completeSegment()

    // We start a new segment
    currentSegmentGlobalOffset = currentGlobalOffset

    // We restore data for the island
    currentIslandGlobalOffset = parentIsland.islandGlobalOffset
    currentPhase = parentIsland.phase
    currentIslandSkippetSlots = parentIsland.skippedSlots + (currentGlobalOffset - parentIsland.lastVisitedOffset)

    if (Debug) println(s"Exited to island starting at offset = $currentIslandGlobalOffset phase = $currentPhase")
  }

  @InternalApi private[akka] def wireIn(in: InPort, logic: Any): Unit = {
    // The slot for this InPort always belong to the current segment, so resolving its local
    // offset/slot is simple
    val localInSlot = currentGlobalOffset - currentIslandGlobalOffset - currentIslandSkippetSlots
    if (Debug) println(s"  wiring port $in inOffs absolute = $currentGlobalOffset local = $localInSlot")

    // Assign the logic belonging to the current port to its calculated local slot in the island
    currentPhase.assignPort(in, localInSlot, logic)

    // Check if there was any forward wiring that has this offset/slot as its target
    // First try to find such wiring
    var forwardWire: ForwardWire = null
    if ((forwardWires ne null) && !forwardWires.isEmpty) {
      var i = 0
      while (i < forwardWires.size()) {
        forwardWire = forwardWires.get(i)
        if (forwardWire.toGlobalOffset == currentGlobalOffset) {
          if (Debug) println(s"    there is a forward wire to this slot $forwardWire")
          forwardWires.remove(i)
          i = Int.MaxValue // Exit the loop
        } else {
          forwardWire = null // Didn't found it yet
          i += 1
        }
      }
    }

    // If there is a forward wiring we need to resolve it
    if (forwardWire ne null) {
      // The forward wire ends up in the same island
      if (forwardWire.phase eq currentPhase) {
        if (Debug) println(s"    in-island forward wiring from port ${forwardWire.from} wired to local slot = $localInSlot")
        forwardWire.phase.assignPort(forwardWire.from, localInSlot, forwardWire.outStage)
      } else {
        if (Debug) println(s"    cross island forward wiring from port ${forwardWire.from} wired to local slot = $localInSlot")
        val publisher = forwardWire.phase.createPublisher(forwardWire.from, forwardWire.outStage)
        currentPhase.takePublisher(localInSlot, publisher)
      }
    }

    currentGlobalOffset += 1
  }

  @InternalApi private[akka] def wireOut(out: OutPort, absoluteOffset: Int, logic: Any): Unit = {
    if (Debug) println(s"  wiring $out to absolute = $absoluteOffset")

    //          <-------- backwards, visited stuff
    //                   ------------> forward, not yet visited
    // ---------------- .. (we are here)
    // First check if we are wiring backwards. This is important since we can only do resolution for backward wires.
    // In other cases we need to record the forward wire and resolve it later once its target inSlot has been visited.
    if (absoluteOffset < currentGlobalOffset) {
      if (Debug) println("    backward wiring")

      if (absoluteOffset >= currentSegmentGlobalOffset) {
        // Wiring is in the same segment, no complex lookup needed
        val localInSlot = absoluteOffset - currentIslandGlobalOffset - currentIslandSkippetSlots
        if (Debug) println(s"    in-segment wiring to local ($absoluteOffset - $currentIslandGlobalOffset - $currentIslandSkippetSlots) = $localInSlot")
        currentPhase.assignPort(out, localInSlot, logic)
      } else {
        // Wiring is cross-segment, but we don't know if it is cross-island or not yet
        // We must find the segment to which this slot belongs first
        var i = segments.size() - 1
        var targetSegment: SegmentInfo = segments.get(i)
        // Skip segments that have a higher offset than our slot, until we find the containing segment
        while (i > 0 && targetSegment.globalBaseOffset > absoluteOffset) {
          i -= 1
          targetSegment = segments.get(i)
        }

        // Independently of the target island the local slot for the target island is calculated the same:
        //   - Calculate the relative offset of the local slot in the segment
        //   - calculate the island relative offset by adding the island relative base offset of the segment
        val distanceFromSegmentStart = absoluteOffset - targetSegment.globalBaseOffset
        val localInSlot = distanceFromSegmentStart + targetSegment.relativeBaseOffset

        if (targetSegment.phase eq currentPhase) {
          if (Debug) println(s"    cross-segment, in-island wiring to local slot $localInSlot")
          currentPhase.assignPort(out, localInSlot, logic)
        } else {
          if (Debug) println(s"    cross-island wiring to local slot $localInSlot in target island")
          val publisher = currentPhase.createPublisher(out, logic)
          targetSegment.phase.takePublisher(localInSlot, publisher)
        }
      }
    } else {
      // We need to record the forward wiring so we can resolve it later

      // The forward wire tracking data structure is only allocated when needed. Many graphs have no forward wires
      // even though it might have islands.
      if (forwardWires eq null) {
        forwardWires = new java.util.ArrayList[ForwardWire](8)
      }

      val forwardWire = ForwardWire(
        islandGlobalOffset = currentIslandGlobalOffset,
        from = out,
        toGlobalOffset = absoluteOffset,
        logic,
        currentPhase)

      if (Debug) println(s"    wiring is forward, recording $forwardWire")
      forwardWires.add(forwardWire)
    }

  }

  @InternalApi private[akka] def allNestedIslandsReady(): Unit = {
    if (activePhases ne null) {
      var i = 0
      while (i < activePhases.size()) {
        activePhases.get(i).onIslandReady()
        i += 1
      }
    }
  }

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] case class PhasedFusingActorMaterializer(
  system:                ActorSystem,
  override val settings: ActorMaterializerSettings,
  dispatchers:           Dispatchers,
  supervisor:            ActorRef,
  haveShutDown:          AtomicBoolean,
  flowNames:             SeqActorName) extends ExtendedActorMaterializer {
  import PhasedFusingActorMaterializer._

  private val _logger = Logging.getLogger(system, this)
  override def logger: LoggingAdapter = _logger

  if (settings.fuzzingMode && !system.settings.config.hasPath("akka.stream.secret-test-fuzzing-warning-disable")) {
    _logger.warning("Fuzzing mode is enabled on this system. If you see this warning on your production system then " +
      "set akka.stream.materializer.debug.fuzzing-mode to off.")
  }
  if (!settings.autoFusing) {
    _logger.warning("Deprecated setting auto-fusing set to false. Since Akka 2.5.0 it does not have any effect " +
      "and streams are always fused.")
  }

  override def shutdown(): Unit =
    if (haveShutDown.compareAndSet(false, true)) supervisor ! PoisonPill

  override def isShutdown: Boolean = haveShutDown.get()

  override def withNamePrefix(name: String): PhasedFusingActorMaterializer = this.copy(flowNames = flowNames.copy(name))

  private[this] def createFlowName(): String = flowNames.next()

  /** INTERNAL API */
  private[akka] val defaultInitialAttributes = {
    val a = Attributes(
      Attributes.InputBuffer(settings.initialInputBufferSize, settings.maxInputBufferSize) ::
        ActorAttributes.SupervisionStrategy(settings.supervisionDecider) ::
        Nil)
    if (settings.dispatcher == Deploy.NoDispatcherGiven) a
    else a and ActorAttributes.dispatcher(settings.dispatcher)
  }

  override def effectiveSettings(opAttr: Attributes): ActorMaterializerSettings = {
    import ActorAttributes._
    import Attributes._
    @tailrec def applyAttributes(attrs: List[Attribute], s: ActorMaterializerSettings,
                                 inputBufferDone: Boolean, dispatcherDone: Boolean, supervisorDone: Boolean): ActorMaterializerSettings = {
      attrs match {
        case InputBuffer(initial, max) :: tail if !inputBufferDone ⇒
          applyAttributes(tail, s.withInputBuffer(initial, max), inputBufferDone = true, dispatcherDone, supervisorDone)
        case Dispatcher(dispatcher) :: tail if !dispatcherDone ⇒
          applyAttributes(tail, s.withDispatcher(dispatcher), inputBufferDone, dispatcherDone = true, supervisorDone)
        case SupervisionStrategy(decider) :: tail if !supervisorDone ⇒
          applyAttributes(tail, s.withSupervisionStrategy(decider), inputBufferDone, dispatcherDone, supervisorDone = true)
        case _ if inputBufferDone || dispatcherDone || supervisorDone ⇒ s
        case _ :: tail ⇒
          applyAttributes(tail, s, inputBufferDone, dispatcherDone, supervisorDone)
        case Nil ⇒
          s
      }
    }
    applyAttributes(opAttr.attributeList, settings, false, false, false)
  }

  override lazy val executionContext: ExecutionContextExecutor = dispatchers.lookup(settings.dispatcher match {
    case Deploy.NoDispatcherGiven ⇒ Dispatchers.DefaultDispatcherId
    case other                    ⇒ other
  })

  override def schedulePeriodically(initialDelay: FiniteDuration, interval: FiniteDuration, task: Runnable): Cancellable =
    system.scheduler.schedule(initialDelay, interval, task)(executionContext)

  override def scheduleOnce(delay: FiniteDuration, task: Runnable): Cancellable =
    system.scheduler.scheduleOnce(delay, task)(executionContext)

  override def materialize[Mat](_runnableGraph: Graph[ClosedShape, Mat]): Mat =
    materialize(_runnableGraph, defaultInitialAttributes)

  override def materialize[Mat](
    _runnableGraph:    Graph[ClosedShape, Mat],
    initialAttributes: Attributes): Mat =
    materialize(
      _runnableGraph,
      initialAttributes,
      PhasedFusingActorMaterializer.DefaultPhase,
      PhasedFusingActorMaterializer.DefaultPhases)

  override def materialize[Mat](
    graph:             Graph[ClosedShape, Mat],
    initialAttributes: Attributes,
    defaultPhase:      Phase[Any],
    phases:            Map[IslandTag, Phase[Any]]): Mat = {
    val islandTracking = new IslandTracking(phases, settings, defaultPhase, this, islandNamePrefix = createFlowName() + "-")

    var current: Traversal = graph.traversalBuilder.traversal

    val attributesStack = new java.util.ArrayDeque[Attributes](8)
    attributesStack.addLast(initialAttributes and graph.traversalBuilder.attributes)

    val traversalStack = new java.util.ArrayDeque[Traversal](16)
    traversalStack.addLast(current)

    val matValueStack = new java.util.ArrayDeque[Any](8)

    if (Debug) {
      println(s"--- Materializing layout:")
      TraversalBuilder.printTraversal(current)
      println(s"--- Start materialization")
    }

    // Due to how Concat works, we need a stack. This probably can be optimized for the most common cases.
    while (!traversalStack.isEmpty) {
      current = traversalStack.removeLast()

      while (current ne EmptyTraversal) {
        var nextStep: Traversal = EmptyTraversal
        current match {
          case MaterializeAtomic(mod, outToSlot) ⇒
            if (Debug) println(s"materializing module: $mod")
            val matAndStage = islandTracking.getCurrentPhase.materializeAtomic(mod, attributesStack.getLast)
            val logic = matAndStage._1
            val matValue = matAndStage._2
            if (Debug) println(s"  materialized value is $matValue")
            matValueStack.addLast(matValue)

            val stageGlobalOffset = islandTracking.getCurrentOffset

            wireInlets(islandTracking, mod, logic)
            wireOutlets(islandTracking, mod, logic, stageGlobalOffset, outToSlot)

            if (Debug) println(s"PUSH: $matValue => $matValueStack")

          case Concat(first, next) ⇒
            if (next ne EmptyTraversal) traversalStack.add(next)
            nextStep = first
          case Pop ⇒
            val popped = matValueStack.removeLast()
            if (Debug) println(s"POP: $popped => $matValueStack")
          case PushNotUsed ⇒
            matValueStack.addLast(NotUsed)
            if (Debug) println(s"PUSH: NotUsed => $matValueStack")
          case transform: Transform ⇒
            val prev = matValueStack.removeLast()
            val result = transform(prev)
            matValueStack.addLast(result)
            if (Debug) println(s"TRFM: $matValueStack")
          case compose: Compose ⇒
            val second = matValueStack.removeLast()
            val first = matValueStack.removeLast()
            val result = compose(first, second)
            matValueStack.addLast(result)
            if (Debug) println(s"COMP: $matValueStack")
          case PushAttributes(attr) ⇒
            attributesStack.addLast(attributesStack.getLast and attr)
            if (Debug) println(s"ATTR PUSH: $attr")
          case PopAttributes ⇒
            attributesStack.removeLast()
            if (Debug) println(s"ATTR POP")
          case EnterIsland(tag) ⇒
            islandTracking.enterIsland(tag, attributesStack.getLast)
          case ExitIsland ⇒
            islandTracking.exitIsland()
          case _ ⇒
        }
        current = nextStep
      }
    }

    islandTracking.getCurrentPhase.onIslandReady()
    islandTracking.allNestedIslandsReady()

    if (Debug) println("--- Finished materialization")
    matValueStack.peekLast().asInstanceOf[Mat]
  }

  private def wireInlets(islandTracking: IslandTracking, mod: StreamLayout.AtomicModule[Shape, Any], logic: Any): Unit = {
    val inlets = mod.shape.inlets
    if (inlets.nonEmpty) {
      if (Shape.hasOnePort(inlets)) {
        // optimization, duplication to avoid iterator allocation
        islandTracking.wireIn(inlets.head, logic)
      } else {
        val ins = inlets.iterator
        while (ins.hasNext) {
          val in = ins.next()
          islandTracking.wireIn(in, logic)
        }
      }
    }
  }

  private def wireOutlets(islandTracking: IslandTracking, mod: StreamLayout.AtomicModule[Shape, Any], logic: Any,
                          stageGlobalOffset: Int, outToSlot: Array[Int]): Unit = {
    val outlets = mod.shape.outlets
    if (outlets.nonEmpty) {
      if (Shape.hasOnePort(outlets)) {
        // optimization, duplication to avoid iterator allocation
        val out = outlets.head
        val absoluteTargetSlot = stageGlobalOffset + outToSlot(out.id)
        if (Debug) println(s"  wiring offset: ${outToSlot.mkString("[", ",", "]")}")
        islandTracking.wireOut(out, absoluteTargetSlot, logic)
      } else {
        val outs = outlets.iterator
        while (outs.hasNext) {
          val out = outs.next()
          val absoluteTargetSlot = stageGlobalOffset + outToSlot(out.id)
          if (Debug) println(s"  wiring offset: ${outToSlot.mkString("[", ",", "]")}")
          islandTracking.wireOut(out, absoluteTargetSlot, logic)
        }
      }
    }
  }

  override def makeLogger(logSource: Class[_]): LoggingAdapter =
    Logging(system, logSource)

}

/**
 * INTERNAL API
 */
@DoNotInherit private[akka] trait IslandTag

/**
 * INTERNAL API
 */
@DoNotInherit private[akka] trait Phase[M] {
  def apply(
    effectiveSettings: ActorMaterializerSettings,
    materializer:      PhasedFusingActorMaterializer,
    islandName:        String): PhaseIsland[M]
}

/**
 * INTERNAL API
 */
@DoNotInherit private[akka] trait PhaseIsland[M] {

  def name: String

  def materializeAtomic(mod: AtomicModule[Shape, Any], attributes: Attributes): (M, Any)

  def assignPort(in: InPort, slot: Int, logic: M): Unit

  def assignPort(out: OutPort, slot: Int, logic: M): Unit

  def createPublisher(out: OutPort, logic: M): Publisher[Any]

  def takePublisher(slot: Int, publisher: Publisher[Any]): Unit

  def onIslandReady(): Unit

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object GraphStageTag extends IslandTag

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class GraphStageIsland(
  effectiveSettings: ActorMaterializerSettings,
  materializer:      PhasedFusingActorMaterializer,
  islandName:        String,
  subflowFuser:      OptionVal[GraphInterpreterShell ⇒ ActorRef]) extends PhaseIsland[GraphStageLogic] {
  // TODO: remove these
  private val logicArrayType = Array.empty[GraphStageLogic]
  private[this] val logics = new ArrayList[GraphStageLogic](16)

  private var connections = new Array[Connection](16)
  private var maxConnections = 0
  private var outConnections: List[Connection] = Nil
  private var fullIslandName: OptionVal[String] = OptionVal.None

  val shell = new GraphInterpreterShell(
    connections = null,
    logics = null,
    effectiveSettings,
    materializer)

  override def name: String = "Fusing GraphStages phase"

  override def materializeAtomic(mod: AtomicModule[Shape, Any], attributes: Attributes): (GraphStageLogic, Any) = {
    // TODO: bail on unknown types
    val stageModule = mod.asInstanceOf[GraphStageModule[Shape, Any]]
    val stage = stageModule.stage
    val matAndLogic = stage.createLogicAndMaterializedValue(attributes)
    val logic = matAndLogic._1
    logic.originalStage = OptionVal.Some(stage)
    logic.attributes = attributes
    logics.add(logic)
    logic.stageId = logics.size() - 1
    fullIslandName match {
      case OptionVal.Some(_) ⇒ // already set
      case OptionVal.None    ⇒ fullIslandName = OptionVal.Some(islandName + "-" + logic.attributes.nameOrDefault())
    }
    matAndLogic
  }

  def conn(slot: Int): Connection = {
    maxConnections = math.max(slot, maxConnections)
    if (maxConnections >= connections.length) {
      connections = java.util.Arrays.copyOf(connections, connections.length * 2)
    }
    val c = connections(slot)
    if (c ne null) c
    else {
      val c2 = new Connection(0, null, null, null, null)
      connections(slot) = c2
      c2
    }
  }

  def outConn(): Connection = {
    val connection = new Connection(0, null, null, null, null)
    outConnections ::= connection
    connection
  }

  override def assignPort(in: InPort, slot: Int, logic: GraphStageLogic): Unit = {
    val connection = conn(slot)
    connection.inOwner = logic
    connection.id = slot
    connection.inHandler = logic.handlers(in.id).asInstanceOf[InHandler]
    if (connection.inHandler eq null) failOnMissingHandler(logic)
    logic.portToConn(in.id) = connection
  }

  override def assignPort(out: OutPort, slot: Int, logic: GraphStageLogic): Unit = {
    val connection = conn(slot)
    connection.outOwner = logic
    connection.id = slot
    connection.outHandler = logic.handlers(logic.inCount + out.id).asInstanceOf[OutHandler]
    if (connection.outHandler eq null) failOnMissingHandler(logic)
    logic.portToConn(logic.inCount + out.id) = connection
  }

  override def createPublisher(out: OutPort, logic: GraphStageLogic): Publisher[Any] = {
    val boundary = new ActorOutputBoundary(shell, out.toString)
    logics.add(boundary)
    boundary.stageId = logics.size() - 1

    val connection = outConn()
    boundary.portToConn(boundary.in.id) = connection
    connection.inHandler = boundary.handlers(0).asInstanceOf[InHandler]
    connection.inOwner = boundary

    connection.outOwner = logic
    connection.id = -1 // Will be filled later
    connection.outHandler = logic.handlers(logic.inCount + out.id).asInstanceOf[OutHandler]
    if (connection.outHandler eq null) failOnMissingHandler(logic)
    logic.portToConn(logic.inCount + out.id) = connection

    boundary.publisher
  }

  override def takePublisher(slot: Int, publisher: Publisher[Any]): Unit = {
    val connection = conn(slot)
    // TODO: proper input port debug string (currently prints the stage)
    val bufferSize = connection.inOwner.attributes.get[InputBuffer].get.max
    val boundary =
      new BatchingActorInputBoundary(bufferSize, shell, publisher, connection.inOwner.toString)
    logics.add(boundary)
    boundary.stageId = logics.size() - 1

    boundary.portToConn(boundary.out.id + boundary.inCount) = connection
    connection.outHandler = boundary.handlers(0).asInstanceOf[OutHandler]
    connection.outOwner = boundary
  }

  override def onIslandReady(): Unit = {

    val totalConnections = maxConnections + outConnections.size + 1
    val finalConnections = java.util.Arrays.copyOf(connections, totalConnections)

    var i = maxConnections + 1
    var outConns = outConnections
    while (i < totalConnections) {
      val conn = outConns.head
      outConns = outConns.tail
      if (conn.inHandler eq null) failOnMissingHandler(conn.inOwner)
      else if (conn.outHandler eq null) failOnMissingHandler(conn.outOwner)
      finalConnections(i) = conn
      conn.id = i
      i += 1
    }

    shell.connections = finalConnections
    shell.logics = logics.toArray(logicArrayType)

    subflowFuser match {
      case OptionVal.Some(fuseIntoExistingInterperter) ⇒
        fuseIntoExistingInterperter(shell)

      case _ ⇒
        val props = ActorGraphInterpreter.props(shell)
          .withDispatcher(effectiveSettings.dispatcher)
        val actorName = fullIslandName match {
          case OptionVal.Some(n) ⇒ n
          case OptionVal.None    ⇒ islandName
        }
        materializer.actorOf(props, actorName)
    }
  }

  private def failOnMissingHandler(logic: GraphStageLogic): Unit = {
    val missingHandlerIdx = logic.handlers.indexWhere(_.asInstanceOf[AnyRef] eq null)
    val isIn = missingHandlerIdx < logic.inCount
    val portLabel = logic.originalStage match {
      case OptionVal.Some(stage) ⇒
        if (isIn) s"in port [${stage.shape.inlets(missingHandlerIdx)}]"
        else s"out port [${stage.shape.outlets(missingHandlerIdx - logic.inCount)}"
      case OptionVal.None ⇒
        if (isIn) s"in port id [$missingHandlerIdx]"
        else s"out port id [$missingHandlerIdx]"
    }
    throw new IllegalStateException(s"No handler defined in stage [${logic.originalStage.getOrElse(logic).toString}] for $portLabel." +
      " All inlets and outlets must be assigned a handler with setHandler in the constructor of your graph stage logic.")
  }

  override def toString: String = "GraphStagePhase"
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object SourceModuleIslandTag extends IslandTag

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class SourceModulePhase(
  materializer: PhasedFusingActorMaterializer,
  islandName:   String) extends PhaseIsland[Publisher[Any]] {
  override def name: String = s"SourceModule phase"

  override def materializeAtomic(mod: AtomicModule[Shape, Any], attributes: Attributes): (Publisher[Any], Any) = {
    mod.asInstanceOf[SourceModule[Any, Any]].create(MaterializationContext(materializer, attributes,
      islandName + "-" + attributes.nameOrDefault()))
  }

  override def assignPort(in: InPort, slot: Int, logic: Publisher[Any]): Unit = ()

  override def assignPort(out: OutPort, slot: Int, logic: Publisher[Any]): Unit = ()

  override def createPublisher(out: OutPort, logic: Publisher[Any]): Publisher[Any] = logic

  override def takePublisher(slot: Int, publisher: Publisher[Any]): Unit =
    throw new UnsupportedOperationException("A Source cannot take a Publisher")

  override def onIslandReady(): Unit = ()
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object SinkModuleIslandTag extends IslandTag

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class SinkModulePhase(materializer: PhasedFusingActorMaterializer, islandName: String)
  extends PhaseIsland[AnyRef] {
  override def name: String = s"SinkModule phase"
  var subscriberOrVirtualPublisher: AnyRef = _

  override def materializeAtomic(mod: AtomicModule[Shape, Any], attributes: Attributes): (AnyRef, Any) = {
    val subAndMat =
      mod.asInstanceOf[SinkModule[Any, Any]].create(MaterializationContext(materializer, attributes,
        islandName + "-" + attributes.nameOrDefault()))

    subscriberOrVirtualPublisher = subAndMat._1
    (subscriberOrVirtualPublisher, subAndMat._2)
  }

  override def assignPort(in: InPort, slot: Int, logic: AnyRef): Unit = ()

  override def assignPort(out: OutPort, slot: Int, logic: AnyRef): Unit = ()

  override def createPublisher(out: OutPort, logic: AnyRef): Publisher[Any] = {
    throw new UnsupportedOperationException("A Sink cannot create a Publisher")
  }

  override def takePublisher(slot: Int, publisher: Publisher[Any]): Unit = {
    subscriberOrVirtualPublisher match {
      case v: VirtualPublisher[_]        ⇒ v.registerPublisher(publisher)
      case s: Subscriber[Any] @unchecked ⇒ publisher.subscribe(s)
    }
  }

  override def onIslandReady(): Unit = ()
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ProcessorModuleIslandTag extends IslandTag

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class ProcessorModulePhase(materializer: PhasedFusingActorMaterializer, islandName: String)
  extends PhaseIsland[Processor[Any, Any]] {
  override def name: String = "ProcessorModulePhase"
  private[this] var processor: Processor[Any, Any] = _

  override def materializeAtomic(mod: AtomicModule[Shape, Any], attributes: Attributes): (Processor[Any, Any], Any) = {
    val procAndMat = mod.asInstanceOf[ProcessorModule[Any, Any, Any]].createProcessor()
    processor = procAndMat._1
    procAndMat
  }

  override def assignPort(in: InPort, slot: Int, logic: Processor[Any, Any]): Unit = ()
  override def assignPort(out: OutPort, slot: Int, logic: Processor[Any, Any]): Unit = ()

  override def createPublisher(out: OutPort, logic: Processor[Any, Any]): Publisher[Any] = logic
  override def takePublisher(slot: Int, publisher: Publisher[Any]): Unit = publisher.subscribe(processor)

  override def onIslandReady(): Unit = ()
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object TlsModuleIslandTag extends IslandTag

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class TlsModulePhase(settings: ActorMaterializerSettings, materializer: PhasedFusingActorMaterializer, islandName: String) extends PhaseIsland[NotUsed] {
  def name: String = "TlsModulePhase"

  var tlsActor: ActorRef = _
  var publishers: Vector[ActorPublisher[Any]] = _

  def materializeAtomic(mod: AtomicModule[Shape, Any], attributes: Attributes): (NotUsed, Any) = {
    val tls = mod.asInstanceOf[TlsModule]

    val props =
      TLSActor.props(settings, tls.createSSLEngine, tls.verifySession, tls.closing).withDispatcher(settings.dispatcher)
    tlsActor = materializer.actorOf(props, islandName)
    def factory(id: Int) = new ActorPublisher[Any](tlsActor) {
      override val wakeUpMsg = FanOut.SubstreamSubscribePending(id)
    }
    publishers = Vector.tabulate(2)(factory)
    tlsActor ! FanOut.ExposedPublishers(publishers)
    (NotUsed, NotUsed)
  }
  def assignPort(in: InPort, slot: Int, logic: NotUsed): Unit = ()
  def assignPort(out: OutPort, slot: Int, logic: NotUsed): Unit = ()

  def createPublisher(out: OutPort, logic: NotUsed): Publisher[Any] =
    publishers(out.id)

  def takePublisher(slot: Int, publisher: Publisher[Any]): Unit =
    publisher.subscribe(FanIn.SubInput[Any](tlsActor, 1 - slot))

  def onIslandReady(): Unit = ()
}
