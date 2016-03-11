/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.{ AtomicBoolean }
import java.{ util ⇒ ju }
import akka.NotUsed
import akka.actor._
import akka.event.Logging
import akka.dispatch.Dispatchers
import akka.pattern.ask
import akka.stream._
import akka.stream.impl.StreamLayout.{ Module, AtomicModule }
import akka.stream.impl.fusing.{ ActorGraphInterpreter, GraphModule }
import akka.stream.impl.io.TLSActor
import akka.stream.impl.io.TlsModule
import org.reactivestreams._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Await, ExecutionContextExecutor }
import akka.stream.impl.fusing.GraphStageModule
import akka.stream.impl.fusing.GraphInterpreter.GraphAssembly
import akka.stream.impl.fusing.Fusing
import akka.stream.impl.fusing.GraphInterpreterShell

/**
 * INTERNAL API
 */
private[akka] case class ActorMaterializerImpl(system: ActorSystem,
                                               override val settings: ActorMaterializerSettings,
                                               dispatchers: Dispatchers,
                                               supervisor: ActorRef,
                                               haveShutDown: AtomicBoolean,
                                               flowNames: SeqActorName) extends ActorMaterializer {
  import akka.stream.impl.Stages._
  private val _logger = Logging.getLogger(system, this)
  override def logger = _logger

  if (settings.fuzzingMode && !system.settings.config.hasPath("akka.stream.secret-test-fuzzing-warning-disable")) {
    _logger.warning("Fuzzing mode is enabled on this system. If you see this warning on your production system then " +
      "set akka.stream.materializer.debug.fuzzing-mode to off.")
  }

  override def shutdown(): Unit =
    if (haveShutDown.compareAndSet(false, true)) supervisor ! PoisonPill

  override def isShutdown: Boolean = haveShutDown.get()

  override def withNamePrefix(name: String): ActorMaterializerImpl = this.copy(flowNames = flowNames.copy(name))

  private[this] def createFlowName(): String = flowNames.next()

  private val initialAttributes = Attributes(
    Attributes.InputBuffer(settings.initialInputBufferSize, settings.maxInputBufferSize) ::
      ActorAttributes.Dispatcher(settings.dispatcher) ::
      ActorAttributes.SupervisionStrategy(settings.supervisionDecider) ::
      Nil)

  override def effectiveSettings(opAttr: Attributes): ActorMaterializerSettings = {
    import Attributes._
    import ActorAttributes._
    opAttr.attributeList.foldLeft(settings) { (s, attr) ⇒
      attr match {
        case InputBuffer(initial, max)    ⇒ s.withInputBuffer(initial, max)
        case Dispatcher(dispatcher)       ⇒ s.withDispatcher(dispatcher)
        case SupervisionStrategy(decider) ⇒ s.withSupervisionStrategy(decider)
        case _                            ⇒ s
      }
    }
  }

  override def schedulePeriodically(initialDelay: FiniteDuration, interval: FiniteDuration, task: Runnable) =
    system.scheduler.schedule(initialDelay, interval, task)(executionContext)

  override def scheduleOnce(delay: FiniteDuration, task: Runnable) =
    system.scheduler.scheduleOnce(delay, task)(executionContext)

  override def materialize[Mat](_runnableGraph: Graph[ClosedShape, Mat]): Mat =
    materialize(_runnableGraph, null)

  private[stream] def materialize[Mat](_runnableGraph: Graph[ClosedShape, Mat],
                                       subflowFuser: GraphInterpreterShell ⇒ ActorRef): Mat = {
    val runnableGraph =
      if (settings.autoFusing) Fusing.aggressive(_runnableGraph)
      else _runnableGraph

    if (haveShutDown.get())
      throw new IllegalStateException("Attempted to call materialize() after the ActorMaterializer has been shut down.")
    if (StreamLayout.Debug) StreamLayout.validate(runnableGraph.module)

    val session = new MaterializerSession(runnableGraph.module, initialAttributes) {
      private val flowName = createFlowName()
      private var nextId = 0
      private def stageName(attr: Attributes): String = {
        val name = s"$flowName-$nextId-${attr.nameOrDefault()}"
        nextId += 1
        name
      }

      override protected def materializeAtomic(atomic: AtomicModule, effectiveAttributes: Attributes, matVal: ju.Map[Module, Any]): Unit = {
        if (MaterializerSession.Debug) println(s"materializing $atomic")

        def newMaterializationContext() =
          new MaterializationContext(ActorMaterializerImpl.this, effectiveAttributes, stageName(effectiveAttributes))
        atomic match {
          case sink: SinkModule[_, _] ⇒
            val (sub, mat) = sink.create(newMaterializationContext())
            assignPort(sink.shape.in, sub)
            matVal.put(atomic, mat)
          case source: SourceModule[_, _] ⇒
            val (pub, mat) = source.create(newMaterializationContext())
            assignPort(source.shape.out, pub.asInstanceOf[Publisher[Any]])
            matVal.put(atomic, mat)

          // FIXME: Remove this, only stream-of-stream ops need it
          case stage: StageModule ⇒
            val (processor, mat) = processorFor(stage, effectiveAttributes, effectiveSettings(effectiveAttributes))
            assignPort(stage.inPort, processor)
            assignPort(stage.outPort, processor)
            matVal.put(atomic, mat)

          case tls: TlsModule ⇒ // TODO solve this so TlsModule doesn't need special treatment here
            val es = effectiveSettings(effectiveAttributes)
            val props =
              TLSActor.props(es, tls.sslContext, tls.firstSession, tls.role, tls.closing, tls.hostInfo)
            val impl = actorOf(props, stageName(effectiveAttributes), es.dispatcher)
            def factory(id: Int) = new ActorPublisher[Any](impl) {
              override val wakeUpMsg = FanOut.SubstreamSubscribePending(id)
            }
            val publishers = Vector.tabulate(2)(factory)
            impl ! FanOut.ExposedPublishers(publishers)

            assignPort(tls.plainOut, publishers(TLSActor.UserOut))
            assignPort(tls.cipherOut, publishers(TLSActor.TransportOut))

            assignPort(tls.plainIn, FanIn.SubInput[Any](impl, TLSActor.UserIn))
            assignPort(tls.cipherIn, FanIn.SubInput[Any](impl, TLSActor.TransportIn))

            matVal.put(atomic, NotUsed)

          case graph: GraphModule ⇒
            matGraph(graph, effectiveAttributes, matVal)

          case stage: GraphStageModule ⇒
            val graph =
              GraphModule(GraphAssembly(stage.shape.inlets, stage.shape.outlets, stage.stage),
                stage.shape, stage.attributes, Array(stage))
            matGraph(graph, effectiveAttributes, matVal)
        }
      }

      private def matGraph(graph: GraphModule, effectiveAttributes: Attributes, matVal: ju.Map[Module, Any]): Unit = {
        val calculatedSettings = effectiveSettings(effectiveAttributes)
        val (inHandlers, outHandlers, logics) = graph.assembly.materialize(effectiveAttributes, graph.matValIDs, matVal, registerSrc)

        val shell = new GraphInterpreterShell(graph.assembly, inHandlers, outHandlers, logics, graph.shape,
          calculatedSettings, ActorMaterializerImpl.this)

        val impl =
          if (subflowFuser != null && !effectiveAttributes.contains(Attributes.AsyncBoundary)) {
            subflowFuser(shell)
          } else {
            val props = ActorGraphInterpreter.props(shell)
            actorOf(props, stageName(effectiveAttributes), calculatedSettings.dispatcher)
          }

        for ((inlet, i) ← graph.shape.inlets.iterator.zipWithIndex) {
          val subscriber = new ActorGraphInterpreter.BoundarySubscriber(impl, shell, i)
          assignPort(inlet, subscriber)
        }
        for ((outlet, i) ← graph.shape.outlets.iterator.zipWithIndex) {
          val publisher = new ActorGraphInterpreter.BoundaryPublisher(impl, shell, i)
          impl ! ActorGraphInterpreter.ExposedPublisher(shell, i, publisher)
          assignPort(outlet, publisher)
        }
      }

      // FIXME: Remove this, only stream-of-stream ops need it
      private def processorFor(op: StageModule,
                               effectiveAttributes: Attributes,
                               effectiveSettings: ActorMaterializerSettings): (Processor[Any, Any], Any) = op match {
        case DirectProcessor(processorFactory, _) ⇒ processorFactory()
        case _ ⇒
          val (opprops, mat) = ActorProcessorFactory.props(ActorMaterializerImpl.this, op, effectiveAttributes)
          ActorProcessorFactory[Any, Any](
            actorOf(opprops, stageName(effectiveAttributes), effectiveSettings.dispatcher)) -> mat
      }
    }

    session.materialize().asInstanceOf[Mat]
  }

  override lazy val executionContext: ExecutionContextExecutor = dispatchers.lookup(settings.dispatcher match {
    case Deploy.NoDispatcherGiven ⇒ Dispatchers.DefaultDispatcherId
    case other                    ⇒ other
  })

  override def actorOf(context: MaterializationContext, props: Props): ActorRef = {
    val dispatcher =
      if (props.deploy.dispatcher == Deploy.NoDispatcherGiven) effectiveSettings(context.effectiveAttributes).dispatcher
      else props.dispatcher
    actorOf(props, context.stageName, dispatcher)
  }

  private[akka] def actorOf(props: Props, name: String, dispatcher: String): ActorRef = {
    supervisor match {
      case ref: LocalActorRef ⇒
        ref.underlying.attachChild(props.withDispatcher(dispatcher), name, systemService = false)
      case ref: RepointableActorRef ⇒
        if (ref.isStarted)
          ref.underlying.asInstanceOf[ActorCell].attachChild(props.withDispatcher(dispatcher), name, systemService = false)
        else {
          implicit val timeout = ref.system.settings.CreationTimeout
          val f = (supervisor ? StreamSupervisor.Materialize(props.withDispatcher(dispatcher), name)).mapTo[ActorRef]
          Await.result(f, timeout.duration)
        }
      case unknown ⇒
        throw new IllegalStateException(s"Stream supervisor must be a local actor, was [${unknown.getClass.getName}]")
    }
  }

}

