/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl2

import java.util.concurrent.atomic.AtomicLong

import akka.stream.actor.ActorSubscriber

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.{ Future, Await }
import org.reactivestreams.{ Processor, Publisher, Subscriber }
import akka.actor._
import akka.pattern.ask
import akka.stream.{ MaterializerSettings, Transformer }
import akka.stream.impl.{ ActorProcessor, ActorPublisher, ExposedPublisher, TransformProcessorImpl }
import akka.stream.scaladsl2._
import akka.stream.TimerTransformer
import akka.stream.impl.TimerTransformerProcessorsImpl
import akka.stream.OverflowStrategy
import akka.stream.impl.ConflateImpl
import akka.stream.impl.ExpandImpl
import akka.stream.impl.BufferImpl
import akka.stream.impl.BlackholeSubscriber
import akka.stream.impl.MapAsyncProcessorImpl

/**
 * INTERNAL API
 */
private[akka] object Ast {
  sealed trait AstNode {
    def name: String
  }

  case class Transform(name: String, mkTransformer: () ⇒ Transformer[Any, Any]) extends AstNode

  case class TimerTransform(name: String, mkTransformer: () ⇒ TimerTransformer[Any, Any]) extends AstNode

  case class MapAsync(f: Any ⇒ Future[Any]) extends AstNode {
    override def name = "mapAsync"
  }

  case class MapAsyncUnordered(f: Any ⇒ Future[Any]) extends AstNode {
    override def name = "mapAsyncUnordered"
  }

  case class GroupBy(f: Any ⇒ Any) extends AstNode {
    override def name = "groupBy"
  }

  case class PrefixAndTail(n: Int) extends AstNode {
    override def name = "prefixAndTail"
  }

  case class SplitWhen(p: Any ⇒ Boolean) extends AstNode {
    override def name = "splitWhen"
  }

  case object ConcatAll extends AstNode {
    override def name = "concatFlatten"
  }

  case class Conflate(seed: Any ⇒ Any, aggregate: (Any, Any) ⇒ Any) extends AstNode {
    override def name = "conflate"
  }

  case class Expand(seed: Any ⇒ Any, extrapolate: Any ⇒ (Any, Any)) extends AstNode {
    override def name = "expand"
  }

  case class Buffer(size: Int, overflowStrategy: OverflowStrategy) extends AstNode {
    override def name = "buffer"
  }

  sealed trait JunctionAstNode {
    def name: String
  }

  // FIXME: Try to eliminate these
  sealed trait FanInAstNode extends JunctionAstNode
  sealed trait FanOutAstNode extends JunctionAstNode

  case object Merge extends FanInAstNode {
    override def name = "merge"
  }

  case object Broadcast extends FanOutAstNode {
    override def name = "broadcast"
  }

  case object Balance extends FanOutAstNode {
    override def name = "balance"
  }

  case object Zip extends FanInAstNode {
    override def name = "zip"
  }

  case object Unzip extends FanOutAstNode {
    override def name = "unzip"
  }

  case object Concat extends FanInAstNode {
    override def name = "concat"
  }

  case class FlexiMergeNode(merger: FlexiMerge[Any]) extends FanInAstNode {
    override def name = merger.name.getOrElse("")
  }

}

/**
 * INTERNAL API
 */
