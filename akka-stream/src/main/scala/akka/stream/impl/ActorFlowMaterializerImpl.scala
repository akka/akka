/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicLong

import akka.actor._
import akka.dispatch.Dispatchers
import akka.pattern.ask
import akka.stream.actor.ActorSubscriber
import akka.stream.impl.GenJunctions.ZipWithModule
import akka.stream.impl.Junctions._
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl.fusing.ActorInterpreter
import akka.stream.scaladsl._
import akka.stream._
import org.reactivestreams._

import scala.concurrent.{ Await, ExecutionContextExecutor }

/**
 * INTERNAL API
 */
private[akka] case class ActorFlowMaterializerImpl(override val settings: ActorFlowMaterializerSettings,
                                                   dispatchers: Dispatchers,
                                                   supervisor: ActorRef,
                                                   flowNameCounter: AtomicLong,
                                                   namePrefix: String,
                                                   optimizations: Optimizations)
  extends ActorFlowMaterializer {
  import ActorFlowMaterializerImpl._
  import akka.stream.impl.Stages._

  override def withNamePrefix(name: String): FlowMaterializer = this.copy(namePrefix = name)

  private[this] def nextFlowNameCount(): Long = flowNameCounter.incrementAndGet()

  private[this] def createFlowName(): String = s"$namePrefix-${nextFlowNameCount()}"

  override def effectiveSettings(opAttr: OperationAttributes): ActorFlowMaterializerSettings = {
    import OperationAttributes._
    import ActorOperationAttributes._
    opAttr.attributes.foldLeft(settings) { (s, attr) ⇒
      attr match {
        case InputBuffer(initial, max)    ⇒ s.withInputBuffer(initial, max)
        case Dispatcher(dispatcher)       ⇒ s.withDispatcher(dispatcher)
        case SupervisionStrategy(decider) ⇒ s.withSupervisionStrategy(decider)
        case Name(_)                      ⇒ s
      }
    }
  }

  override def materialize[Mat](runnableFlow: Graph[ClosedShape, Mat]): Mat = {
    runnableFlow.module.validate()

    val session = new MaterializerSession(runnableFlow.module) {
      private val flowName = createFlowName()
      private var nextId = 0
      private def stageName(attr: OperationAttributes): String = {
        val name = s"$flowName-$nextId-${attr.nameOrDefault()}"
        nextId += 1
        name
      }

      override protected def materializeAtomic(atomic: Module, effectiveAttributes: OperationAttributes): Any = {

        def newMaterializationContext() = new MaterializationContext(ActorFlowMaterializerImpl.this,
          effectiveAttributes, stageName(effectiveAttributes))
        atomic match {
          case sink: SinkModule[_, _] ⇒
            val (sub, mat) = sink.create(newMaterializationContext())
            assignPort(sink.shape.inlet, sub.asInstanceOf[Subscriber[Any]])
            mat
          case source: SourceModule[_, _] ⇒
            val (pub, mat) = source.create(newMaterializationContext())
            assignPort(source.shape.outlet, pub.asInstanceOf[Publisher[Any]])
            mat

          case stage: StageModule ⇒
            val (processor, mat) = processorFor(stage, effectiveAttributes, effectiveSettings(effectiveAttributes))
            assignPort(stage.inPort, processor)
            assignPort(stage.outPort, processor)
            mat

          case junction: JunctionModule ⇒ materializeJunction(junction, effectiveAttributes, effectiveSettings(effectiveAttributes))
        }
      }

      private def processorFor(op: StageModule,
                               effectiveAttributes: OperationAttributes,
                               effectiveSettings: ActorFlowMaterializerSettings): (Processor[Any, Any], Any) = op match {
        case DirectProcessor(processorFactory, _) ⇒ processorFactory()
        case _ ⇒
          val (opprops, mat) = ActorProcessorFactory.props(ActorFlowMaterializerImpl.this, op, effectiveAttributes)
          val processor = ActorProcessorFactory[Any, Any](actorOf(
            opprops,
            stageName(effectiveAttributes),
            effectiveSettings.dispatcher))
          processor -> mat
      }

      private def materializeJunction(op: JunctionModule,
                                      effectiveAttributes: OperationAttributes,
                                      effectiveSettings: ActorFlowMaterializerSettings): Unit = {
        op match {
          case fanin: FanInModule ⇒
            val (props, inputs, output) = fanin match {

              case MergeModule(shape, _) ⇒
                (FairMerge.props(effectiveSettings, shape.inArray.size), shape.inArray.toSeq, shape.out)

              case f: FlexiMergeModule[t, p] ⇒
                val flexi = f.flexi(f.shape)
                (FlexiMerge.props(effectiveSettings, f.shape, flexi), f.shape.inlets, f.shape.outlets.head)

              case MergePreferredModule(shape, _) ⇒
                (UnfairMerge.props(effectiveSettings, shape.inlets.size), shape.preferred +: shape.inArray.toSeq, shape.out)

              case ConcatModule(shape, _) ⇒
                require(shape.inArray.size == 2, "currently only supporting concatenation of exactly two inputs") // FIXME
                (Concat.props(effectiveSettings), shape.inArray.toSeq, shape.out)

              case zip: ZipWithModule ⇒
                (zip.props(effectiveSettings), zip.shape.inlets, zip.outPorts.head)
            }
            val impl = actorOf(props, stageName(effectiveAttributes), effectiveSettings.dispatcher)
            val publisher = new ActorPublisher[Any](impl)
            impl ! ExposedPublisher(publisher)
            for ((in, id) ← inputs.zipWithIndex) {
              assignPort(in, FanIn.SubInput[Any](impl, id))
            }
            assignPort(output, publisher)

          case fanout: FanOutModule ⇒
            val (props, in, outs) = fanout match {

              case r: FlexiRouteModule[t, p] ⇒
                val flexi = r.flexi(r.shape)
                (FlexiRoute.props(effectiveSettings, r.shape, flexi), r.shape.inlets.head: InPort, r.shape.outlets)

              case BroadcastModule(shape, _) ⇒
                (Broadcast.props(effectiveSettings, shape.outArray.size), shape.in, shape.outArray.toSeq)

              case BalanceModule(shape, waitForDownstreams, _) ⇒
                (Balance.props(effectiveSettings, shape.outArray.size, waitForDownstreams), shape.in, shape.outArray.toSeq)

              case UnzipModule(shape, _) ⇒
                (Unzip.props(effectiveSettings), shape.in, shape.outlets)
            }
            val impl = actorOf(props, stageName(effectiveAttributes), effectiveSettings.dispatcher)
            val size = outs.size
            def factory(id: Int) = new ActorPublisher[Any](impl) {
              override val wakeUpMsg = FanOut.SubstreamSubscribePending(id)
            }
            val publishers =
              if (outs.size < 8) Vector.tabulate(size)(factory)
              else List.tabulate(size)(factory)
            impl ! FanOut.ExposedPublishers(publishers)

            publishers.zip(outs).foreach { case (pub, out) ⇒ assignPort(out, pub) }
            val subscriber = ActorSubscriber[Any](impl)
            assignPort(in, subscriber)

        }
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

  private[akka] def actorOf(props: Props, name: String, dispatcher: String): ActorRef = supervisor match {
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

/**
 * INTERNAL API
 */
private[akka] object FlowNameCounter extends ExtensionId[FlowNameCounter] with ExtensionIdProvider {
  override def get(system: ActorSystem): FlowNameCounter = super.get(system)
  override def lookup = FlowNameCounter
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
  def props(settings: ActorFlowMaterializerSettings): Props = Props(new StreamSupervisor(settings))

  final case class Materialize(props: Props, name: String) extends DeadLetterSuppression
}

private[akka] class StreamSupervisor(settings: ActorFlowMaterializerSettings) extends Actor {
  import akka.stream.impl.StreamSupervisor._

  override def supervisorStrategy = SupervisorStrategy.stoppingStrategy

  def receive = {
    case Materialize(props, name) ⇒
      val impl = context.actorOf(props, name)
      sender() ! impl
  }
}

/**
 * INTERNAL API
 */
private[akka] object ActorProcessorFactory {
  import akka.stream.impl.Stages._
  import ActorFlowMaterializerImpl._

  private val _identity = (x: Any) ⇒ x

  def props(materializer: ActorFlowMaterializer, op: StageModule, parentAttributes: OperationAttributes): (Props, Any) = {
    val att = parentAttributes and op.attributes
    // USE THIS TO AVOID CLOSING OVER THE MATERIALIZER BELOW
    // Also, otherwise the attributes will not affect the settings properly!
    val settings = materializer.effectiveSettings(att)
    op match {
      case Identity(_)                ⇒ (ActorInterpreter.props(settings, List(fusing.Map(_identity, settings.supervisionDecider)), materializer), ())
      case Fused(ops, _)              ⇒ (ActorInterpreter.props(settings, ops, materializer), ())
      case Map(f, _)                  ⇒ (ActorInterpreter.props(settings, List(fusing.Map(f, settings.supervisionDecider)), materializer), ())
      case Filter(p, _)               ⇒ (ActorInterpreter.props(settings, List(fusing.Filter(p, settings.supervisionDecider)), materializer), ())
      case Drop(n, _)                 ⇒ (ActorInterpreter.props(settings, List(fusing.Drop(n)), materializer), ())
      case Take(n, _)                 ⇒ (ActorInterpreter.props(settings, List(fusing.Take(n)), materializer), ())
      case Collect(pf, _)             ⇒ (ActorInterpreter.props(settings, List(fusing.Collect(settings.supervisionDecider)(pf)), materializer), ())
      case Scan(z, f, _)              ⇒ (ActorInterpreter.props(settings, List(fusing.Scan(z, f, settings.supervisionDecider)), materializer), ())
      case Expand(s, f, _)            ⇒ (ActorInterpreter.props(settings, List(fusing.Expand(s, f)), materializer), ())
      case Conflate(s, f, _)          ⇒ (ActorInterpreter.props(settings, List(fusing.Conflate(s, f, settings.supervisionDecider)), materializer), ())
      case Buffer(n, s, _)            ⇒ (ActorInterpreter.props(settings, List(fusing.Buffer(n, s)), materializer), ())
      case MapConcat(f, _)            ⇒ (ActorInterpreter.props(settings, List(fusing.MapConcat(f, settings.supervisionDecider)), materializer), ())
      case MapAsync(p, f, _)          ⇒ (ActorInterpreter.props(settings, List(fusing.MapAsync(p, f, settings.supervisionDecider)), materializer), ())
      case MapAsyncUnordered(p, f, _) ⇒ (ActorInterpreter.props(settings, List(fusing.MapAsyncUnordered(p, f, settings.supervisionDecider)), materializer), ())
      case Grouped(n, _)              ⇒ (ActorInterpreter.props(settings, List(fusing.Grouped(n)), materializer), ())
      case GroupBy(f, _)              ⇒ (GroupByProcessorImpl.props(settings, f), ())
      case PrefixAndTail(n, _)        ⇒ (PrefixAndTailImpl.props(settings, n), ())
      case SplitWhen(p, _)            ⇒ (SplitWhenProcessorImpl.props(settings, p), ())
      case ConcatAll(_)               ⇒ (ConcatAllImpl.props(materializer), ()) //FIXME closes over the materializer, is this good?
      case StageFactory(mkStage, _)   ⇒ (ActorInterpreter.props(settings, List(mkStage()), materializer), ())
      case TimerTransform(mkStage, _) ⇒ (TimerTransformerProcessorsImpl.props(settings, mkStage()), ())
      case MaterializingStageFactory(mkStageAndMat, _) ⇒
        val sm = mkStageAndMat()
        (ActorInterpreter.props(settings, List(sm._1), materializer), sm._2)
      case DirectProcessor(p, m) ⇒ throw new AssertionError("DirectProcessor cannot end up in ActorProcessorFactory")
    }
  }

  def apply[I, O](impl: ActorRef): ActorProcessor[I, O] = {
    val p = new ActorProcessor[I, O](impl)
    impl ! ExposedPublisher(p.asInstanceOf[ActorPublisher[Any]])
    p
  }
}
