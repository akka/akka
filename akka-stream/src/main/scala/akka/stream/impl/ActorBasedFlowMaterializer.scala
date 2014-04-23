/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.annotation.tailrec
import scala.collection.immutable
import org.reactivestreams.api.{ Consumer, Processor, Producer }
import org.reactivestreams.spi.Subscriber
import akka.actor.ActorRefFactory
import akka.stream.{ MaterializerSettings, FlowMaterializer }
import akka.stream.Transformer
import akka.stream.RecoveryTransformer
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

/**
 * INTERNAL API
 */
private[akka] object Ast {
  trait AstNode {
    def name: String
  }

  case class Transform(transformer: Transformer[Any, Any]) extends AstNode {
    override def name = transformer.name
  }
  case class Recover(recoveryTransformer: RecoveryTransformer[Any, Any]) extends AstNode {
    override def name = recoveryTransformer.name
  }
  case class GroupBy(f: Any ⇒ Any) extends AstNode {
    override def name = "groupBy"
  }
  case class SplitWhen(p: Any ⇒ Boolean) extends AstNode {
    override def name = "splitWhen"
  }
  case class Merge(other: Producer[Any]) extends AstNode {
    override def name = "merge"
  }
  case class Zip(other: Producer[Any]) extends AstNode {
    override def name = "zip"
  }
  case class Concat(next: Producer[Any]) extends AstNode {
    override def name = "concat"
  }
  case class Tee(other: Consumer[Any]) extends AstNode {
    override def name = "tee"
  }

  trait ProducerNode[I] {
    private[akka] def createProducer(materializer: ActorBasedFlowMaterializer, flowName: String): Producer[I]
  }

  final case class ExistingProducer[I](producer: Producer[I]) extends ProducerNode[I] {
    def createProducer(materializer: ActorBasedFlowMaterializer, flowName: String) = producer
  }

  final case class IteratorProducerNode[I](iterator: Iterator[I]) extends ProducerNode[I] {
    final def createProducer(materializer: ActorBasedFlowMaterializer, flowName: String): Producer[I] =
      if (iterator.isEmpty) EmptyProducer.asInstanceOf[Producer[I]]
      else new ActorProducer[I](materializer.context.actorOf(IteratorProducer.props(iterator, materializer.settings),
        name = s"$flowName-0-iterator"))
  }
  final case class IterableProducerNode[I](iterable: immutable.Iterable[I]) extends ProducerNode[I] {
    def createProducer(materializer: ActorBasedFlowMaterializer, flowName: String): Producer[I] =
      if (iterable.isEmpty) EmptyProducer.asInstanceOf[Producer[I]]
      else new ActorProducer[I](materializer.context.actorOf(IterableProducer.props(iterable, materializer.settings),
        name = s"$flowName-0-iterable"), Some(iterable))
  }
  final case class ThunkProducerNode[I](f: () ⇒ I) extends ProducerNode[I] {
    def createProducer(materializer: ActorBasedFlowMaterializer, flowName: String): Producer[I] =
      new ActorProducer(materializer.context.actorOf(ActorProducer.props(materializer.settings, f),
        name = s"$flowName-0-thunk"))
  }
  final case class FutureProducerNode[I](future: Future[I]) extends ProducerNode[I] {
    def createProducer(materializer: ActorBasedFlowMaterializer, flowName: String): Producer[I] =
      future.value match {
        case Some(Success(element)) ⇒
          new ActorProducer[I](materializer.context.actorOf(IterableProducer.props(List(element), materializer.settings),
            name = s"$flowName-0-future"), Some(future))
        case Some(Failure(t)) ⇒
          ErrorProducer(t).asInstanceOf[Producer[I]]
        case None ⇒
          new ActorProducer[I](materializer.context.actorOf(FutureProducer.props(future, materializer.settings),
            name = s"$flowName-0-future"), Some(future))
      }
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
  val settings: MaterializerSettings,
  _context: ActorRefFactory,
  namePrefix: String)
  extends FlowMaterializer {
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

  @tailrec private def processorChain(topConsumer: Consumer[_], ops: immutable.Seq[AstNode],
                                      flowName: String, n: Int): Consumer[_] = {
    ops match {
      case op :: tail ⇒
        val opProcessor: Processor[Any, Any] = processorForNode(op, flowName, n)
        opProcessor.produceTo(topConsumer.asInstanceOf[Consumer[Any]])
        processorChain(opProcessor, tail, flowName, n - 1)
      case _ ⇒ topConsumer
    }
  }

  // Ops come in reverse order
  override def toProducer[I, O](producerNode: ProducerNode[I], ops: List[AstNode]): Producer[O] = {
    val flowName = createFlowName()
    if (ops.isEmpty) producerNode.createProducer(this, flowName).asInstanceOf[Producer[O]]
    else {
      val opsSize = ops.size
      val opProcessor = processorForNode(ops.head, flowName, opsSize)
      val topConsumer = processorChain(opProcessor, ops.tail, flowName, opsSize - 1)
      producerNode.createProducer(this, flowName).produceTo(topConsumer.asInstanceOf[Consumer[I]])
      opProcessor.asInstanceOf[Producer[O]]
    }
  }

  private val blackholeTransform = Transform(
    new Transformer[Any, Any] {
      override def onNext(element: Any) = Nil
    })

  private val identityTransform = Transform(
    new Transformer[Any, Any] {
      override def onNext(element: Any) = List(element)
    })

  override def consume[I](producerNode: ProducerNode[I], ops: List[AstNode]): Unit = {
    val flowName = createFlowName()
    val consumer = consume(ops, flowName)
    producerNode.createProducer(this, flowName).produceTo(consumer.asInstanceOf[Consumer[I]])
  }

  private def consume[In, Out](ops: List[Ast.AstNode], flowName: String): Consumer[In] = {
    val c = ops match {
      case Nil ⇒
        new ActorConsumer[Any](context.actorOf(ActorConsumer.props(settings, blackholeTransform),
          name = s"$flowName-1-consume"))
      case head :: tail ⇒
        val opsSize = ops.size
        val c = new ActorConsumer[Any](context.actorOf(ActorConsumer.props(settings, head),
          name = s"$flowName-$opsSize-${head.name}"))
        processorChain(c, tail, flowName, ops.size - 1)
    }
    c.asInstanceOf[Consumer[In]]
  }

  def processorForNode(op: AstNode, flowName: String, n: Int): Processor[Any, Any] =
    new ActorProcessor(context.actorOf(ActorProcessor.props(settings, op),
      name = s"$flowName-$n-${op.name}"))

  override def ductProduceTo[In, Out](consumer: Consumer[Out], ops: List[Ast.AstNode]): Consumer[In] =
    processorChain(consumer, ops, createFlowName(), ops.size).asInstanceOf[Consumer[In]]

  override def ductConsume[In](ops: List[Ast.AstNode]): Consumer[In] =
    consume(ops, createFlowName)

  override def ductBuild[In, Out](ops: List[Ast.AstNode]): (Consumer[In], Producer[Out]) = {
    val flowName = createFlowName()
    if (ops.isEmpty) {
      val identityProcessor: Processor[In, Out] = processorForNode(identityTransform, flowName, 1).asInstanceOf[Processor[In, Out]]
      (identityProcessor, identityProcessor)
    } else {
      val opsSize = ops.size
      val outProcessor = processorForNode(ops.head, flowName, opsSize).asInstanceOf[Processor[In, Out]]
      val topConsumer = processorChain(outProcessor, ops.tail, flowName, opsSize - 1).asInstanceOf[Processor[In, Out]]
      (topConsumer, outProcessor)
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