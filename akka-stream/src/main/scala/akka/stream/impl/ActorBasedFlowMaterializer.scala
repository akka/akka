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
import akka.stream.{ FlowMaterializer, MaterializerSettings, InPort }
import org.reactivestreams._

import scala.concurrent.{ Await, ExecutionContext }

/**
 * INTERNAL API
 */
final object Optimizations {
  val none: Optimizations = Optimizations(collapsing = false, elision = false, simplification = false, fusion = false)
  val all: Optimizations = Optimizations(collapsing = true, elision = true, simplification = true, fusion = true)
}
/**
 * INTERNAL API
 */
final case class Optimizations(collapsing: Boolean, elision: Boolean, simplification: Boolean, fusion: Boolean) {
  def isEnabled: Boolean = collapsing || elision || simplification || fusion
}

/**
 * INTERNAL API
 */
case class ActorBasedFlowMaterializer(override val settings: MaterializerSettings,
                                      dispatchers: Dispatchers, // FIXME is this the right choice for loading an EC?
                                      supervisor: ActorRef,
                                      flowNameCounter: AtomicLong,
                                      namePrefix: String,
                                      optimizations: Optimizations)
  extends FlowMaterializer(settings) {
  import akka.stream.impl.Stages._

  def withNamePrefix(name: String): FlowMaterializer = this.copy(namePrefix = name)

  private[this] def nextFlowNameCount(): Long = flowNameCounter.incrementAndGet()

  private[this] def createFlowName(): String = s"$namePrefix-${nextFlowNameCount()}"

  override def materialize[Mat](runnableFlow: RunnableFlow[Mat]): Mat = {
    runnableFlow.module.validate()

    val session = new MaterializerSession(runnableFlow.module) {
      private val flowName = createFlowName()
      private var nextId = 0
      private def stageName(attr: OperationAttributes): String = {
        val name = s"$flowName-$nextId-${attr.name}"
        nextId += 1
        name
      }

      override protected def materializeAtomic(atomic: Module, effectiveAttributes: OperationAttributes): Any = atomic match {
        case sink: SinkModule[_, _] ⇒
          val (sub, mat) = sink.create(ActorBasedFlowMaterializer.this, stageName(effectiveAttributes))
          assignPort(sink.shape.inlet, sub.asInstanceOf[Subscriber[Any]])
          mat
        case source: SourceModule[_, _] ⇒
          val (pub, mat) = source.create(ActorBasedFlowMaterializer.this, stageName(effectiveAttributes))
          assignPort(source.shape.outlet, pub.asInstanceOf[Publisher[Any]])
          mat

        case stage: StageModule ⇒
          val (processor, mat) = processorFor(stage, effectiveAttributes)
          assignPort(stage.inPort, processor)
          assignPort(stage.outPort, processor)
          mat

        case junction: JunctionModule ⇒ materializeJunction(junction, effectiveAttributes)

      }

      private def processorFor(op: StageModule, effectiveAttributes: OperationAttributes): (Processor[Any, Any], Any) = op match {
        case DirectProcessor(processorFactory, _) ⇒ processorFactory()
        case _ ⇒
          val (opprops, mat) = ActorProcessorFactory.props(ActorBasedFlowMaterializer.this, op)
          val processor = ActorProcessorFactory[Any, Any](actorOf(
            opprops,
            stageName(effectiveAttributes),
            effectiveAttributes.settings(settings).dispatcher))
          processor -> mat
      }

      private def materializeJunction(op: JunctionModule, effectiveAttributes: OperationAttributes): Unit = {
        op match {
          case fanin: FanInModule ⇒
            val (props, inputs, output) = fanin match {
              case MergeModule(shape, _) ⇒
                (FairMerge.props(effectiveAttributes.settings(settings), shape.inArray.size), shape.inArray.toSeq, shape.out)

              case f: FlexiMergeModule[t, p] ⇒
                val flexi = f.flexi(f.shape)
                (FlexiMerge.props(effectiveAttributes.settings(settings), f.shape, flexi), f.shape.inlets, f.shape.outlets.head)
              // TODO each materialization needs its own logic

              case MergePreferredModule(shape, _) ⇒
                (UnfairMerge.props(effectiveAttributes.settings(settings), shape.inlets.size), shape.preferred +: shape.inArray.toSeq, shape.out)

              case ConcatModule(shape, _) ⇒
                require(shape.inArray.size == 2, "currently only supporting concatenation of exactly two inputs") // FIXME
                (Concat.props(effectiveAttributes.settings(settings)), shape.inArray.toSeq, shape.out)

              case zip: ZipWithModule ⇒
                (zip.props(effectiveAttributes.settings(settings)), zip.shape.inlets, zip.outPorts.head)
            }
            val impl = actorOf(props, stageName(effectiveAttributes), effectiveAttributes.settings(settings).dispatcher)
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
                (FlexiRoute.props(effectiveAttributes.settings(settings), r.shape, flexi), r.shape.inlets.head: InPort, r.shape.outlets)
              case BroadcastModule(shape, _) ⇒
                (Broadcast.props(effectiveAttributes.settings(settings), shape.outArray.size), shape.in, shape.outArray.toSeq)
              case BalanceModule(shape, waitForDownstreams, _) ⇒
                (Balance.props(effectiveAttributes.settings(settings), shape.outArray.size, waitForDownstreams), shape.in, shape.outArray.toSeq)
              case UnzipModule(shape, _) ⇒
                (Unzip.props(effectiveAttributes.settings(settings)), shape.in, shape.outlets)
            }
            val impl = actorOf(props, stageName(effectiveAttributes), effectiveAttributes.settings(settings).dispatcher)
            val publishers = Vector.tabulate(outs.size)(id ⇒ new ActorPublisher[Any](impl) { // FIXME switch to List.tabulate for inputCount < 8?
              override val wakeUpMsg = FanOut.SubstreamSubscribePending(id)
            })
            impl ! FanOut.ExposedPublishers(publishers)

            publishers.zip(outs).foreach { case (pub, out) ⇒ assignPort(out, pub) }
            val subscriber = ActorSubscriber[Any](impl)
            assignPort(in, subscriber)

        }
      }

    }

    session.materialize().asInstanceOf[Mat]
  }

  def executionContext: ExecutionContext = dispatchers.lookup(settings.dispatcher match {
    case Deploy.NoDispatcherGiven ⇒ Dispatchers.DefaultDispatcherId
    case other                    ⇒ other
  })

  private[akka] def actorOf(props: Props, name: String): ActorRef =
    actorOf(props, name, settings.dispatcher)

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
  def props(settings: MaterializerSettings): Props = Props(new StreamSupervisor(settings))

  final case class Materialize(props: Props, name: String) extends DeadLetterSuppression
}

