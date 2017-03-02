package akka.stream.impl

import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean

import akka.NotUsed
import akka.actor.{ ActorContext, ActorRef, ActorRefFactory, ActorSystem, Cancellable, Deploy, ExtendedActorSystem, PoisonPill, Props }
import akka.dispatch.Dispatchers
import akka.event.{ Logging, LoggingAdapter }
import akka.stream.Attributes.InputBuffer
import akka.stream._
import akka.stream.impl.StreamLayout.AtomicModule
import akka.stream.impl.fusing.ActorGraphInterpreter.{ ActorOutputBoundary, BatchingActorInputBoundary }
import akka.stream.impl.fusing.GraphInterpreter.Connection
import akka.stream.impl.fusing._
import akka.stream.stage.{ GraphStageLogic, InHandler, OutHandler }
import org.reactivestreams.{ Processor, Publisher, Subscriber, Subscription }

import scala.collection.immutable.Map
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.util.Random

object PhasedFusingActorMaterializer {

  val Debug = false

  val DefaultPhase: Phase[Any] = new Phase[Any] {
    override def apply(settings: ActorMaterializerSettings, materializer: PhasedFusingActorMaterializer): PhaseIsland[Any] =
      new GraphStageIsland(settings, materializer).asInstanceOf[PhaseIsland[Any]]
  }

  val DefaultPhases: Map[IslandTag, Phase[Any]] = Map[IslandTag, Phase[Any]](
    SinkModuleIslandTag → new Phase[Any] {
      override def apply(settings: ActorMaterializerSettings, materializer: PhasedFusingActorMaterializer): PhaseIsland[Any] =
        (new SinkModulePhase(materializer)).asInstanceOf[PhaseIsland[Any]]
    },
    SourceModuleIslandTag → new Phase[Any] {
      override def apply(settings: ActorMaterializerSettings, materializer: PhasedFusingActorMaterializer): PhaseIsland[Any] =
        new SourceModulePhase(materializer).asInstanceOf[PhaseIsland[Any]]
    },
    ProcessorModuleIslandTag → new Phase[Any] {
      override def apply(settings: ActorMaterializerSettings, materializer: PhasedFusingActorMaterializer): PhaseIsland[Any] =
        new ProcessorModulePhase(materializer).asInstanceOf[PhaseIsland[Any]]
    },
    GraphStageTag → DefaultPhase
  )