private[akka] class SubFusingActorMaterializerImpl(val delegate: ActorMaterializerImpl, registerShell: GraphInterpreterShell ⇒ ActorRef) extends Materializer {
  override def executionContext: ExecutionContextExecutor = delegate.executionContext

  override def materialize[Mat](runnable: Graph[ClosedShape, Mat]): Mat = delegate.materialize(runnable, registerShell)

  override def scheduleOnce(delay: FiniteDuration, task: Runnable): Cancellable = delegate.scheduleOnce(delay, task)

  override def schedulePeriodically(initialDelay: FiniteDuration, interval: FiniteDuration, task: Runnable): Cancellable =
    delegate.schedulePeriodically(initialDelay, interval, task)

  def withNamePrefix(name: String): SubFusingActorMaterializerImpl =
    new SubFusingActorMaterializerImpl(delegate.withNamePrefix(name), registerShell)
}

/**
 * INTERNAL API
 */
private[akka] object FlowNames extends ExtensionId[FlowNames] with ExtensionIdProvider {
  override def get(system: ActorSystem): FlowNames = super.get(system)
  override def lookup() = FlowNames
  override def createExtension(system: ExtendedActorSystem): FlowNames = new FlowNames
}

/**
 * INTERNAL API
 */
private[akka] class FlowNames extends Extension {
  val name = SeqActorName("Flow")
}

