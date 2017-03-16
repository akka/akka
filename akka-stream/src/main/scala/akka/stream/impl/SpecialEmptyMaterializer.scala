/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{ ActorContext, ActorRef, ActorSystem }
import akka.annotation.InternalApi
import akka.dispatch.Dispatchers
import akka.event.{ LoggingAdapter, NoLogging }
import akka.stream._
import akka.stream.impl.StreamLayout.AtomicModule
import akka.stream.impl.fusing.{ ActorGraphInterpreterLogic, GraphInterpreterShell, GraphStageModule }
import akka.stream.stage.GraphStageLogic
import akka.util.OptionVal
import org.reactivestreams.Publisher

@InternalApi
private[akka] object SpecialEmptyMaterializer {
  val InPlacePhase: Phase[Any] = new Phase[Any] {
    override def apply(settings: ActorMaterializerSettings, materializer: PhasedFusingActorMaterializer, islandName: String): PhaseIsland[Any] =
      new SpecialEmptyStreamGraphStageIsland(settings, materializer, islandName).asInstanceOf[PhaseIsland[Any]]
  }
}

@InternalApi
private[akka] final case class SpecialEmptyMaterializer(
  override val system:       ActorSystem,
  override val settings:     ActorMaterializerSettings,
  override val dispatchers:  Dispatchers,
  override val supervisor:   ActorRef,
  override val haveShutDown: AtomicBoolean,
  override val flowNames:    SeqActorName
) extends PhasedFusingActorMaterializer(system, settings, dispatchers, supervisor, haveShutDown, flowNames) {
  override protected def defaultPhase[Mat]: Phase[Any] = SpecialEmptyMaterializer.InPlacePhase
}

object SpecialEmptyStreamGraphStageIsland {
  final val NotSupportedException =
    new RuntimeException(s"Attempted to materialize complex graph that is not supported by the [${SpecialEmptyMaterializer.getClass.getName}]! " +
      s"Use a proper materializer instead (e.g. ActorMaterializer).")
}

@InternalApi
private[akka] final class SpecialEmptyStreamGraphStageIsland(
  effectiveSettings: ActorMaterializerSettings,
  materializer:      PhasedFusingActorMaterializer,
  islandName:        String
) extends GraphStageIsland(effectiveSettings, materializer, islandName, OptionVal.None) {

  override def createPublisher(out: OutPort, logic: GraphStageLogic): Publisher[Any] =
    throw SpecialEmptyStreamGraphStageIsland.NotSupportedException

  override def takePublisher(slot: Int, publisher: Publisher[Any]): Unit =
    throw SpecialEmptyStreamGraphStageIsland.NotSupportedException

  override def materializeAtomic(mod: AtomicModule[Shape, Any], attributes: Attributes): (GraphStageLogic, Any) = {
    val stageModule = mod.asInstanceOf[GraphStageModule[Shape, Any]]
    val stage = stageModule.stage

    super.materializeAtomic(mod, attributes)
  }

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