  def apply(settings: ActorMaterializerSettings)(implicit context: ActorRefFactory): ActorMaterializer = {
    val haveShutDown = new AtomicBoolean(false)
    val system = actorSystemOf(context)
    val materializerSettings = ActorMaterializerSettings(system)

    PhasedFusingActorMaterializer(
      system,
      materializerSettings,
      system.dispatchers,
      context.actorOf(StreamSupervisor.props(materializerSettings, haveShutDown).withDispatcher(materializerSettings.dispatcher), StreamSupervisor.nextName()),
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

private case class SegmentInfo(
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

private case class ForwardWire(
  islandGlobalOffset: Int,
  from:               OutPort,
  toGlobalOffset:     Int,
  outStage:           Any,
  phase:              PhaseIsland[Any]) {

  override def toString: String = s"ForwardWire(islandId = $islandGlobalOffset, from = $from, toGlobal = $toGlobalOffset, phase = $phase)"
}

class IslandTracking(
  val phases:       Map[IslandTag, Phase[Any]],
  val settings:     ActorMaterializerSettings,
  defaultPhase:     Phase[Any],
  val materializer: PhasedFusingActorMaterializer
) {

  import PhasedFusingActorMaterializer.Debug

  private var currentGlobalOffset = 0
  private var currentSegmentGlobalOffset = 0
  private var currentIslandGlobalOffset = 0
  // The number of slots that belong to segments of other islands encountered so far, from the
  // beginning of the island
  private var currentIslandSkippetSlots = 0

  private var segments: java.util.ArrayList[SegmentInfo] = null
  private var forwardWires: java.util.ArrayList[ForwardWire] = null

  private var currentPhase: PhaseIsland[Any] = defaultPhase.apply(settings, materializer)

  def getCurrentPhase: PhaseIsland[Any] = currentPhase
  def getCurrentOffset: Int = currentGlobalOffset

  private def completeSegment(): Int = {
    val length = currentGlobalOffset - currentSegmentGlobalOffset

    if (length > 0) {
      // We just finished a segment by entering an island.
      val previousSegment = SegmentInfo(
        globalislandOffset = currentIslandGlobalOffset,
        length = currentGlobalOffset - currentSegmentGlobalOffset,
        globalBaseOffset = currentSegmentGlobalOffset,
        relativeBaseOffset = currentSegmentGlobalOffset - currentIslandGlobalOffset - currentIslandSkippetSlots,
        currentPhase
      )

      // Segment tracking is by demand, we only allocate this list if it is used.
      // If there are no islands, then there is no need to track segments
      if (segments eq null) segments = new java.util.ArrayList[SegmentInfo](8)
      segments.add(previousSegment)
      if (Debug) println(s"Completed segment $previousSegment")
    } else {
      if (Debug) println(s"Skipped zero length segment")
    }

    length
  }

  def enterIsland(tag: IslandTag, attributes: Attributes): ExitIsland = {
    completeSegment()
    val previousPhase = currentPhase
    val previousIslandOffset = currentIslandGlobalOffset

    val effectiveSettings: ActorMaterializerSettings = {
      import Attributes._
      import ActorAttributes._
      attributes.attributeList.foldLeft(settings) { (s, attr) ⇒
        attr match {
          case InputBuffer(initial, max)    ⇒ s.withInputBuffer(initial, max)
          case Dispatcher(dispatcher)       ⇒ s.withDispatcher(dispatcher)
          case SupervisionStrategy(decider) ⇒ s.withSupervisionStrategy(decider)
          case _                            ⇒ s
        }
      }
    }

    currentPhase = phases(tag)(effectiveSettings, materializer)
    if (Debug) println(s"Entering island starting at offset = $currentIslandGlobalOffset phase = $currentPhase")

    // Resolve the phase to be used to materialize this island
    currentIslandGlobalOffset = currentGlobalOffset

    // The base offset of this segment is the current global offset
    currentSegmentGlobalOffset = currentGlobalOffset
    ExitIsland(previousIslandOffset, currentIslandSkippetSlots, previousPhase)
  }

  def exitIsland(exitIsland: ExitIsland): Unit = {
    val previousSegmentLength = completeSegment()

    // Closing previous island
    currentPhase.onIslandReady()

    // We start a new segment
    currentSegmentGlobalOffset = currentGlobalOffset

    // We restore data for the island
    currentIslandGlobalOffset = exitIsland.islandGlobalOffset
    currentPhase = exitIsland.phase
    currentIslandSkippetSlots = exitIsland.skippedSlots + previousSegmentLength

    if (Debug) println(s"Exited to island starting at offset = $currentIslandGlobalOffset phase = $currentPhase")
  }

  def wireIn(in: InPort, logic: Any): Unit = {
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

  def wireOut(out: OutPort, absoluteOffset: Int, logic: Any): Unit = {
    // TODO: forward wires
    if (Debug) println(s"  wiring $out to absolute = $absoluteOffset")

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
        currentPhase
      )

      if (Debug) println(s"    wiring is forward, recording $forwardWire")
      forwardWires.add(forwardWire)
    }

  }

}

case class PhasedFusingActorMaterializer(
  system:                ActorSystem,
  override val settings: ActorMaterializerSettings,
  dispatchers:           Dispatchers,
  supervisor:            ActorRef,
  haveShutDown:          AtomicBoolean,
  flowNames:             SeqActorName
) extends ExtendedActorMaterializer {
  import PhasedFusingActorMaterializer._

  private val _logger = Logging.getLogger(system, this)
  override def logger: LoggingAdapter = _logger

  if (settings.fuzzingMode && !system.settings.config.hasPath("akka.stream.secret-test-fuzzing-warning-disable")) {
    _logger.warning("Fuzzing mode is enabled on this system. If you see this warning on your production system then " +
      "set akka.stream.materializer.debug.fuzzing-mode to off.")
  }

  override def shutdown(): Unit =
    if (haveShutDown.compareAndSet(false, true)) supervisor ! PoisonPill

  override def isShutdown: Boolean = haveShutDown.get()

  override def withNamePrefix(name: String): PhasedFusingActorMaterializer = this.copy(flowNames = flowNames.copy(name))

  private[this] def createFlowName(): String = flowNames.next()

  private val defaultInitialAttributes = Attributes(
    Attributes.InputBuffer(settings.initialInputBufferSize, settings.maxInputBufferSize) ::
      ActorAttributes.Dispatcher(settings.dispatcher) ::
      ActorAttributes.SupervisionStrategy(settings.supervisionDecider) ::
      Nil)

  override def effectiveSettings(opAttr: Attributes): ActorMaterializerSettings = {
    import ActorAttributes._
    import Attributes._
    opAttr.attributeList.foldLeft(settings) { (s, attr) ⇒
      attr match {
        case InputBuffer(initial, max)    ⇒ s.withInputBuffer(initial, max)
        case Dispatcher(dispatcher)       ⇒ s.withDispatcher(dispatcher)
        case SupervisionStrategy(decider) ⇒ s.withSupervisionStrategy(decider)
        case _                            ⇒ s
      }
    }
  }

  override def schedulePeriodically(initialDelay: FiniteDuration, interval: FiniteDuration, task: Runnable): Cancellable =
    system.scheduler.schedule(initialDelay, interval, task)(executionContext)

  override def scheduleOnce(delay: FiniteDuration, task: Runnable): Cancellable =
    system.scheduler.scheduleOnce(delay, task)(executionContext)

  override def materialize[Mat](_runnableGraph: Graph[ClosedShape, Mat]): Mat =
    materialize(_runnableGraph, null, defaultInitialAttributes)

  override def materialize[Mat](_runnableGraph: Graph[ClosedShape, Mat], initialAttributes: Attributes): Mat =
    materialize(_runnableGraph, null, initialAttributes)

  override def materialize[Mat](_runnableGraph: Graph[ClosedShape, Mat], subflowFuser: (GraphInterpreterShell) ⇒ ActorRef): Mat =
    materialize(_runnableGraph, subflowFuser, defaultInitialAttributes)

  override def makeLogger(logSource: Class[_]): LoggingAdapter =
    Logging(system, logSource)

  override lazy val executionContext: ExecutionContextExecutor = dispatchers.lookup(settings.dispatcher match {
    case Deploy.NoDispatcherGiven ⇒ Dispatchers.DefaultDispatcherId
    case other                    ⇒ other
  })

  override def materialize[Mat](
    _runnableGraph:    Graph[ClosedShape, Mat],
    subflowFuser:      (GraphInterpreterShell) ⇒ ActorRef,
    initialAttributes: Attributes): Mat = {
    materialize(
      _runnableGraph,
      subflowFuser,
      initialAttributes,
      PhasedFusingActorMaterializer.DefaultPhase,
      PhasedFusingActorMaterializer.DefaultPhases
    )
  }

  def materialize[Mat](
    graph:             Graph[ClosedShape, Mat],
    subflowFuser:      GraphInterpreterShell ⇒ ActorRef,
    initialAttributes: Attributes,
    defaultPhase:      Phase[Any],
    phases:            Map[IslandTag, Phase[Any]]
  ): Mat = {
    val islandTracking = new IslandTracking(phases, settings, defaultPhase, this)

    var current: Traversal = graph.traversalBuilder.traversal

    val attributesStack = new java.util.ArrayDeque[Attributes](8)
    attributesStack.addLast(initialAttributes)

    // TODO: No longer need for a stack
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

            val ins = mod.shape.inlets.iterator
            val stageGlobalOffset = islandTracking.getCurrentOffset

            while (ins.hasNext) {
              val in = ins.next()
              islandTracking.wireIn(in, logic)
            }

            val outs = mod.shape.outlets.iterator
            while (outs.hasNext) {
              val out = outs.next()
              val absoluteTargetSlot = stageGlobalOffset + outToSlot(out.id)
              if (Debug) println(s"  wiring offset: ${outToSlot.mkString("[", ",", "]")}")
              islandTracking.wireOut(out, absoluteTargetSlot, logic)
            }

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
          case Transform(f) ⇒
            val prev = matValueStack.removeLast()
            val result = f(prev)
            matValueStack.addLast(result)
            if (Debug) println(s"TRFM: $matValueStack")
          case Compose(f) ⇒
            val second = matValueStack.removeLast()
            val first = matValueStack.removeLast()
            val result = f(first, second)
            matValueStack.addLast(result)
            if (Debug) println(s"COMP: $matValueStack")
          case PushAttributes(attr) ⇒
            attributesStack.addLast(attributesStack.getLast and attr)
            if (Debug) println(s"ATTR PUSH: $attr")
          case PopAttributes ⇒
            attributesStack.removeLast()
            if (Debug) println(s"ATTR POP")
          case EnterIsland(tag, island) ⇒
            traversalStack.addLast(islandTracking.enterIsland(tag, attributesStack.getLast))
            nextStep = island
          case ex: ExitIsland ⇒
            islandTracking.exitIsland(ex)
          case _ ⇒
        }
        current = nextStep
      }
    }

    islandTracking.getCurrentPhase.onIslandReady()

    if (Debug) println("--- Finished materialization")
    matValueStack.peekLast().asInstanceOf[Mat]
  }

}