private[akka] class StreamSupervisor(settings: MaterializerSettings) extends Actor {
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

  def props(materializer: FlowMaterializer, op: StageModule): (Props, Any) = {
    val settings = materializer.settings // USE THIS TO AVOID CLOSING OVER THE MATERIALIZER BELOW
    op match {
      case Identity(_)                ⇒ (ActorInterpreter.props(settings, List(fusing.Map { x: Any ⇒ x })), ())
      case Fused(ops, _)              ⇒ (ActorInterpreter.props(settings, ops), ())
      case Map(f, _)                  ⇒ (ActorInterpreter.props(settings, List(fusing.Map(f))), ())
      case Filter(p, _)               ⇒ (ActorInterpreter.props(settings, List(fusing.Filter(p))), ())
      case Drop(n, _)                 ⇒ (ActorInterpreter.props(settings, List(fusing.Drop(n))), ())
      case Take(n, _)                 ⇒ (ActorInterpreter.props(settings, List(fusing.Take(n))), ())
      case Collect(pf, _)             ⇒ (ActorInterpreter.props(settings, List(fusing.Collect(pf))), ())
      case Scan(z, f, _)              ⇒ (ActorInterpreter.props(settings, List(fusing.Scan(z, f))), ())
      case Expand(s, f, _)            ⇒ (ActorInterpreter.props(settings, List(fusing.Expand(s, f))), ())
      case Conflate(s, f, _)          ⇒ (ActorInterpreter.props(settings, List(fusing.Conflate(s, f))), ())
      case Buffer(n, s, _)            ⇒ (ActorInterpreter.props(settings, List(fusing.Buffer(n, s))), ())
      case MapConcat(f, _)            ⇒ (ActorInterpreter.props(settings, List(fusing.MapConcat(f))), ())
      case MapAsync(f, _)             ⇒ (MapAsyncProcessorImpl.props(settings, f), ())
      case MapAsyncUnordered(f, _)    ⇒ (MapAsyncUnorderedProcessorImpl.props(settings, f), ())
      case Grouped(n, _)              ⇒ (ActorInterpreter.props(settings, List(fusing.Grouped(n))), ())
      case GroupBy(f, _)              ⇒ (GroupByProcessorImpl.props(settings, f), ())
      case PrefixAndTail(n, _)        ⇒ (PrefixAndTailImpl.props(settings, n), ())
      case SplitWhen(p, _)            ⇒ (SplitWhenProcessorImpl.props(settings, p), ())
      case ConcatAll(_)               ⇒ (ConcatAllImpl.props(materializer), ()) //FIXME closes over the materializer, is this good?
      case StageFactory(mkStage, _)   ⇒ (ActorInterpreter.props(settings, List(mkStage())), ())
      case TimerTransform(mkStage, _) ⇒ (TimerTransformerProcessorsImpl.props(settings, mkStage()), ())
      case MaterializingStageFactory(mkStageAndMat, _) ⇒
        val (stage, mat) = mkStageAndMat()
        (ActorInterpreter.props(settings, List(stage)), mat)

    }
  }

  def apply[I, O](impl: ActorRef): ActorProcessor[I, O] = {
    val p = new ActorProcessor[I, O](impl)
    impl ! ExposedPublisher(p.asInstanceOf[ActorPublisher[Any]])
    p
  }
}
