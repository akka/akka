/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{ ActorContext, ActorRef, ActorSystem }
import akka.dispatch.Dispatchers
import akka.event.{ Logging, LoggingAdapter, NoLogging }
import akka.stream.Attributes.InputBuffer
import akka.stream.impl.StreamLayout.AtomicModule
import akka.stream.impl.fusing.ActorGraphInterpreter.{ ActorOutputBoundary, BatchingActorInputBoundary }
import akka.stream.impl.fusing.GraphInterpreter.Connection
import akka.stream.impl.fusing.{ ActorGraphInterpreter, ActorGraphInterpreterLogic, GraphInterpreterShell, GraphStageModule }
import akka.stream.stage.{ GraphStageLogic, InHandler, OutHandler }
import akka.stream._
import akka.util.OptionVal
import org.reactivestreams.Publisher

import scala.collection.immutable.Map

private[akka] final case class SpecialEmptyMaterializer(
  override val system:       ActorSystem,
  override val settings:     ActorMaterializerSettings,
  override val dispatchers:  Dispatchers,
  override val supervisor:   ActorRef,
  override val haveShutDown: AtomicBoolean,
  override val flowNames:    SeqActorName
) extends PhasedFusingActorMaterializer(system, settings, dispatchers, supervisor, haveShutDown, flowNames) {

  val Debug = false

  val InPlacePhase: Phase[Any] = new Phase[Any] {
    override def apply(settings: ActorMaterializerSettings, materializer: PhasedFusingActorMaterializer, islandName: String): PhaseIsland[Any] =
      new SpecialGraphStageIsland(settings, materializer, islandName, subflowFuser = OptionVal.None).asInstanceOf[PhaseIsland[Any]]
  }

  override def materialize[Mat](
    graph:             Graph[ClosedShape, Mat],
    initialAttributes: Attributes,
    defaultPhase:      Phase[Any],
    phases:            Map[IslandTag, Phase[Any]]
  ): Mat = {

    val islandTracking = new IslandTracking(phases, settings, InPlacePhase, this, islandNamePrefix = flowNames.next() + "-")

    var current: Traversal = graph.traversalBuilder.traversal

    val attributesStack = new java.util.ArrayDeque[Attributes](4)
    attributesStack.addLast(initialAttributes and graph.traversalBuilder.attributes)

    val traversalStack = new java.util.ArrayDeque[Traversal](4)
    traversalStack.addLast(current)

    val matValueStack = new java.util.ArrayDeque[Any](4)

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
            //            if (matValue.isInstanceOf[String]) return matValue.asInstanceOf[Mat] // RETURN ASAP!!!
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
          case transform: Transform ⇒
            val prev = matValueStack.removeLast()
            val result = transform(prev)
            matValueStack.addLast(result)
            if (Debug) println(s"TRFM: $matValueStack")
          case compose: ComposeOp ⇒
            val second = matValueStack.removeLast()
            val first = matValueStack.removeLast()
            val result = compose(first, second)
            matValueStack.addLast(result)
            if (Debug) println(s"COMP: $matValueStack")
          case EnterIsland(tag) ⇒
            islandTracking.enterIsland(tag, attributesStack.getLast)
          case ExitIsland ⇒
            islandTracking.exitIsland()
          case _ ⇒ // ignore
        }
        current = nextStep
      }
    }

    islandTracking.getCurrentPhase.onIslandReady()
    islandTracking.allNestedIslandsReady()

    if (Debug) println("--- Finished materialization")
    matValueStack.peekLast().asInstanceOf[Mat]
  }
}

final class SpecialGraphStageIsland(
  effectiveSettings: ActorMaterializerSettings,
  materializer:      PhasedFusingActorMaterializer,
  islandName:        String,
  subflowFuser:      OptionVal[GraphInterpreterShell ⇒ ActorRef]) extends PhaseIsland[GraphStageLogic] {
  // TODO: remove these
  private val logicArrayType = Array.empty[GraphStageLogic]
  private[this] val logics = new ArrayList[GraphStageLogic](4)
  // TODO: Resize
  private val connections = new Array[Connection](4)
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
      case OptionVal.None    ⇒ fullIslandName = OptionVal.Some("FAST_NAME")
    }
    matAndLogic
  }

  def conn(slot: Int): Connection = {
    maxConnections = math.max(slot, maxConnections)
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
    logic.portToConn(in.id) = connection
  }

  override def assignPort(out: OutPort, slot: Int, logic: GraphStageLogic): Unit = {
    val connection = conn(slot)
    connection.outOwner = logic
    connection.id = slot
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

    connection.outOwner = logic
    connection.id = -1 // Will be filled later
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

    val interpreter = new ActorGraphInterpreterLogic {
      override val _initial: GraphInterpreterShell = shell
      override def log: LoggingAdapter = NoLogging
      override def self: ActorRef = null
      override def context: ActorContext = ???
    }

    interpreter.preStart()
    interpreter.processEvent(shell.resume)

    //    subflowFuser match {
    //      case OptionVal.Some(fuseIntoExistingInterperter) ⇒
    //        fuseIntoExistingInterperter(shell)
    //
    //      case _ ⇒
    //        ???
    //              val props = ActorGraphInterpreter.props(shell)
    //      //          .withDispatcher(effectiveSettings.dispatcher)
    //      //        val actorName = fullIslandName match {
    //      //          case OptionVal.Some(n) ⇒ n
    //      //          case OptionVal.None    ⇒ islandName
    //      //        }
    //      //        materializer.actorOf(props, actorName)
    //    }
  }

  override def toString: String = "GraphStagePhase"
}
