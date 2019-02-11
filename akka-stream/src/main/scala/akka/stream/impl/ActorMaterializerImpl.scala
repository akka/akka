/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor._
import akka.annotation.{ DoNotInherit, InternalApi }
import akka.dispatch.Dispatchers
import akka.event.LoggingAdapter
import akka.pattern.ask
import akka.stream._
import akka.stream.impl.fusing.GraphInterpreterShell
import akka.util.OptionVal

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Await, ExecutionContextExecutor }

/**
 * ExtendedActorMaterializer used by subtypes which delegates in-island wiring to [[akka.stream.impl.PhaseIsland]]s
 */
@DoNotInherit private[akka] abstract class ExtendedActorMaterializer extends ActorMaterializer {

  override def withNamePrefix(name: String): ExtendedActorMaterializer

  /** INTERNAL API */
  @InternalApi def materialize[Mat](_runnableGraph: Graph[ClosedShape, Mat]): Mat

  /** INTERNAL API */
  @InternalApi def materialize[Mat](
    _runnableGraph:    Graph[ClosedShape, Mat],
    defaultAttributes: Attributes): Mat

  /** INTERNAL API */
  @InternalApi private[akka] def materialize[Mat](
    graph:             Graph[ClosedShape, Mat],
    defaultAttributes: Attributes,
    defaultPhase:      Phase[Any],
    phases:            Map[IslandTag, Phase[Any]]): Mat

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] override def actorOf(context: MaterializationContext, props: Props): ActorRef = {
    val effectiveProps = props.dispatcher match {
      case Dispatchers.DefaultDispatcherId ⇒
        props.withDispatcher(context.effectiveAttributes.mandatoryAttribute[ActorAttributes.Dispatcher].dispatcher)
      case ActorAttributes.IODispatcher.dispatcher ⇒
        // this one is actually not a dispatcher but a relative config key pointing containing the actual dispatcher name
        props.withDispatcher(settings.blockingIoDispatcher)
      case _ ⇒ props
    }

    actorOf(effectiveProps, context.islandName)
  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def actorOf(props: Props, name: String): ActorRef = {
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
  @InternalApi private[akka] override def logger: LoggingAdapter

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] override def supervisor: ActorRef

}

/**
 * This materializer replaces the default phase with one that will fuse operators into an existing interpreter (via `registerShell`),
 * rather than start a new actor for each of them.
 *
 * The default phases are left in-tact since we still respect `.async` and other tags that were marked within a sub-fused graph.
 */
private[akka] class SubFusingActorMaterializerImpl(val delegate: ExtendedActorMaterializer, registerShell: GraphInterpreterShell ⇒ ActorRef) extends Materializer {
  val subFusingPhase = new Phase[Any] {
    override def apply(settings: ActorMaterializerSettings, attributes: Attributes,
                       materializer: PhasedFusingActorMaterializer, islandName: String): PhaseIsland[Any] = {
      new GraphStageIsland(settings, attributes, materializer, islandName, OptionVal(registerShell)).asInstanceOf[PhaseIsland[Any]]
    }
  }

  override def executionContext: ExecutionContextExecutor = delegate.executionContext

  override def materialize[Mat](runnable: Graph[ClosedShape, Mat]): Mat =
    delegate match {
      case am: PhasedFusingActorMaterializer ⇒
        materialize(runnable, am.defaultAttributes)

      case other ⇒
        throw new IllegalStateException(s"SubFusing only supported by [PhasedFusingActorMaterializer], " +
          s"yet was used with [${other.getClass.getName}]!")
    }

  override def materialize[Mat](runnable: Graph[ClosedShape, Mat], defaultAttributes: Attributes): Mat = {
    if (PhasedFusingActorMaterializer.Debug) println(s"Using [${getClass.getSimpleName}] to materialize [${runnable}]")
    val phases = PhasedFusingActorMaterializer.DefaultPhases

    delegate.materialize(runnable, defaultAttributes, subFusingPhase, phases)
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
@InternalApi private[akka] object FlowNames extends ExtensionId[FlowNames] with ExtensionIdProvider {
  override def get(system: ActorSystem): FlowNames = super.get(system)
  override def lookup() = FlowNames
  override def createExtension(system: ExtendedActorSystem): FlowNames = new FlowNames
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class FlowNames extends Extension {
  val name = SeqActorName("Flow")
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object StreamSupervisor {
  def props(settings: ActorMaterializerSettings, haveShutDown: AtomicBoolean): Props =
    Props(new StreamSupervisor(settings, haveShutDown)).withDeploy(Deploy.local)
      .withDispatcher(settings.dispatcher)
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
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class StreamSupervisor(settings: ActorMaterializerSettings, haveShutDown: AtomicBoolean) extends Actor {
  import akka.stream.impl.StreamSupervisor._

  override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy

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

