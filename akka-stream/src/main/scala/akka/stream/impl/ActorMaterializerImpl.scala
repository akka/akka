/*
 * Copyright (C) 2014-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.immutable
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future
import scala.concurrent.duration._

import com.github.ghik.silencer.silent

import akka.actor._
import akka.annotation.DoNotInherit
import akka.annotation.InternalApi
import akka.dispatch.Dispatchers
import akka.event.LoggingAdapter
import akka.pattern.ask
import akka.pattern.pipe
import akka.pattern.retry
import akka.stream._
import akka.stream.impl.fusing.ActorGraphInterpreter
import akka.stream.impl.fusing.GraphInterpreterShell
import akka.stream.snapshot.StreamSnapshot
import akka.util.OptionVal
import akka.util.Timeout

/**
 * ExtendedActorMaterializer used by subtypes which delegates in-island wiring to [[akka.stream.impl.PhaseIsland]]s
 *
 * INTERNAL API
 */
@silent("deprecated")
@DoNotInherit private[akka] abstract class ExtendedActorMaterializer extends ActorMaterializer {

  override def withNamePrefix(name: String): ExtendedActorMaterializer

  /** INTERNAL API */
  @InternalApi def materialize[Mat](_runnableGraph: Graph[ClosedShape, Mat]): Mat

  /** INTERNAL API */
  @InternalApi def materialize[Mat](_runnableGraph: Graph[ClosedShape, Mat], defaultAttributes: Attributes): Mat

  /** INTERNAL API */
  @InternalApi private[akka] def materialize[Mat](
      graph: Graph[ClosedShape, Mat],
      defaultAttributes: Attributes,
      defaultPhase: Phase[Any],
      phases: Map[IslandTag, Phase[Any]]): Mat

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] override def actorOf(context: MaterializationContext, props: Props): ActorRef = {
    val effectiveProps = props.dispatcher match {
      case Dispatchers.DefaultDispatcherId =>
        // the caller said to use the default dispatcher, but that can been trumped by the dispatcher attribute
        props.withDispatcher(context.effectiveAttributes.mandatoryAttribute[ActorAttributes.Dispatcher].dispatcher)
      case _ => props
    }

    actorOf(effectiveProps, context.islandName)
  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def actorOf(props: Props, name: String): ActorRef = {
    supervisor match {
      case ref: LocalActorRef =>
        ref.underlying.attachChild(props, name, systemService = false)
      case unknown =>
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
private[akka] class SubFusingActorMaterializerImpl(
    val delegate: ExtendedActorMaterializer,
    registerShell: GraphInterpreterShell => ActorRef)
    extends Materializer {
  val subFusingPhase = new Phase[Any] {
    override def apply(
        settings: ActorMaterializerSettings,
        attributes: Attributes,
        materializer: PhasedFusingActorMaterializer,
        islandName: String): PhaseIsland[Any] = {
      new GraphStageIsland(attributes, materializer, islandName, OptionVal(registerShell))
        .asInstanceOf[PhaseIsland[Any]]
    }
  }

  override def executionContext: ExecutionContextExecutor = delegate.executionContext

  override def materialize[Mat](runnable: Graph[ClosedShape, Mat]): Mat =
    delegate match {
      case am: PhasedFusingActorMaterializer =>
        materialize(runnable, am.defaultAttributes)

      case other =>
        throw new IllegalStateException(
          s"SubFusing only supported by [PhasedFusingActorMaterializer], " +
          s"yet was used with [${other.getClass.getName}]!")
    }

  override def materialize[Mat](runnable: Graph[ClosedShape, Mat], defaultAttributes: Attributes): Mat = {
    if (PhasedFusingActorMaterializer.Debug) println(s"Using [${getClass.getSimpleName}] to materialize [${runnable}]")
    val phases = PhasedFusingActorMaterializer.DefaultPhases

    delegate.materialize(runnable, defaultAttributes, subFusingPhase, phases)
  }

  override def scheduleOnce(delay: FiniteDuration, task: Runnable): Cancellable = delegate.scheduleOnce(delay, task)

  override def scheduleWithFixedDelay(
      initialDelay: FiniteDuration,
      delay: FiniteDuration,
      task: Runnable): Cancellable =
    delegate.scheduleWithFixedDelay(initialDelay, delay, task)

  override def scheduleAtFixedRate(
      initialDelay: FiniteDuration,
      interval: FiniteDuration,
      task: Runnable): Cancellable =
    delegate.scheduleAtFixedRate(initialDelay, interval, task)

  override def schedulePeriodically(
      initialDelay: FiniteDuration,
      interval: FiniteDuration,
      task: Runnable): Cancellable =
    scheduleAtFixedRate(initialDelay, interval, task)

  override def withNamePrefix(name: String): SubFusingActorMaterializerImpl =
    new SubFusingActorMaterializerImpl(delegate.withNamePrefix(name), registerShell)

  override def shutdown(): Unit = delegate.shutdown()

  override def isShutdown: Boolean = delegate.isShutdown

  override def system: ActorSystem = delegate.system

  override private[akka] def logger = delegate.logger

  override private[akka] def supervisor = delegate.supervisor

  override private[akka] def actorOf(context: MaterializationContext, props: Props): ActorRef =
    delegate.actorOf(context, props)

  @silent("deprecated")
  override def settings: ActorMaterializerSettings = delegate.settings
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object FlowNames extends ExtensionId[FlowNames] with ExtensionIdProvider {
  override def get(system: ActorSystem): FlowNames = super.get(system)
  override def get(system: ClassicActorSystemProvider): FlowNames = super.get(system)
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
  def props(attributes: Attributes, haveShutDown: AtomicBoolean): Props =
    Props(new StreamSupervisor(haveShutDown))
      .withDeploy(Deploy.local)
      .withDispatcher(attributes.mandatoryAttribute[ActorAttributes.Dispatcher].dispatcher)
  private[stream] val baseName = "StreamSupervisor"
  private val actorName = SeqActorName(baseName)
  def nextName(): String = actorName.next()

  final case class Materialize(props: Props, name: String)
      extends DeadLetterSuppression
      with NoSerializationVerificationNeeded

  case object GetChildrenSnapshots
  final case class ChildrenSnapshots(seq: immutable.Seq[StreamSnapshot])
      extends DeadLetterSuppression
      with NoSerializationVerificationNeeded

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
@InternalApi private[akka] class StreamSupervisor(haveShutDown: AtomicBoolean) extends Actor {
  import akka.stream.impl.StreamSupervisor._
  implicit val ec: ExecutionContextExecutor = context.dispatcher
  override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy

  def receive: Receive = {
    case Materialize(props, name) =>
      val impl = context.actorOf(props, name)
      sender() ! impl
    case GetChildren =>
      sender() ! Children(context.children.toSet)
    case GetChildrenSnapshots =>
      takeSnapshotsOfChildren().map(ChildrenSnapshots.apply _).pipeTo(sender())

    case StopChildren =>
      context.children.foreach(context.stop)
      sender() ! StoppedChildren
  }

  def takeSnapshotsOfChildren(): Future[immutable.Seq[StreamSnapshot]] = {
    // Arbitrary timeout but should always be quick, the failure scenario is that
    // the child/stream stopped, and we do retry below
    implicit val timeout: Timeout = 1.second
    def takeSnapshot() = {
      val futureSnapshots =
        context.children.toList.map(child => (child ? ActorGraphInterpreter.Snapshot).mapTo[StreamSnapshot])
      Future.sequence(futureSnapshots)
    }

    // If the timeout hits it is likely because one of the streams stopped between looking at the list
    // of children and asking it for a snapshot. We retry the entire snapshot in that case
    retry(() => takeSnapshot(), 3)
  }

  override def postStop(): Unit = haveShutDown.set(true)
}