trait IslandTag

trait Phase[M] {
  def apply(settings: ActorMaterializerSettings, materializer: PhasedFusingActorMaterializer): PhaseIsland[M]
}

trait PhaseIsland[M] {

  def name: String

  def materializeAtomic(mod: AtomicModule[Shape, Any], attributes: Attributes): (M, Any)

  def assignPort(in: InPort, slot: Int, logic: M): Unit

  def assignPort(out: OutPort, slot: Int, logic: M): Unit

  def createPublisher(out: OutPort, logic: M): Publisher[Any]

  def takePublisher(slot: Int, publisher: Publisher[Any]): Unit

  def onIslandReady(): Unit

}

object GraphStageTag extends IslandTag

final class GraphStageIsland(
  settings:     ActorMaterializerSettings,
  materializer: PhasedFusingActorMaterializer
) extends PhaseIsland[GraphStageLogic] {
  // TODO: remove these
  private val logicArrayType = Array.empty[GraphStageLogic]
  private[this] val logics = new ArrayList[GraphStageLogic](64)
  // TODO: Resize
  private val connections = Array.ofDim[Connection](64)
  private var maxConnections = 0
  private var outConnections: List[Connection] = Nil

  val shell = new GraphInterpreterShell(
    connections = null,
    logics = null,
    settings,
    materializer)

  override def name: String = "Fusing GraphStages phase"

  override def materializeAtomic(mod: AtomicModule[Shape, Any], attributes: Attributes): (GraphStageLogic, Any) = {
    // TODO: bail on unknown types
    val stageModule = mod.asInstanceOf[GraphStageModule[Shape, Any]]
    val matAndLogic = stageModule.stage.createLogicAndMaterializedValue(attributes)
    val logic = matAndLogic._1
    logic.attributes = attributes
    logics.add(logic)
    logic.stageId = logics.size() - 1
    matAndLogic
  }

  def conn(slot: Int): Connection = {
    maxConnections = math.max(slot, maxConnections)
    val c = connections(slot)
    if (c ne null) c
    else {
      val c2 = new Connection(0, 0, null, 0, null, null, null)
      connections(slot) = c2
      c2
    }
  }

  def outConn(): Connection = {
    val connection = new Connection(0, 0, null, 0, null, null, null)
    outConnections ::= connection
    connection
  }

  override def assignPort(in: InPort, slot: Int, logic: GraphStageLogic): Unit = {
    val connection = conn(slot)
    connection.inOwner = logic
    connection.id = slot
    connection.inOwnerId = logic.stageId
    connection.inHandler = logic.handlers(in.id).asInstanceOf[InHandler]
    logic.portToConn(in.id) = connection
  }

  override def assignPort(out: OutPort, slot: Int, logic: GraphStageLogic): Unit = {
    val connection = conn(slot)
    connection.outOwner = logic
    connection.id = slot
    connection.outOwnerId = logic.stageId
    connection.outHandler = logic.handlers(logic.inCount + out.id).asInstanceOf[OutHandler]
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
    connection.inOwnerId = boundary.stageId

    connection.outOwner = logic
    connection.id = -1 // Will be filled later
    connection.outOwnerId = logic.stageId
    connection.outHandler = logic.handlers(logic.inCount + out.id).asInstanceOf[OutHandler]
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
    connection.outOwnerId = boundary.stageId
  }

  override def onIslandReady(): Unit = {

    val totalConnections = maxConnections + outConnections.size + 1
    val finalConnections = java.util.Arrays.copyOf(connections, totalConnections)

    var i = maxConnections + 1
    var outConns = outConnections
    while (i < totalConnections) {
      val conn = outConns.head
      outConns = outConns.tail
      finalConnections(i) = conn
      conn.id = i
      i += 1
    }

    shell.connections = finalConnections
    shell.logics = logics.toArray(logicArrayType)

    // TODO: Subfusing
    //    if (subflowFuser != null) {
    //      subflowFuser(shell)
    //    } else {
    val props = ActorGraphInterpreter.props(shell)
    // TODO: actor names
    materializer.actorOf(props, "fused" + Random.nextInt(), settings.dispatcher)
    //    }

  }

  override def toString: String = "GraphStagePhase"
}