/**
 * INTERNAL API
 */
private[akka] object StreamSupervisor {
  def props(settings: ActorMaterializerSettings, haveShutDown: AtomicBoolean): Props =
    Props(new StreamSupervisor(settings, haveShutDown)).withDeploy(Deploy.local)

  private val actorName = SeqActorName("StreamSupervisor")
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

private[akka] class StreamSupervisor(settings: ActorMaterializerSettings, haveShutDown: AtomicBoolean) extends Actor {
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

/**
 * INTERNAL API
 */
private[akka] object ActorProcessorFactory {
  import akka.stream.impl.Stages._

  def props(materializer: ActorMaterializer, op: StageModule, parentAttributes: Attributes): (Props, Any) = {
    val att = parentAttributes and op.attributes
    // USE THIS TO AVOID CLOSING OVER THE MATERIALIZER BELOW
    // Also, otherwise the attributes will not affect the settings properly!
    val settings = materializer.effectiveSettings(att)
    op match {
      case GroupBy(maxSubstreams, f, _) ⇒ (GroupByProcessorImpl.props(settings, maxSubstreams, f), ())
      case DirectProcessor(p, m)        ⇒ throw new AssertionError("DirectProcessor cannot end up in ActorProcessorFactory")
    }
  }

  def apply[I, O](impl: ActorRef): ActorProcessor[I, O] = {
    val p = new ActorProcessor[I, O](impl)
    // Resolve cyclic dependency with actor. This MUST be the first message no matter what.
    impl ! ExposedPublisher(p.asInstanceOf[ActorPublisher[Any]])
    p
  }
}
