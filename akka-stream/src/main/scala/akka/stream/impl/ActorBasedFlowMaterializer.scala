/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.annotation.tailrec
import scala.collection.immutable
import org.reactivestreams.{ Publisher, Subscriber, Processor }
import akka.actor.ActorRefFactory
import akka.stream.{ OverflowStrategy, MaterializerSettings, FlowMaterializer, Transformer }
import scala.util.Try
import scala.concurrent.Future
import scala.util.Success
import scala.util.Failure
import java.util.concurrent.atomic.AtomicLong
import akka.actor.ActorContext
import akka.actor.ExtensionIdProvider
import akka.actor.ExtensionId
import akka.actor.ExtendedActorSystem
import akka.actor.ActorSystem
import akka.actor.Extension
import scala.concurrent.duration.FiniteDuration
import akka.stream.TimerTransformer

/**
 * INTERNAL API
 */
private[akka] object Ast {
  sealed trait AstNode {
    def name: String
  }

  case class Transform(transformer: Transformer[Any, Any]) extends AstNode {
    override def name = transformer.name
  }
  case class MapFuture(f: Any ⇒ Future[Any]) extends AstNode {
    override def name = "mapFuture"
  }
  case class GroupBy(f: Any ⇒ Any) extends AstNode {
    override def name = "groupBy"
  }
  case class SplitWhen(p: Any ⇒ Boolean) extends AstNode {
    override def name = "splitWhen"
  }
  case class Merge(other: Publisher[Any]) extends AstNode {
    override def name = "merge"
  }
  case class Zip(other: Publisher[Any]) extends AstNode {
    override def name = "zip"
  }
  case class Concat(next: Publisher[Any]) extends AstNode {
    override def name = "concat"
  }
  case class Tee(other: Subscriber[Any]) extends AstNode {
    override def name = "tee"
  }
  case class PrefixAndTail(n: Int) extends AstNode {
    override def name = "prefixAndTail"
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

  trait PublisherNode[I] {
    private[akka] def createPublisher(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[I]
  }

  final case class ExistingPublisher[I](publisher: Publisher[I]) extends PublisherNode[I] {
    def createPublisher(materializer: ActorBasedFlowMaterializer, flowName: String) = publisher
  }

  final case class IteratorPublisherNode[I](iterator: Iterator[I]) extends PublisherNode[I] {
    final def createPublisher(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[I] =
      if (iterator.isEmpty) EmptyPublisher.asInstanceOf[Publisher[I]]
      else ActorPublisher[I](materializer.context.actorOf(IteratorPublisher.props(iterator, materializer.settings),
        name = s"$flowName-0-iterator"))
  }
  final case class IterablePublisherNode[I](iterable: immutable.Iterable[I]) extends PublisherNode[I] {
    def createPublisher(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[I] =
      if (iterable.isEmpty) EmptyPublisher.asInstanceOf[Publisher[I]]
      else ActorPublisher[I](materializer.context.actorOf(IterablePublisher.props(iterable, materializer.settings),
        name = s"$flowName-0-iterable"), Some(iterable))
  }
  final case class ThunkPublisherNode[I](f: () ⇒ I) extends PublisherNode[I] {
    def createPublisher(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[I] =
      ActorPublisher[I](materializer.context.actorOf(SimpleCallbackPublisher.props(materializer.settings, f),
        name = s"$flowName-0-thunk"))
  }
  final case class FuturePublisherNode[I](future: Future[I]) extends PublisherNode[I] {
    def createPublisher(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[I] =
      future.value match {
        case Some(Success(element)) ⇒
          ActorPublisher[I](materializer.context.actorOf(IterablePublisher.props(List(element), materializer.settings),
            name = s"$flowName-0-future"), Some(future))
        case Some(Failure(t)) ⇒
          ErrorPublisher(t).asInstanceOf[Publisher[I]]
        case None ⇒
          ActorPublisher[I](materializer.context.actorOf(FuturePublisher.props(future, materializer.settings),
            name = s"$flowName-0-future"), Some(future))
      }
  }
  final case class TickPublisherNode[I](interval: FiniteDuration, tick: () ⇒ I) extends PublisherNode[I] {
    def createPublisher(materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[I] =
      ActorPublisher[I](materializer.context.actorOf(TickPublisher.props(interval, tick, materializer.settings),
        name = s"$flowName-0-tick"))
  }
}

/**
 * INTERNAL API
 */
private[akka] object ActorBasedFlowMaterializer {

  val ctx = new ThreadLocal[ActorRefFactory]

  def withCtx[T](arf: ActorRefFactory)(block: ⇒ T): T = {
    val old = ctx.get()
    ctx.set(arf)
    try block
    finally ctx.set(old)
  }

  def currentActorContext(): ActorContext =
    ActorBasedFlowMaterializer.ctx.get() match {
      case c: ActorContext ⇒ c
      case _ ⇒
        throw new IllegalStateException(s"Transformer [${getClass.getName}] is running without ActorContext")
    }

}

/**
 * INTERNAL API
 */
private[akka] class ActorBasedFlowMaterializer(
  settings: MaterializerSettings,
  _context: ActorRefFactory,
  namePrefix: String)
  extends FlowMaterializer(settings) {
  import Ast._
  import ActorBasedFlowMaterializer._

  _context match {
    case _: ActorSystem | _: ActorContext ⇒ // ok
    case null                             ⇒ throw new IllegalArgumentException("ActorRefFactory context must be defined")
    case _ ⇒ throw new IllegalArgumentException(s"ActorRefFactory context must be a ActorSystem or ActorContext, " +
      "got [${_contex.getClass.getName}]")
  }

  def context = ctx.get() match {
    case null ⇒ _context
    case x    ⇒ x
  }

  def withNamePrefix(name: String): FlowMaterializer =
    new ActorBasedFlowMaterializer(settings, _context, name)

  private def system: ActorSystem = _context match {
    case s: ExtendedActorSystem ⇒ s
    case c: ActorContext        ⇒ c.system
    case _ ⇒
      throw new IllegalArgumentException(s"Unknown ActorRefFactory [${_context.getClass.getName}")
  }

  private def nextFlowNameCount(): Long = FlowNameCounter(system).counter.incrementAndGet()

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
  override def toPublisher[I, O](publisherNode: PublisherNode[I], ops: List[AstNode]): Publisher[O] = {
    val flowName = createFlowName()
    if (ops.isEmpty) publisherNode.createPublisher(this, flowName).asInstanceOf[Publisher[O]]
    else {
      val opsSize = ops.size
      val opProcessor = processorForNode(ops.head, flowName, opsSize)
      val topSubscriber = processorChain(opProcessor, ops.tail, flowName, opsSize - 1)
      publisherNode.createPublisher(this, flowName).subscribe(topSubscriber.asInstanceOf[Subscriber[I]])
      opProcessor.asInstanceOf[Publisher[O]]
    }
  }

  private val identityTransform = Transform(
    new Transformer[Any, Any] {
      override def onNext(element: Any) = List(element)
    })

  def processorForNode(op: AstNode, flowName: String, n: Int): Processor[Any, Any] =
    ActorProcessor(context.actorOf(ActorProcessor.props(settings, op),
      name = s"$flowName-$n-${op.name}"))

  override def ductProduceTo[In, Out](subscriber: Subscriber[Out], ops: List[Ast.AstNode]): Subscriber[In] =
    processorChain(subscriber, ops, createFlowName(), ops.size).asInstanceOf[Subscriber[In]]

  override def ductBuild[In, Out](ops: List[Ast.AstNode]): (Subscriber[In], Publisher[Out]) = {
    val flowName = createFlowName()
    if (ops.isEmpty) {
      val identityProcessor: Processor[In, Out] = processorForNode(identityTransform, flowName, 1).asInstanceOf[Processor[In, Out]]
      (identityProcessor, identityProcessor)
    } else {
      val opsSize = ops.size
      val outProcessor = processorForNode(ops.head, flowName, opsSize).asInstanceOf[Processor[In, Out]]
      val topSubscriber = processorChain(outProcessor, ops.tail, flowName, opsSize - 1).asInstanceOf[Processor[In, Out]]
      (topSubscriber, outProcessor)
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