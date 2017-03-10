/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor._
import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.pattern.ask
import akka.stream._
import akka.stream.impl.fusing.GraphInterpreterShell
import akka.util.OptionVal

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Await, ExecutionContextExecutor }

/**
 * ExtendedActorMaterializer used by subtypes which materializer using GraphInterpreterShell
 */
abstract class ExtendedActorMaterializer extends ActorMaterializer {

  override def withNamePrefix(name: String): ExtendedActorMaterializer

  /** INTERNAL API */
  @InternalApi def materialize[Mat](_runnableGraph: Graph[ClosedShape, Mat]): Mat

  /** INTERNAL API */
  @InternalApi def materialize[Mat](
    _runnableGraph:    Graph[ClosedShape, Mat],
    initialAttributes: Attributes): Mat

  /** INTERNAL API */
  @InternalApi private[akka] def materialize[Mat](
    graph:             Graph[ClosedShape, Mat],
    initialAttributes: Attributes,
    defaultPhase:      Phase[Any],
    phases:            Map[IslandTag, Phase[Any]]): Mat

  /**
   * INTERNAL API
   */
  override def actorOf(context: MaterializationContext, props: Props): ActorRef = {
    val dispatcher =
      if (props.deploy.dispatcher == Deploy.NoDispatcherGiven) effectiveSettings(context.effectiveAttributes).dispatcher
      else props.dispatcher
    actorOf(props.withDispatcher(dispatcher), context.islandName)
  }

  /**
   * INTERNAL API
   */
  def actorOf(props: Props, name: String): ActorRef = {
    supervisor match {
      case ref: LocalActorRef ⇒
        ref.underlying.attachChild(props, name, systemService = false)
      case ref: RepointableActorRef ⇒
        if (ref.isStarted)
          ref.underlying.asInstanceOf[ActorCell].attachChild(props, name, systemService = false)
        else {
          implicit val timeout = ref.system.settings.CreationTimeout
          val f = (supervisor ? StreamSupervisor.Materialize(props, name)).mapTo[ActorRef]
          Await.result(f, timeout.duration)
        }
      case unknown ⇒
        throw new IllegalStateException(s"Stream supervisor must be a local actor, was [${unknown.getClass.getName}]")
    }
  }

  /**
   * INTERNAL API
   */
  override def logger: LoggingAdapter

  /**
   * INTERNAL API
   */
  override def supervisor: ActorRef

}

/**
 * This materializer replaces the default phase with one that will fuse stages into an existing interpreter (via `registerShell`),
 * rather than start a new actor for each of them.
 *
 * The default phases are left in-tact since we still respect `.async` and other tags that were marked within a sub-fused graph.
 */
private[akka] class SubFusingActorMaterializerImpl(val delegate: ExtendedActorMaterializer, registerShell: GraphInterpreterShell ⇒ ActorRef) extends Materializer {
  val subFusingPhase = new Phase[Any] {
    override def apply(settings: ActorMaterializerSettings, materializer: PhasedFusingActorMaterializer, islandName: String): PhaseIsland[Any] = {
      new GraphStageIsland(settings, materializer, islandName, OptionVal(registerShell)).asInstanceOf[PhaseIsland[Any]]
    }
  }

  override def executionContext: ExecutionContextExecutor = delegate.executionContext

  override def materialize[Mat](runnable: Graph[ClosedShape, Mat]): Mat =
    delegate.materialize(runnable)

  override def materialize[Mat](runnable: Graph[ClosedShape, Mat], initialAttributes: Attributes): Mat = {
    if (PhasedFusingActorMaterializer.Debug) println(s"Using [${getClass.getSimpleName}] to materialize [${runnable}]")
    val phases = PhasedFusingActorMaterializer.DefaultPhases

    delegate.materialize(runnable, initialAttributes, subFusingPhase, phases)
  }

  override def scheduleOnce(delay: FiniteDuration, task: Runnable): Cancellable = delegate.scheduleOnce(delay, task)

  override def schedulePeriodically(initialDelay: FiniteDuration, interval: FiniteDuration, task: Runnable): Cancellable =
    delegate.schedulePeriodically(initialDelay, interval, task)

  override def withNamePrefix(name: String): SubFusingActorMaterializerImpl =
    new SubFusingActorMaterializerImpl(delegate.withNamePrefix(name), registerShell)
}

/**
 * INTERNAL API
 */
object FlowNames extends ExtensionId[FlowNames] with ExtensionIdProvider {
  override def get(system: ActorSystem): FlowNames = super.get(system)
  override def lookup() = FlowNames
  override def createExtension(system: ExtendedActorSystem): FlowNames = new FlowNames
}

/**
 * INTERNAL API
 */
class FlowNames extends Extension {
  val name = SeqActorName("Flow")
}

/**
 * INTERNAL API
 */
object StreamSupervisor {
  def props(settings: ActorMaterializerSettings, haveShutDown: AtomicBoolean): Props =
    Props(new StreamSupervisor(settings, haveShutDown)).withDeploy(Deploy.local)
  private[stream] val baseName = "StreamSupervisor"
  private val actorName = SeqActorName(baseName)
  def nextName(): String = actorName.next()

  final case class Materialize(props: Props, name: String)
    extends DeadLetterSuppression with NoSerializationVerificationNeeded

  /** Testing purpose */
  case object GetChildren
  /** Testing purpose */
  final case class Children(children: Set[ActorRef])
  /** Testing purpose */
  case object StopChildren
  /** Testing purpose */
  case object StoppedChildren
  /** Testing purpose */
  case object PrintDebugDump
}

class StreamSupervisor(settings: ActorMaterializerSettings, haveShutDown: AtomicBoolean) extends Actor {
  import akka.stream.impl.StreamSupervisor._

  override def supervisorStrategy = SupervisorStrategy.stoppingStrategy

  def receive = {
    case Materialize(props, name) ⇒
      val impl = context.actorOf(props, name)
      sender() ! impl
    case GetChildren ⇒ sender() ! Children(context.children.toSet)
    case StopChildren ⇒
      context.children.foreach(context.stop)
      sender() ! StoppedChildren
  }

  override def postStop(): Unit = haveShutDown.set(true)
}

