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
  subflowFuser:      OptionVal[GraphInterpreterShell ⇒ ActorRef]
) extends GraphStageIsland(effectiveSettings, materializer, islandName, OptionVal.None) {

  override protected def onIslandReadyStartInterpreter(shell: GraphInterpreterShell) = {
    val interpreter = new ActorGraphInterpreterLogic {
      override val _initial: GraphInterpreterShell = shell
      override def eventLimit: Int = Int.MaxValue
      override def log: LoggingAdapter = NoLogging
      override def self: ActorRef = null
      override def context: ActorContext = null
    }

    interpreter.preStart()
    interpreter.processEvent(shell.resume)
    interpreter.postStop()
  }

  override def onIslandReady(): Unit = super.onIslandReady()

  override def toString: String = "GraphStagePhase"
}