case class ActorBasedFlowMaterializer(override val settings: MaterializerSettings,
                                      supervisor: ActorRef,
                                      flowNameCounter: AtomicLong,
                                      namePrefix: String)
  extends FlowMaterializer(settings) {

  import Ast.AstNode

  def withNamePrefix(name: String): FlowMaterializer = this.copy(namePrefix = name)

  private def nextFlowNameCount(): Long = flowNameCounter.incrementAndGet()

  private def createFlowName(): String = s"$namePrefix-${nextFlowNameCount()}"

  @tailrec private def processorChain(topSubscriber: Subscriber[_], ops: immutable.Seq[AstNode],
                                      flowName: String, n: Int): Subscriber[_] = {
    ops match {
      case op :: tail ⇒
        val opProcessor: Processor[Any, Any] = processorForNode(op, flowName, n)
        opProcessor.subscribe(topSubscriber.asInstanceOf[Subscriber[Any]])
        processorChain(opProcessor, tail, flowName, n - 1)
      case _ ⇒ topSubscriber
    }
  }

  // Ops come in reverse order
  override def materialize[In, Out](tap: Tap[In], drain: Drain[Out], ops: List[Ast.AstNode]): MaterializedMap = {
    val flowName = createFlowName()

    def attachDrain(pub: Publisher[Out]) = drain match {
      case s: SimpleDrain[Out]  ⇒ s.attach(pub, this, flowName)
      case s: DrainWithKey[Out] ⇒ s.attach(pub, this, flowName)
      case _                    ⇒ throw new MaterializationException("unknown Drain type " + drain.getClass)
    }
    def attachTap(sub: Subscriber[In]) = tap match {
      case s: SimpleTap[In]  ⇒ s.attach(sub, this, flowName)
      case s: TapWithKey[In] ⇒ s.attach(sub, this, flowName)
      case _                 ⇒ throw new MaterializationException("unknown Tap type " + drain.getClass)
    }
    def createDrain() = drain.asInstanceOf[Drain[In]] match {
      case s: SimpleDrain[In]  ⇒ s.create(this, flowName) -> (())
      case s: DrainWithKey[In] ⇒ s.create(this, flowName)
      case _                   ⇒ throw new MaterializationException("unknown Drain type " + drain.getClass)
    }
    def createTap() = tap.asInstanceOf[Tap[Out]] match {
      case s: SimpleTap[Out]  ⇒ s.create(this, flowName) -> (())
      case s: TapWithKey[Out] ⇒ s.create(this, flowName)
      case _                  ⇒ throw new MaterializationException("unknown Tap type " + drain.getClass)
    }
    def isActive(s: AnyRef) = s match {
      case tap: SimpleTap[_]      ⇒ tap.isActive
      case tap: TapWithKey[_]     ⇒ tap.isActive
      case drain: SimpleDrain[_]  ⇒ drain.isActive
      case drain: DrainWithKey[_] ⇒ drain.isActive
      case _: Tap[_]              ⇒ throw new MaterializationException("unknown Tap type " + drain.getClass)
      case _: Drain[_]            ⇒ throw new MaterializationException("unknown Drain type " + drain.getClass)
    }

    val (tapValue, drainValue) =
      if (ops.isEmpty) {
        if (isActive(drain)) {
          val (sub, value) = createDrain()
          (attachTap(sub), value)
        } else if (isActive(tap)) {
          val (pub, value) = createTap()
          (value, attachDrain(pub))
        } else {
          val id: Processor[In, Out] = processorForNode(identityTransform, flowName, 1).asInstanceOf[Processor[In, Out]]
          (attachTap(id), attachDrain(id))
        }
      } else {
        val opsSize = ops.size
        val last = processorForNode(ops.head, flowName, opsSize).asInstanceOf[Processor[Any, Out]]
        val first = processorChain(last, ops.tail, flowName, opsSize - 1).asInstanceOf[Processor[In, Any]]
        (attachTap(first), attachDrain(last))
      }
    new MaterializedPipe(tap, tapValue, drain, drainValue)
  }

  private val identityTransform = Ast.Transform("identity", () ⇒
    new Transformer[Any, Any] {
      override def onNext(element: Any) = List(element)
    })

  /**
   * INTERNAL API
   */
  private[akka] def processorForNode(op: AstNode, flowName: String, n: Int): Processor[Any, Any] = {
    val impl = actorOf(ActorProcessorFactory.props(this, op), s"$flowName-$n-${op.name}")
    ActorProcessorFactory(impl)
  }

  def actorOf(props: Props, name: String): ActorRef = supervisor match {
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
    case _ ⇒
      throw new IllegalStateException(s"Stream supervisor must be a local actor, was [${supervisor.getClass.getName}]")
  }

  override def materializeJunction[In, Out](op: Ast.JunctionAstNode, inputCount: Int, outputCount: Int): (immutable.Seq[Subscriber[In]], immutable.Seq[Publisher[Out]]) = {
    val flowName = createFlowName()
    val actorName = s"$flowName-${op.name}"

    op match {
      case fanin: Ast.FanInAstNode ⇒
        val impl = op match {
          case Ast.Merge ⇒
            actorOf(FairMerge.props(settings, inputCount).withDispatcher(settings.dispatcher), actorName)
          case Ast.Zip ⇒
            actorOf(Zip.props(settings).withDispatcher(settings.dispatcher), actorName)
          case Ast.Concat ⇒
            actorOf(Concat.props(settings).withDispatcher(settings.dispatcher), actorName)
        }

        val publisher = new ActorPublisher[Out](impl, equalityValue = None)
        impl ! ExposedPublisher(publisher.asInstanceOf[ActorPublisher[Any]])
        val subscribers = Vector.tabulate(inputCount)(FanIn.SubInput[In](impl, _))
        (subscribers, List(publisher))

      case fanout: Ast.FanOutAstNode ⇒
        val impl = op match {
          case Ast.Broadcast ⇒
            actorOf(Broadcast.props(settings, outputCount).withDispatcher(settings.dispatcher), actorName)
          case Ast.Balance ⇒
            actorOf(Balance.props(settings, outputCount).withDispatcher(settings.dispatcher), actorName)
          case Ast.Unzip ⇒
            actorOf(Unzip.props(settings).withDispatcher(settings.dispatcher), actorName)
        }

        val publishers = Vector.tabulate(outputCount)(id ⇒ new ActorPublisher[Out](impl, equalityValue = None) {
          override val wakeUpMsg = FanOut.SubstreamSubscribePending(id)
        })
        impl ! FanOut.ExposedPublishers(publishers.asInstanceOf[immutable.Seq[ActorPublisher[Any]]])
        val subscriber = ActorSubscriber[In](impl)
        (List(subscriber), publishers)
    }

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

  case class Materialize(props: Props, name: String)
}

private[akka] class StreamSupervisor(settings: MaterializerSettings) extends Actor {
  import StreamSupervisor._

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

  import Ast._
  def props(materializer: FlowMaterializer, op: AstNode): Props = {
    val settings = materializer.settings
    (op match {
      case t: Transform         ⇒ Props(new TransformProcessorImpl(settings, t.mkTransformer()))
      case t: TimerTransform    ⇒ Props(new TimerTransformerProcessorsImpl(settings, t.mkTransformer()))
      case m: MapAsync          ⇒ Props(new MapAsyncProcessorImpl(settings, m.f))
      case m: MapAsyncUnordered ⇒ Props(new MapAsyncUnorderedProcessorImpl(settings, m.f))
      case g: GroupBy           ⇒ Props(new GroupByProcessorImpl(settings, g.f))
      case tt: PrefixAndTail    ⇒ Props(new PrefixAndTailImpl(settings, tt.n))
      case s: SplitWhen         ⇒ Props(new SplitWhenProcessorImpl(settings, s.p))
      case ConcatAll            ⇒ Props(new ConcatAllImpl(materializer))
      case cf: Conflate         ⇒ Props(new ConflateImpl(settings, cf.seed, cf.aggregate))
      case ex: Expand           ⇒ Props(new ExpandImpl(settings, ex.seed, ex.extrapolate))
      case bf: Buffer           ⇒ Props(new BufferImpl(settings, bf.size, bf.overflowStrategy))
    }).withDispatcher(settings.dispatcher)
  }

  def apply[I, O](impl: ActorRef): ActorProcessor[I, O] = {
    val p = new ActorProcessor[I, O](impl)
    impl ! ExposedPublisher(p.asInstanceOf[ActorPublisher[Any]])
    p
  }
}