object SourceModuleIslandTag extends IslandTag

final class SourceModulePhase(materializer: PhasedFusingActorMaterializer) extends PhaseIsland[Publisher[Any]] {
  override def name: String = s"SourceModule phase"

  override def materializeAtomic(mod: AtomicModule[Shape, Any], attributes: Attributes): (Publisher[Any], Any) = {
    // TODO: proper stage name
    mod.asInstanceOf[SourceModule[Any, Any]].create(MaterializationContext(materializer, attributes, "stageName"))
  }

  override def assignPort(in: InPort, slot: Int, logic: Publisher[Any]): Unit = ()

  override def assignPort(out: OutPort, slot: Int, logic: Publisher[Any]): Unit = ()

  override def createPublisher(out: OutPort, logic: Publisher[Any]): Publisher[Any] = logic

  override def takePublisher(slot: Int, publisher: Publisher[Any]): Unit =
    throw new UnsupportedOperationException("A Source cannot take a Publisher")

  override def onIslandReady(): Unit = ()
}

object SinkModuleIslandTag extends IslandTag

final class SinkModulePhase(materializer: PhasedFusingActorMaterializer) extends PhaseIsland[AnyRef] {
  override def name: String = s"SourceModule phase"
  var subscriberOrVirtualPublisher: AnyRef = _

  override def materializeAtomic(mod: AtomicModule[Shape, Any], attributes: Attributes): (AnyRef, Any) = {
    // TODO: proper stage name
    val subAndMat =
      mod.asInstanceOf[SinkModule[Any, Any]].create(MaterializationContext(materializer, attributes, "stageName"))

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
      case v: VirtualPublisher[Any] ⇒ v.registerPublisher(publisher)
      case s: Subscriber[Any]       ⇒ publisher.subscribe(s)
    }
  }

  override def onIslandReady(): Unit = ()
}

object ProcessorModuleIslandTag extends IslandTag

final class ProcessorModulePhase(materializer: PhasedFusingActorMaterializer) extends PhaseIsland[Processor[Any, Any]] {
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