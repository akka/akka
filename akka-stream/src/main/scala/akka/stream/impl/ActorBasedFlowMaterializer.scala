/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicLong

import akka.dispatch.Dispatchers
import akka.event.Logging
import akka.stream.impl.fusing.ActorInterpreter
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Await, Future }
import akka.actor._
import akka.stream.{ FlowMaterializer, MaterializerSettings, OverflowStrategy, TimerTransformer }
import akka.stream.MaterializationException
import akka.stream.actor.ActorSubscriber
import akka.stream.impl.Zip.ZipAs
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.pattern.ask
import org.reactivestreams.{ Processor, Publisher, Subscriber }

/**
 * INTERNAL API
 */
private[akka] object Ast {
  sealed abstract class AstNode {
    def name: String
  }

  final case class TimerTransform(mkStage: () ⇒ TimerTransformer[Any, Any], override val name: String) extends AstNode

  final case class StageFactory(mkStage: () ⇒ Stage[_, _], override val name: String) extends AstNode

  object Fused {
    def apply(ops: immutable.Seq[Stage[_, _]]): Fused =
      Fused(ops, ops.map(x ⇒ Logging.simpleName(x).toLowerCase).mkString("+")) //FIXME change to something more performant for name
  }
  final case class Fused(ops: immutable.Seq[Stage[_, _]], override val name: String) extends AstNode

  final case class Map(f: Any ⇒ Any) extends AstNode { override def name = "map" }

  final case class Filter(p: Any ⇒ Boolean) extends AstNode { override def name = "filter" }

  final case class Collect(pf: PartialFunction[Any, Any]) extends AstNode { override def name = "collect" }

  // FIXME Replace with OperateAsync
  final case class MapAsync(f: Any ⇒ Future[Any]) extends AstNode { override def name = "mapAsync" }

  //FIXME Should be OperateUnorderedAsync
  final case class MapAsyncUnordered(f: Any ⇒ Future[Any]) extends AstNode { override def name = "mapAsyncUnordered" }

  final case class Grouped(n: Int) extends AstNode {
    require(n > 0, "n must be greater than 0")
    override def name = "grouped"
  }

  //FIXME should be `n: Long`
  final case class Take(n: Int) extends AstNode {
    override def name = "take"
  }

  //FIXME should be `n: Long`
  final case class Drop(n: Int) extends AstNode {
    override def name = "drop"
  }

  final case class Scan(zero: Any, f: (Any, Any) ⇒ Any) extends AstNode { override def name = "scan" }

  final case class Buffer(size: Int, overflowStrategy: OverflowStrategy) extends AstNode {
    require(size > 0, s"Buffer size must be larger than zero but was [$size]")
    override def name = "buffer"
  }
  final case class Conflate(seed: Any ⇒ Any, aggregate: (Any, Any) ⇒ Any) extends AstNode {
    override def name = "conflate"
  }
  final case class Expand(seed: Any ⇒ Any, extrapolate: Any ⇒ (Any, Any)) extends AstNode {
    override def name = "expand"
  }
  final case class MapConcat(f: Any ⇒ immutable.Seq[Any]) extends AstNode {
    override def name = "mapConcat"
  }

  final case class GroupBy(f: Any ⇒ Any) extends AstNode { override def name = "groupBy" }

  final case class PrefixAndTail(n: Int) extends AstNode { override def name = "prefixAndTail" }

  final case class SplitWhen(p: Any ⇒ Boolean) extends AstNode { override def name = "splitWhen" }

  final case object ConcatAll extends AstNode {
    override def name = "concatFlatten"
  }

  sealed trait JunctionAstNode {
    def name: String
  }

  // FIXME: Try to eliminate these
  sealed trait FanInAstNode extends JunctionAstNode
  sealed trait FanOutAstNode extends JunctionAstNode

  // FIXME Why do we need this?
  case object IdentityAstNode extends JunctionAstNode {
    override def name = "identity"
  }

  case object Merge extends FanInAstNode {
    override def name = "merge"
  }

  case object MergePreferred extends FanInAstNode {
    override def name = "mergePreferred"
  }

  case object Broadcast extends FanOutAstNode {
    override def name = "broadcast"
  }

  case class Balance(waitForAllDownstreams: Boolean) extends FanOutAstNode {
    override def name = "balance"
  }

  final case class Zip(as: ZipAs) extends FanInAstNode {
    override def name = "zip"
  }

  case object Unzip extends FanOutAstNode {
    override def name = "unzip"
  }

  case object Concat extends FanInAstNode {
    override def name = "concat"
  }

  case class FlexiMergeNode(factory: FlexiMergeImpl.MergeLogicFactory[Any]) extends FanInAstNode {
    override def name = factory.name.getOrElse("flexiMerge")
  }

  case class FlexiRouteNode(factory: FlexiRouteImpl.RouteLogicFactory[Any]) extends FanOutAstNode {
    override def name = factory.name.getOrElse("flexiRoute")
  }

}

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

  import Ast.AstNode

  def withNamePrefix(name: String): FlowMaterializer = this.copy(namePrefix = name)

  private[this] def nextFlowNameCount(): Long = flowNameCounter.incrementAndGet()

  private[this] def createFlowName(): String = s"$namePrefix-${nextFlowNameCount()}"

  @tailrec private[this] def processorChain(topProcessor: Processor[_, _],
                                            ops: List[AstNode],
                                            flowName: String,
                                            n: Int): Processor[_, _] =
    ops match {
      case op :: tail ⇒
        val opProcessor = processorForNode[Any, Any](op, flowName, n)
        opProcessor.subscribe(topProcessor.asInstanceOf[Subscriber[Any]])
        processorChain(opProcessor, tail, flowName, n - 1)
      case Nil ⇒
        topProcessor
    }

  //FIXME Optimize the implementation of the optimizer (no joke)
  // AstNodes are in reverse order, Fusable Ops are in order
  private[this] final def optimize(ops: List[Ast.AstNode]): (List[Ast.AstNode], Int) = {
    @tailrec def analyze(rest: List[Ast.AstNode], optimized: List[Ast.AstNode], fuseCandidates: List[Stage[_, _]]): (List[Ast.AstNode], Int) = {

      //The `verify` phase
      def verify(rest: List[Ast.AstNode], orig: List[Ast.AstNode]): List[Ast.AstNode] =
        rest match {
          case (f: Ast.Fused) :: _ ⇒ throw new IllegalStateException("Fused AST nodes not allowed to be present in the input to the optimizer: " + f)
          //TODO Ast.Take(-Long.MaxValue..0) == stream doesn't do anything. Perhaps output warning for that?
          case noMatch             ⇒ noMatch
        }

      // The `elide` phase
      // TODO / FIXME : This phase could be pulled out to be executed incrementally when building the Ast
      def elide(rest: List[Ast.AstNode], orig: List[Ast.AstNode]): List[Ast.AstNode] =
        rest match {
          case noMatch if !optimizations.elision || (noMatch ne orig) ⇒ orig
          //Collapses consecutive Take's into one
          case (t1 @ Ast.Take(t1n)) :: (t2 @ Ast.Take(t2n)) :: rest ⇒ (if (t1n < t2n) t1 else t2) :: rest

          //Collapses consecutive Drop's into one
          case (d1 @ Ast.Drop(d1n)) :: (d2 @ Ast.Drop(d2n)) :: rest ⇒ new Ast.Drop(d1n + d2n) :: rest

          case Ast.Drop(n) :: rest if n < 1 ⇒ rest // a 0 or negative drop is a NoOp

          case noMatch ⇒ noMatch
        }
      // The `simplify` phase
      def simplify(rest: List[Ast.AstNode], orig: List[Ast.AstNode]): List[Ast.AstNode] =
        rest match {
          case noMatch if !optimizations.simplification || (noMatch ne orig) ⇒ orig

          // Two consecutive maps is equivalent to one pipelined map
          case Ast.Map(second) :: Ast.Map(first) :: rest ⇒ Ast.Map(first andThen second) :: rest

          case noMatch ⇒ noMatch
        }

      // the `Collapse` phase
      def collapse(rest: List[Ast.AstNode], orig: List[Ast.AstNode]): List[Ast.AstNode] =
        rest match {
          case noMatch if !optimizations.collapsing || (noMatch ne orig) ⇒ orig

          // Collapses a filter and a map into a collect
          case Ast.Map(f) :: Ast.Filter(p) :: rest ⇒ Ast.Collect({ case i if p(i) ⇒ f(i) }) :: rest

          case noMatch ⇒ noMatch
        }

      // Tries to squeeze AstNode into a single fused pipeline
      def ast2op(head: Ast.AstNode, prev: List[Stage[_, _]]): List[Stage[_, _]] =
        head match {
          // Always-on below
          case Ast.StageFactory(mkStage, _)     ⇒ mkStage() :: prev

          // Optimizations below
          case noMatch if !optimizations.fusion ⇒ prev

          case Ast.Map(f)                       ⇒ fusing.Map(f) :: prev
          case Ast.Filter(p)                    ⇒ fusing.Filter(p) :: prev
          case Ast.Drop(n)                      ⇒ fusing.Drop(n) :: prev
          case Ast.Take(n)                      ⇒ fusing.Take(n) :: prev
          case Ast.Collect(pf)                  ⇒ fusing.Collect(pf) :: prev
          case Ast.Scan(z, f)                   ⇒ fusing.Scan(z, f) :: prev
          case Ast.Expand(s, f)                 ⇒ fusing.Expand(s, f) :: prev
          case Ast.Conflate(s, f)               ⇒ fusing.Conflate(s, f) :: prev
          case Ast.Buffer(n, s)                 ⇒ fusing.Buffer(n, s) :: prev
          case Ast.MapConcat(f)                 ⇒ fusing.MapConcat(f) :: prev
          case Ast.Grouped(n)                   ⇒ fusing.Grouped(n) :: prev
          //FIXME Add more fusion goodies here
          case _                                ⇒ prev
        }

      // First verify, then try to elide, then try to simplify, then try to fuse
      collapse(rest, simplify(rest, elide(rest, verify(rest, rest)))) match {

        case Nil ⇒
          if (fuseCandidates.isEmpty) (optimized.reverse, optimized.length) // End of optimization run without fusion going on, wrap up
          else ((Ast.Fused(fuseCandidates) :: optimized).reverse, optimized.length + 1) // End of optimization run with fusion going on, so add it to the optimized stack

        // If the Ast was changed this pass simply recur
        case modified if modified ne rest ⇒ analyze(modified, optimized, fuseCandidates)

        // No changes to the Ast, lets try to see if we can squeeze the current head Ast node into a fusion pipeline
        case head :: rest ⇒
          ast2op(head, fuseCandidates) match {
            case Nil                      ⇒ analyze(rest, head :: optimized, Nil)
            case l if l eq fuseCandidates ⇒ analyze(rest, head :: Ast.Fused(fuseCandidates) :: optimized, Nil)
            case newFuseCandidates        ⇒ analyze(rest, optimized, newFuseCandidates)
          }
      }
    }
    val result = analyze(ops, Nil, Nil)
    //println(s"before: $ops")
    //println(s"after: ${result._1}")
    result
  }

  // Ops come in reverse order
  override def materialize[In, Out](source: Source[In], sink: Sink[Out], rawOps: List[Ast.AstNode]): MaterializedMap = {
    val flowName = createFlowName() //FIXME: Creates Id even when it is not used in all branches below

    def throwUnknownType(typeName: String, s: AnyRef): Nothing =
      throw new MaterializationException(s"unknown $typeName type ${s.getClass}")

    def attachSink(pub: Publisher[Out], flowName: String) = sink match {
      case s: ActorFlowSink[Out] ⇒ s.attach(pub, this, flowName)
      case s                     ⇒ throwUnknownType("Sink", s)
    }
    def attachSource(sub: Subscriber[In], flowName: String) = source match {
      case s: ActorFlowSource[In] ⇒ s.attach(sub, this, flowName)
      case s                      ⇒ throwUnknownType("Source", s)
    }
    def createSink(flowName: String) = sink match {
      case s: ActorFlowSink[In] ⇒ s.create(this, flowName)
      case s                    ⇒ throwUnknownType("Sink", s)
    }
    def createSource(flowName: String) = source match {
      case s: ActorFlowSource[Out] ⇒ s.create(this, flowName)
      case s                       ⇒ throwUnknownType("Source", s)
    }
    def isActive(s: AnyRef) = s match {
      case s: ActorFlowSource[_] ⇒ s.isActive
      case s: ActorFlowSink[_]   ⇒ s.isActive
      case s: Source[_]          ⇒ throwUnknownType("Source", s)
      case s: Sink[_]            ⇒ throwUnknownType("Sink", s)
    }

    val (sourceValue, sinkValue) =
      if (rawOps.isEmpty) {
        if (isActive(sink)) {
          val (sub, value) = createSink(flowName)
          (attachSource(sub, flowName), value)
        } else if (isActive(source)) {
          val (pub, value) = createSource(flowName)
          (value, attachSink(pub, flowName))
        } else {
          val id = processorForNode[In, Out](identityStageNode, flowName, 1)
          (attachSource(id, flowName), attachSink(id, flowName))
        }
      } else {
        val (ops, opsSize) = if (optimizations.isEnabled) optimize(rawOps) else (rawOps, rawOps.length)
        val last = processorForNode[Any, Out](ops.head, flowName, opsSize)
        val first = processorChain(last, ops.tail, flowName, opsSize - 1).asInstanceOf[Processor[In, Any]]
        (attachSource(first, flowName), attachSink(last, flowName))
      }
    new MaterializedPipe(source, sourceValue, sink, sinkValue)
  }
  //FIXME Should this be a dedicated AstNode?
  private[this] val identityStageNode = Ast.StageFactory(() ⇒ FlowOps.identityStage[Any], "identity")

  def executionContext: ExecutionContext = dispatchers.lookup(settings.dispatcher match {
    case Deploy.NoDispatcherGiven ⇒ Dispatchers.DefaultDispatcherId
    case other                    ⇒ other
  })

  /**
   * INTERNAL API
   */
  private[akka] def processorForNode[In, Out](op: AstNode, flowName: String, n: Int): Processor[In, Out] =
    ActorProcessorFactory[In, Out](actorOf(ActorProcessorFactory.props(this, op), s"$flowName-$n-${op.name}"))

  def actorOf(props: Props, name: String): ActorRef = supervisor match {
    case ref: LocalActorRef ⇒
      ref.underlying.attachChild(props.withDispatcher(settings.dispatcher), name, systemService = false)
    case ref: RepointableActorRef ⇒
      if (ref.isStarted)
        ref.underlying.asInstanceOf[ActorCell].attachChild(props.withDispatcher(settings.dispatcher), name, systemService = false)
      else {
        implicit val timeout = ref.system.settings.CreationTimeout
        val f = (supervisor ? StreamSupervisor.Materialize(props.withDispatcher(settings.dispatcher), name)).mapTo[ActorRef]
        Await.result(f, timeout.duration)
      }
    case unknown ⇒
      throw new IllegalStateException(s"Stream supervisor must be a local actor, was [${unknown.getClass.getName}]")
  }
  // FIXME Investigate possibility of using `enableOperationsFusion` in `materializeJunction`
  override def materializeJunction[In, Out](op: Ast.JunctionAstNode, inputCount: Int, outputCount: Int): (immutable.Seq[Subscriber[In]], immutable.Seq[Publisher[Out]]) = {
    val actorName = s"${createFlowName()}-${op.name}"

    op match {
      case fanin: Ast.FanInAstNode ⇒
        val impl = fanin match {
          case Ast.Merge                  ⇒ actorOf(FairMerge.props(settings, inputCount), actorName)
          case Ast.MergePreferred         ⇒ actorOf(UnfairMerge.props(settings, inputCount), actorName)
          case zip: Ast.Zip               ⇒ actorOf(Zip.props(settings, zip.as), actorName)
          case Ast.Concat                 ⇒ actorOf(Concat.props(settings), actorName)
          case Ast.FlexiMergeNode(merger) ⇒ actorOf(FlexiMergeImpl.props(settings, inputCount, merger.createMergeLogic()), actorName)
        }

        val publisher = new ActorPublisher[Out](impl)
        impl ! ExposedPublisher(publisher.asInstanceOf[ActorPublisher[Any]])
        val subscribers = Vector.tabulate(inputCount)(FanIn.SubInput[In](impl, _))
        (subscribers, List(publisher))

      case fanout: Ast.FanOutAstNode ⇒
        val impl = fanout match {
          case Ast.Broadcast                      ⇒ actorOf(Broadcast.props(settings, outputCount), actorName)
          case Ast.Balance(waitForAllDownstreams) ⇒ actorOf(Balance.props(settings, outputCount, waitForAllDownstreams), actorName)
          case Ast.Unzip                          ⇒ actorOf(Unzip.props(settings), actorName)
          case Ast.FlexiRouteNode(route)          ⇒ actorOf(FlexiRouteImpl.props(settings, outputCount, route.createRouteLogic()), actorName)
        }

        val publishers = Vector.tabulate(outputCount)(id ⇒ new ActorPublisher[Out](impl) {
          override val wakeUpMsg = FanOut.SubstreamSubscribePending(id)
        })
        impl ! FanOut.ExposedPublishers(publishers.asInstanceOf[immutable.Seq[ActorPublisher[Any]]])
        val subscriber = ActorSubscriber[In](impl)
        (List(subscriber), publishers)

      case identity @ Ast.IdentityAstNode ⇒ // FIXME Why is IdentityAstNode a JunctionAStNode?
        val id = List(processorForNode[In, Out](identityStageNode, identity.name, 1)) // FIXME is `identity.name` appropriate/unique here?
        (id, id)
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
    val settings = materializer.settings // USE THIS TO AVOID CLOSING OVER THE MATERIALIZER BELOW
    (op match {
      case Fused(ops, _)              ⇒ ActorInterpreter.props(settings, ops)
      case Map(f)                     ⇒ ActorInterpreter.props(settings, List(fusing.Map(f)))
      case Filter(p)                  ⇒ ActorInterpreter.props(settings, List(fusing.Filter(p)))
      case Drop(n)                    ⇒ ActorInterpreter.props(settings, List(fusing.Drop(n)))
      case Take(n)                    ⇒ ActorInterpreter.props(settings, List(fusing.Take(n)))
      case Collect(pf)                ⇒ ActorInterpreter.props(settings, List(fusing.Collect(pf)))
      case Scan(z, f)                 ⇒ ActorInterpreter.props(settings, List(fusing.Scan(z, f)))
      case Expand(s, f)               ⇒ ActorInterpreter.props(settings, List(fusing.Expand(s, f)))
      case Conflate(s, f)             ⇒ ActorInterpreter.props(settings, List(fusing.Conflate(s, f)))
      case Buffer(n, s)               ⇒ ActorInterpreter.props(settings, List(fusing.Buffer(n, s)))
      case MapConcat(f)               ⇒ ActorInterpreter.props(settings, List(fusing.MapConcat(f)))
      case MapAsync(f)                ⇒ MapAsyncProcessorImpl.props(settings, f)
      case MapAsyncUnordered(f)       ⇒ MapAsyncUnorderedProcessorImpl.props(settings, f)
      case Grouped(n)                 ⇒ ActorInterpreter.props(settings, List(fusing.Grouped(n)))
      case GroupBy(f)                 ⇒ GroupByProcessorImpl.props(settings, f)
      case PrefixAndTail(n)           ⇒ PrefixAndTailImpl.props(settings, n)
      case SplitWhen(p)               ⇒ SplitWhenProcessorImpl.props(settings, p)
      case ConcatAll                  ⇒ ConcatAllImpl.props(materializer) //FIXME closes over the materializer, is this good?
      case StageFactory(mkStage, _)   ⇒ ActorInterpreter.props(settings, List(mkStage()))
      case TimerTransform(mkStage, _) ⇒ TimerTransformerProcessorsImpl.props(settings, mkStage())
    }).withDispatcher(settings.dispatcher)
  }

  def apply[I, O](impl: ActorRef): ActorProcessor[I, O] = {
    val p = new ActorProcessor[I, O](impl)
    impl ! ExposedPublisher(p.asInstanceOf[ActorPublisher[Any]])
    p
  }
}
