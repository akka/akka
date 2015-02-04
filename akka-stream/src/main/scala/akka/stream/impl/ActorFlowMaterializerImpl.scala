/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util.concurrent.atomic.AtomicLong
import akka.dispatch.Dispatchers
import akka.event.Logging
import akka.stream.impl.fusing.ActorInterpreter
import akka.stream.scaladsl.OperationAttributes._
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.{ Promise, ExecutionContext, Await, Future }
import akka.actor._
import akka.stream.{ ActorFlowMaterializer, ActorFlowMaterializerSettings, OverflowStrategy, TimerTransformer }
import akka.stream.MaterializationException
import akka.stream.actor.ActorSubscriber
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.pattern.ask
import org.reactivestreams.{ Processor, Publisher, Subscriber }
import akka.stream.Optimizations

// FIXME move Ast things to separate file

/**
 * INTERNAL API
 */
private[akka] object Ast {

  sealed abstract class AstNode {
    def attributes: OperationAttributes
    def withAttributes(attributes: OperationAttributes): AstNode
  }
  // FIXME Fix the name `Defaults` is waaaay too opaque. How about "Names"?
  object Defaults {
    val timerTransform = name("timerTransform")
    val stageFactory = name("stageFactory")
    val fused = name("fused")
    val map = name("map")
    val filter = name("filter")
    val collect = name("collect")
    val mapAsync = name("mapAsync")
    val mapAsyncUnordered = name("mapAsyncUnordered")
    val grouped = name("grouped")
    val take = name("take")
    val drop = name("drop")
    val scan = name("scan")
    val buffer = name("buffer")
    val conflate = name("conflate")
    val expand = name("expand")
    val mapConcat = name("mapConcat")
    val groupBy = name("groupBy")
    val prefixAndTail = name("prefixAndTail")
    val splitWhen = name("splitWhen")
    val concatAll = name("concatAll")
    val processor = name("processor")
    val processorWithKey = name("processorWithKey")
    val identityOp = name("identityOp")

    val merge = name("merge")
    val mergePreferred = name("mergePreferred")
    val broadcast = name("broadcast")
    val balance = name("balance")
    val zip = name("zip")
    val unzip = name("unzip")
    val concat = name("concat")
    val flexiMerge = name("flexiMerge")
    val flexiRoute = name("flexiRoute")
    val identityJunction = name("identityJunction")
  }

  import Defaults._

  final case class TimerTransform(mkStage: () ⇒ TimerTransformer[Any, Any], attributes: OperationAttributes = timerTransform) extends AstNode {
    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }

  final case class StageFactory(mkStage: () ⇒ Stage[_, _], attributes: OperationAttributes = stageFactory) extends AstNode {
    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }

  object Fused {
    def apply(ops: immutable.Seq[Stage[_, _]]): Fused =
      Fused(ops, name(ops.map(x ⇒ Logging.simpleName(x).toLowerCase).mkString("+"))) //FIXME change to something more performant for name
  }
  final case class Fused(ops: immutable.Seq[Stage[_, _]], attributes: OperationAttributes = fused) extends AstNode {
    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }

  final case class Map(f: Any ⇒ Any, attributes: OperationAttributes = map) extends AstNode {
    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }

  final case class Filter(p: Any ⇒ Boolean, attributes: OperationAttributes = filter) extends AstNode {
    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }

  final case class Collect(pf: PartialFunction[Any, Any], attributes: OperationAttributes = collect) extends AstNode {
    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }

  // FIXME Replace with OperateAsync
  final case class MapAsync(f: Any ⇒ Future[Any], attributes: OperationAttributes = mapAsync) extends AstNode {
    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }

  //FIXME Should be OperateUnorderedAsync
  final case class MapAsyncUnordered(f: Any ⇒ Future[Any], attributes: OperationAttributes = mapAsyncUnordered) extends AstNode {
    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }

  final case class Grouped(n: Int, attributes: OperationAttributes = grouped) extends AstNode {
    require(n > 0, "n must be greater than 0")

    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }

  //FIXME should be `n: Long`
  final case class Take(n: Int, attributes: OperationAttributes = take) extends AstNode {
    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }

  //FIXME should be `n: Long`
  final case class Drop(n: Int, attributes: OperationAttributes = drop) extends AstNode {
    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }

  final case class Scan(zero: Any, f: (Any, Any) ⇒ Any, attributes: OperationAttributes = scan) extends AstNode {
    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }

  final case class Buffer(size: Int, overflowStrategy: OverflowStrategy, attributes: OperationAttributes = buffer) extends AstNode {
    require(size > 0, s"Buffer size must be larger than zero but was [$size]")

    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }
  final case class Conflate(seed: Any ⇒ Any, aggregate: (Any, Any) ⇒ Any, attributes: OperationAttributes = conflate) extends AstNode {
    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }
  final case class Expand(seed: Any ⇒ Any, extrapolate: Any ⇒ (Any, Any), attributes: OperationAttributes = expand) extends AstNode {
    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }
  final case class MapConcat(f: Any ⇒ immutable.Seq[Any], attributes: OperationAttributes = mapConcat) extends AstNode {
    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }

  final case class GroupBy(f: Any ⇒ Any, attributes: OperationAttributes = groupBy) extends AstNode {
    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }

  final case class PrefixAndTail(n: Int, attributes: OperationAttributes = prefixAndTail) extends AstNode {
    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }

  final case class SplitWhen(p: Any ⇒ Boolean, attributes: OperationAttributes = splitWhen) extends AstNode {
    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }

  final case class ConcatAll(attributes: OperationAttributes = concatAll) extends AstNode {
    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }

  final case class DirectProcessor(p: () ⇒ Processor[Any, Any], attributes: OperationAttributes = processor) extends AstNode {
    override def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }

  final case class DirectProcessorWithKey(p: () ⇒ (Processor[Any, Any], Any), key: Key[_], attributes: OperationAttributes = processorWithKey) extends AstNode {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
  }

  sealed trait JunctionAstNode {
    def attributes: OperationAttributes
  }

  // FIXME: Try to eliminate these
  sealed trait FanInAstNode extends JunctionAstNode
  sealed trait FanOutAstNode extends JunctionAstNode

  /**
   * INTERNAL API
   * `f` MUST be implemented as value of type `scala.FunctionN`
   */
  sealed trait ZipWith extends FanInAstNode {
    /** MUST be implemented as type of FunctionN */
    def f: Any
  }
  final case class Zip2With[T1, T2](f: Function2[T1, T2, Any], attributes: OperationAttributes) extends ZipWith
  final case class Zip3With[T1, T2, T3](f: Function3[T1, T2, T3, Any], attributes: OperationAttributes) extends ZipWith
  final case class Zip4With[T1, T2, T3, T4](f: Function4[T1, T2, T3, T4, Any], attributes: OperationAttributes) extends ZipWith
  final case class Zip5With[T1, T2, T3, T4, T5](f: Function5[T1, T2, T3, T4, T5, Any], attributes: OperationAttributes) extends ZipWith
  final case class Zip6With[T1, T2, T3, T4, T5, T6](f: Function6[T1, T2, T3, T4, T5, T6, Any], attributes: OperationAttributes) extends ZipWith
  final case class Zip7With[T1, T2, T3, T4, T5, T6, T7](f: Function7[T1, T2, T3, T4, T5, T6, T7, Any], attributes: OperationAttributes) extends ZipWith
  final case class Zip8With[T1, T2, T3, T4, T5, T6, T7, T8](f: Function8[T1, T2, T3, T4, T5, T6, T7, T8, Any], attributes: OperationAttributes) extends ZipWith
  final case class Zip9With[T1, T2, T3, T4, T5, T6, T7, T8, T9](f: Function9[T1, T2, T3, T4, T5, T6, T7, T8, T9, Any], attributes: OperationAttributes) extends ZipWith
  final case class Zip10With[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10](f: Function10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, Any], attributes: OperationAttributes) extends ZipWith
  final case class Zip11With[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11](f: Function11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, Any], attributes: OperationAttributes) extends ZipWith
  final case class Zip12With[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12](f: Function12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, Any], attributes: OperationAttributes) extends ZipWith
  final case class Zip13With[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13](f: Function13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, Any], attributes: OperationAttributes) extends ZipWith
  final case class Zip14With[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14](f: Function14[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, Any], attributes: OperationAttributes) extends ZipWith
  final case class Zip15With[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15](f: Function15[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, Any], attributes: OperationAttributes) extends ZipWith
  final case class Zip16With[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16](f: Function16[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, Any], attributes: OperationAttributes) extends ZipWith
  final case class Zip17With[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17](f: Function17[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, Any], attributes: OperationAttributes) extends ZipWith
  final case class Zip18With[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18](f: Function18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, Any], attributes: OperationAttributes) extends ZipWith
  final case class Zip19With[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19](f: Function19[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, Any], attributes: OperationAttributes) extends ZipWith
  final case class Zip20With[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20](f: Function20[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, Any], attributes: OperationAttributes) extends ZipWith
  final case class Zip21With[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21](f: Function21[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, Any], attributes: OperationAttributes) extends ZipWith
  final case class Zip22With[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22](f: Function22[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22, Any], attributes: OperationAttributes) extends ZipWith

  // FIXME Why do we need this?
  final case class IdentityAstNode(attributes: OperationAttributes) extends JunctionAstNode

  final case class Merge(attributes: OperationAttributes) extends FanInAstNode
  final case class MergePreferred(attributes: OperationAttributes) extends FanInAstNode

  final case class Broadcast(attributes: OperationAttributes) extends FanOutAstNode
  final case class Balance(waitForAllDownstreams: Boolean, attributes: OperationAttributes) extends FanOutAstNode

  final case class Unzip(attributes: OperationAttributes) extends FanOutAstNode

  final case class Concat(attributes: OperationAttributes) extends FanInAstNode

  final case class FlexiMergeNode(factory: FlexiMergeImpl.MergeLogicFactory[Any], attributes: OperationAttributes) extends FanInAstNode
  final case class FlexiRouteNode(factory: FlexiRouteImpl.RouteLogicFactory[Any], attributes: OperationAttributes) extends FanOutAstNode
}

