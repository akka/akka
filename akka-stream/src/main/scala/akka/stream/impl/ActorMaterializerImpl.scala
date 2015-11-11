/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicLong }

import akka.actor._
import akka.dispatch.Dispatchers
import akka.pattern.ask
import akka.stream._
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl.fusing.{ ActorGraphInterpreter, GraphModule }
import akka.stream.impl.io.SslTlsCipherActor
import akka.stream.io.SslTls.TlsModule
import org.reactivestreams._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Await, ExecutionContextExecutor }

/**
 * INTERNAL API
 */
private[akka] case class ActorMaterializerImpl(system: ActorSystem,
                                               override val settings: ActorMaterializerSettings,
                                               dispatchers: Dispatchers,
                                               supervisor: ActorRef,
                                               haveShutDown: AtomicBoolean,
                                               flowNameCounter: AtomicLong,
                                               namePrefix: String) extends ActorMaterializer {
  import akka.stream.impl.Stages._

  override def shutdown(): Unit =
    if (haveShutDown.compareAndSet(false, true)) supervisor ! PoisonPill

  override def isShutdown: Boolean = haveShutDown.get()

  override def withNamePrefix(name: String): Materializer = this.copy(namePrefix = name)

  private[this] def nextFlowNameCount(): Long = flowNameCounter.incrementAndGet()

  private[this] def createFlowName(): String = s"$namePrefix-${nextFlowNameCount()}"

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

  override def materialize[Mat](runnableGraph: Graph[ClosedShape, Mat]): Mat = {
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

      override protected def materializeAtomic(atomic: Module, effectiveAttributes: Attributes): Any = {

        def newMaterializationContext() =
          new MaterializationContext(ActorMaterializerImpl.this, effectiveAttributes, stageName(effectiveAttributes))
        atomic match {
          case sink: SinkModule[_, _] ⇒
            val (sub, mat) = sink.create(newMaterializationContext())
            assignPort(sink.shape.inlet, sub.asInstanceOf[Subscriber[Any]])
            mat
          case source: SourceModule[_, _] ⇒
            val (pub, mat) = source.create(newMaterializationContext())
            assignPort(source.shape.outlet, pub.asInstanceOf[Publisher[Any]])
            mat

          // FIXME: Remove this, only stream-of-stream ops need it
          case stage: StageModule ⇒
            val (processor, mat) = processorFor(stage, effectiveAttributes, effectiveSettings(effectiveAttributes))
            assignPort(stage.inPort, processor)
            assignPort(stage.outPort, processor)
            mat

          case tls: TlsModule ⇒ // TODO solve this so TlsModule doesn't need special treatment here
            val es = effectiveSettings(effectiveAttributes)
            val props =
              SslTlsCipherActor.props(es, tls.sslContext, tls.firstSession, tls.role, tls.closing, tls.hostInfo)
            val impl = actorOf(props, stageName(effectiveAttributes), es.dispatcher)
            def factory(id: Int) = new ActorPublisher[Any](impl) {
              override val wakeUpMsg = FanOut.SubstreamSubscribePending(id)
            }
            val publishers = Vector.tabulate(2)(factory)
            impl ! FanOut.ExposedPublishers(publishers)

            assignPort(tls.plainOut, publishers(SslTlsCipherActor.UserOut))
            assignPort(tls.cipherOut, publishers(SslTlsCipherActor.TransportOut))

            assignPort(tls.plainIn, FanIn.SubInput[Any](impl, SslTlsCipherActor.UserIn))
            assignPort(tls.cipherIn, FanIn.SubInput[Any](impl, SslTlsCipherActor.TransportIn))

          case graph: GraphModule ⇒
            val calculatedSettings = effectiveSettings(effectiveAttributes)
            val (inHandlers, outHandlers, logics, mat) = graph.assembly.materialize(effectiveAttributes)

            val props = ActorGraphInterpreter.props(
              graph.assembly, inHandlers, outHandlers, logics, graph.shape, calculatedSettings, ActorMaterializerImpl.this)

            val impl = actorOf(props, stageName(effectiveAttributes), calculatedSettings.dispatcher)
            for ((inlet, i) ← graph.shape.inlets.iterator.zipWithIndex) {
              val subscriber = new ActorGraphInterpreter.BoundarySubscriber(impl, i)
              assignPort(inlet, subscriber)
            }
            for ((outlet, i) ← graph.shape.outlets.iterator.zipWithIndex) {
              val publisher = new ActorPublisher[Any](impl) { override val wakeUpMsg = ActorGraphInterpreter.SubscribePending(i) }
              impl ! ActorGraphInterpreter.ExposedPublisher(i, publisher)
              assignPort(outlet, publisher)
            }
            mat
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

/**
 * INTERNAL API
 */
private[akka] object FlowNameCounter extends ExtensionId[FlowNameCounter] with ExtensionIdProvider {
  override def get(system: ActorSystem): FlowNameCounter = super.get(system)
  override def lookup() = FlowNameCounter
  override def createExtension(system: ExtendedActorSystem): FlowNameCounter = new FlowNameCounter
}

/**
 * INTERNAL API
 */
private[akka] class FlowNameCounter extends Extension {
  val counter = new AtomicLong(0)
}

/**
 * INTERNAL API
 */
private[akka] object StreamSupervisor {
  def props(settings: ActorMaterializerSettings, haveShutDown: AtomicBoolean): Props =
    Props(new StreamSupervisor(settings, haveShutDown)).withDeploy(Deploy.local)

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
      case GroupBy(f, _)         ⇒ (GroupByProcessorImpl.props(settings, f), ())
      case PrefixAndTail(n, _)   ⇒ (PrefixAndTailImpl.props(settings, n), ())
      case Split(d, _)           ⇒ (SplitWhereProcessorImpl.props(settings, d), ())
      case ConcatAll(f, _)       ⇒ (ConcatAllImpl.props(f, materializer), ())
      case DirectProcessor(p, m) ⇒ throw new AssertionError("DirectProcessor cannot end up in ActorProcessorFactory")
    }
  }

  def apply[I, O](impl: ActorRef): ActorProcessor[I, O] = {
    val p = new ActorProcessor[I, O](impl)
    // Resolve cyclic dependency with actor. This MUST be the first message no matter what.
    impl ! ExposedPublisher(p.asInstanceOf[ActorPublisher[Any]])
    p
  }
}