case class ActorFlowMaterializerImpl(
  override val settings: ActorFlowMaterializerSettings,
  dispatchers: Dispatchers, // FIXME is this the right choice for loading an EC?
  supervisor: ActorRef,
  flowNameCounter: AtomicLong,
  namePrefix: String)
  extends ActorFlowMaterializer {

  import Ast.AstNode

  override def withNamePrefix(name: String): ActorFlowMaterializerImpl = this.copy(namePrefix = name)

  private[this] def nextFlowNameCount(): Long = flowNameCounter.incrementAndGet()

  private[this] def createFlowName(): String = s"$namePrefix-${nextFlowNameCount()}"

  @tailrec private[this] def processorChain(topProcessor: Processor[_, _],
                                            ops: List[AstNode],
                                            flowName: String,
                                            n: Int,
                                            materializedMap: MaterializedMap): (Processor[_, _], MaterializedMap) =
    ops match {
      case op :: tail ⇒
        val (opProcessor, opMap) = processorForNode[Any, Any](op, flowName, n)
        opProcessor.subscribe(topProcessor.asInstanceOf[Subscriber[Any]])
        processorChain(opProcessor, tail, flowName, n - 1, materializedMap.merge(opMap))
      case Nil ⇒
        (topProcessor, materializedMap)
    }

  //FIXME Optimize the implementation of the optimizer (no joke)
  // AstNodes are in reverse order, Fusable Ops are in order
  private[this] final def optimize(ops: List[Ast.AstNode], mmFuture: Future[MaterializedMap]): (List[Ast.AstNode], Int) = {
    import settings.optimizations

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
          case (t1: Ast.Take) :: (t2: Ast.Take) :: rest ⇒ (if (t1.n < t2.n) t1 else t2) :: rest

          //Collapses consecutive Drop's into one
          case (d1: Ast.Drop) :: (d2: Ast.Drop) :: rest ⇒ new Ast.Drop(d1.n + d2.n, d1.attributes and d2.attributes) :: rest

          case Ast.Drop(n, _) :: rest if n < 1 ⇒ rest // a 0 or negative drop is a NoOp

          case noMatch ⇒ noMatch
        }
      // The `simplify` phase
      def simplify(rest: List[Ast.AstNode], orig: List[Ast.AstNode]): List[Ast.AstNode] =
        rest match {
          case noMatch if !optimizations.simplification || (noMatch ne orig) ⇒ orig

          // Two consecutive maps is equivalent to one pipelined map
          case Ast.Map(second, secondAttributes) :: Ast.Map(first, firstAttributes) :: rest ⇒
            Ast.Map(first andThen second, firstAttributes and secondAttributes) :: rest

          case noMatch ⇒ noMatch
        }

      // the `Collapse` phase
      def collapse(rest: List[Ast.AstNode], orig: List[Ast.AstNode]): List[Ast.AstNode] =
        rest match {
          case noMatch if !optimizations.collapsing || (noMatch ne orig) ⇒ orig

          // Collapses a filter and a map into a collect
          case Ast.Map(mapFn, mapAttributes) :: Ast.Filter(filFn, filAttributes) :: rest ⇒
            Ast.Collect({ case i if filFn(i) ⇒ mapFn(i) }, filAttributes and mapAttributes) :: rest

          case noMatch ⇒ noMatch
        }

      // Tries to squeeze AstNode into a single fused pipeline
      def ast2op(head: Ast.AstNode, prev: List[Stage[_, _]]): List[Stage[_, _]] =
        head match {
          // Always-on below
          case Ast.StageFactory(mkStage, _)     ⇒ mkStage() :: prev

          // Optimizations below
          case noMatch if !optimizations.fusion ⇒ prev

          case Ast.Map(f, att)                  ⇒ fusing.Map(f, att.settings(settings).supervisionDecider) :: prev
          case Ast.Filter(p, att)               ⇒ fusing.Filter(p, att.settings(settings).supervisionDecider) :: prev
          case Ast.Drop(n, _)                   ⇒ fusing.Drop(n) :: prev
          case Ast.Take(n, _)                   ⇒ fusing.Take(n) :: prev
          case Ast.Collect(pf, att)             ⇒ fusing.Collect(att.settings(settings).supervisionDecider)(pf) :: prev
          case Ast.Scan(z, f, att)              ⇒ fusing.Scan(z, f, att.settings(settings).supervisionDecider) :: prev
          case Ast.Expand(s, f, _)              ⇒ fusing.Expand(s, f) :: prev
          case Ast.Conflate(s, f, att)          ⇒ fusing.Conflate(s, f, att.settings(settings).supervisionDecider) :: prev
          case Ast.Buffer(n, s, _)              ⇒ fusing.Buffer(n, s) :: prev
          case Ast.MapConcat(f, att)            ⇒ fusing.MapConcat(f, att.settings(settings).supervisionDecider) :: prev
          case Ast.Grouped(n, _)                ⇒ fusing.Grouped(n) :: prev
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
            case Nil               ⇒ analyze(rest, head :: optimized, Nil)
            case `fuseCandidates`  ⇒ analyze(rest, head :: Ast.Fused(fuseCandidates) :: optimized, Nil)
            case newFuseCandidates ⇒ analyze(rest, optimized, newFuseCandidates)
          }
      }
    }
    val result = analyze(ops, Nil, Nil)
    result
  }

  // Ops come in reverse order
  override def materialize[In, Out](source: Source[In], sink: Sink[Out], rawOps: List[Ast.AstNode], keys: List[Key[_]]): MaterializedMap = {
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
    def addIfKeyed(m: Materializable, v: Any, map: MaterializedMap) = m match {
      case km: KeyedMaterializable[_] ⇒ map.updated(km, v)
      case _                          ⇒ map
    }

    val mmPromise = Promise[MaterializedMap]
    val mmFuture = mmPromise.future

    val (sourceValue, sinkValue, pipeMap) =
      if (rawOps.isEmpty) {
        if (isActive(sink)) {
          val (sub, value) = createSink(flowName)
          (attachSource(sub, flowName), value, MaterializedMap.empty)
        } else if (isActive(source)) {
          val (pub, value) = createSource(flowName)
          (value, attachSink(pub, flowName), MaterializedMap.empty)
        } else {
          val (id, empty) = processorForNode[In, Out](identityStageNode, flowName, 1)
          (attachSource(id, flowName), attachSink(id, flowName), empty)
        }
      } else {
        val (ops, opsSize) = if (settings.optimizations.isEnabled) optimize(rawOps, mmFuture) else (rawOps, rawOps.length)
        val (last, lastMap) = processorForNode[Any, Out](ops.head, flowName, opsSize)
        val (first, map) = processorChain(last, ops.tail, flowName, opsSize - 1, lastMap)
        (attachSource(first.asInstanceOf[Processor[In, Any]], flowName), attachSink(last, flowName), map)
      }
    val sourceSinkMap = addIfKeyed(sink, sinkValue, addIfKeyed(source, sourceValue, pipeMap))

    if (keys.isEmpty) sourceSinkMap
    else (sourceSinkMap /: keys) {
      case (mm, k) ⇒ mm.updated(k, k.materialize(mm))
    }
  }
  //FIXME Should this be a dedicated AstNode?
  private[this] val identityStageNode = Ast.StageFactory(() ⇒ FlowOps.identityStage[Any], Ast.Defaults.identityOp)

  def executionContext: ExecutionContext = dispatchers.lookup(settings.dispatcher match {
    case Deploy.NoDispatcherGiven ⇒ Dispatchers.DefaultDispatcherId
    case other                    ⇒ other
  })

  /**
   * INTERNAL API
   */
  private[akka] def processorForNode[In, Out](op: AstNode, flowName: String, n: Int): (Processor[In, Out], MaterializedMap) = op match {
    // FIXME #16376 should probably be replaced with an ActorFlowProcessor similar to ActorFlowSource/Sink
    case Ast.DirectProcessor(p, _) ⇒ (p().asInstanceOf[Processor[In, Out]], MaterializedMap.empty)
    case Ast.DirectProcessorWithKey(p, key, _) ⇒
      val (processor, value) = p()
      (processor.asInstanceOf[Processor[In, Out]], MaterializedMap.empty.updated(key, value))
    case _ ⇒
      (ActorProcessorFactory[In, Out](actorOf(ActorProcessorFactory.props(this, op), s"$flowName-$n-${op.attributes.name}", op)), MaterializedMap.empty)
  }

  override private[akka] def actorOf(props: Props, name: String): ActorRef =
    actorOf(props, name, settings.dispatcher)

  private[akka] def actorOf(props: Props, name: String, ast: Ast.JunctionAstNode): ActorRef =
    actorOf(props, name, ast.attributes.settings(settings).dispatcher)

  private[akka] def actorOf(props: Props, name: String, ast: AstNode): ActorRef =
    actorOf(props, name, ast.attributes.settings(settings).dispatcher)

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
  // FIXME Investigate possibility of using `enableOperationsFusion` in `materializeJunction`
  override def materializeJunction[In, Out](op: Ast.JunctionAstNode, inputCount: Int, outputCount: Int): (immutable.Seq[Subscriber[In]], immutable.Seq[Publisher[Out]]) = {
    val actorName = s"${createFlowName()}-${op.attributes.name}"

    val transformedSettings = op.attributes.settings(settings)

    op match {
      case fanin: Ast.FanInAstNode ⇒
        val props = fanin match {
          case Ast.Merge(_)                  ⇒ FairMerge.props(transformedSettings, inputCount)
          case Ast.MergePreferred(_)         ⇒ UnfairMerge.props(transformedSettings, inputCount)
          case z: Ast.ZipWith                ⇒ ZipWith.props(transformedSettings, z.f)
          case Ast.Concat(_)                 ⇒ Concat.props(transformedSettings)
          case Ast.FlexiMergeNode(merger, _) ⇒ FlexiMergeImpl.props(transformedSettings, inputCount, merger.createMergeLogic())
        }
        val impl = actorOf(props, actorName, fanin)

        val publisher = new ActorPublisher[Out](impl)
        impl ! ExposedPublisher(publisher.asInstanceOf[ActorPublisher[Any]])
        val subscribers = Vector.tabulate(inputCount)(FanIn.SubInput[In](impl, _)) // FIXME switch to List.tabulate for inputCount < 8?
        (subscribers, List(publisher))

      case fanout: Ast.FanOutAstNode ⇒
        val props = fanout match {
          case Ast.Broadcast(_)                      ⇒ Broadcast.props(transformedSettings, outputCount)
          case Ast.Balance(waitForAllDownstreams, _) ⇒ Balance.props(transformedSettings, outputCount, waitForAllDownstreams)
          case Ast.Unzip(_)                          ⇒ Unzip.props(transformedSettings)
          case Ast.FlexiRouteNode(route, _)          ⇒ FlexiRouteImpl.props(transformedSettings, outputCount, route.createRouteLogic())
        }
        val impl = actorOf(props, actorName, fanout)

        val publishers = Vector.tabulate(outputCount)(id ⇒ new ActorPublisher[Out](impl) { // FIXME switch to List.tabulate for inputCount < 8?
          override val wakeUpMsg = FanOut.SubstreamSubscribePending(id)
        })
        impl ! FanOut.ExposedPublishers(publishers.asInstanceOf[immutable.Seq[ActorPublisher[Any]]])
        val subscriber = ActorSubscriber[In](impl)
        (List(subscriber), publishers)

      case identity @ Ast.IdentityAstNode(attr) ⇒ // FIXME Why is IdentityAstNode a JunctionAStNode?
        // We can safely ignore the materialized map that gets created here since it will be empty
        val id = List(processorForNode[In, Out](identityStageNode, attr.name, 1)._1) // FIXME is `identity.name` appropriate/unique here?
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
  def props(settings: ActorFlowMaterializerSettings): Props = Props(new StreamSupervisor(settings))

  final case class Materialize(props: Props, name: String) extends DeadLetterSuppression
}

private[akka] class StreamSupervisor(settings: ActorFlowMaterializerSettings) extends Actor {
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
  def props(materializer: ActorFlowMaterializer, op: AstNode): Props = {
    val settings = materializer.settings // USE THIS TO AVOID CLOSING OVER THE MATERIALIZER BELOW
    op match {
      case Fused(ops, _) ⇒ ActorInterpreter.props(settings, ops)
      // FIXME this way of grabbing the supervisionDecider feels very inefficient
      case Map(f, att) ⇒
        ActorInterpreter.props(settings, List(fusing.Map(f, att.settings(settings).supervisionDecider)))
      case Filter(p, att) ⇒
        ActorInterpreter.props(settings, List(fusing.Filter(p, att.settings(settings).supervisionDecider)))
      case Drop(n, _) ⇒ ActorInterpreter.props(settings, List(fusing.Drop(n)))
      case Take(n, _) ⇒ ActorInterpreter.props(settings, List(fusing.Take(n)))
      case Collect(pf, att) ⇒
        ActorInterpreter.props(settings, List(fusing.Collect(att.settings(settings).supervisionDecider)(pf)))
      case Scan(z, f, att) ⇒
        ActorInterpreter.props(settings, List(fusing.Scan(z, f, att.settings(settings).supervisionDecider)))
      case Expand(s, f, _) ⇒ ActorInterpreter.props(settings, List(fusing.Expand(s, f)))
      case Conflate(s, f, att) ⇒
        ActorInterpreter.props(settings, List(fusing.Conflate(s, f, att.settings(settings).supervisionDecider)))
      case Buffer(n, s, _) ⇒ ActorInterpreter.props(settings, List(fusing.Buffer(n, s)))
      case MapConcat(f, att) ⇒
        ActorInterpreter.props(settings, List(fusing.MapConcat(f, att.settings(settings).supervisionDecider)))
      case MapAsync(f, att)          ⇒ MapAsyncProcessorImpl.props(att.settings(settings), f)
      case MapAsyncUnordered(f, att) ⇒ MapAsyncUnorderedProcessorImpl.props(att.settings(settings), f)
      // FIXME always amend settings with att.settings(settings)
      case Grouped(n, _)             ⇒ ActorInterpreter.props(settings, List(fusing.Grouped(n)))
      case GroupBy(f, att) ⇒
        GroupByProcessorImpl.props(att.settings(settings), f)
      case PrefixAndTail(n, _) ⇒ PrefixAndTailImpl.props(settings, n)
      case SplitWhen(p, att) ⇒
        SplitWhenProcessorImpl.props(att.settings(settings), p)
      case ConcatAll(_)               ⇒ ConcatAllImpl.props(materializer) //FIXME closes over the materializer, is this good?
      case StageFactory(mkStage, _)   ⇒ ActorInterpreter.props(settings, List(mkStage()))
      case TimerTransform(mkStage, _) ⇒ TimerTransformerProcessorsImpl.props(settings, mkStage())
    }
  }

  def apply[I, O](impl: ActorRef): ActorProcessor[I, O] = {
    val p = new ActorProcessor[I, O](impl)
    impl ! ExposedPublisher(p.asInstanceOf[ActorPublisher[Any]])
    p
  }
}
