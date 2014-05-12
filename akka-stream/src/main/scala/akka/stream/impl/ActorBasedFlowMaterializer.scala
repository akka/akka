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
import akka.stream.scaladsl.Transformer
import akka.stream.scaladsl.RecoveryTransformer
import scala.util.Try
import scala.concurrent.Future
import scala.util.Success
import scala.util.Failure

/**
 * INTERNAL API
 */
private[akka] object Ast {
  trait AstNode

  case class Transform(transformer: Transformer[Any, Any]) extends AstNode
  case class Recover(recoveryTransformer: RecoveryTransformer[Any, Any]) extends AstNode
  case class GroupBy(f: Any ⇒ Any) extends AstNode
  case class SplitWhen(p: Any ⇒ Boolean) extends AstNode
  case class Merge(other: Producer[Any]) extends AstNode
  case class Zip(other: Producer[Any]) extends AstNode
  case class Concat(next: Producer[Any]) extends AstNode
  case class Tee(other: Consumer[Any]) extends AstNode

  trait ProducerNode[I] {
    def createProducer(settings: MaterializerSettings, context: ActorRefFactory): Producer[I]
  }

  case class ExistingProducer[I](producer: Producer[I]) extends ProducerNode[I] {
    def createProducer(settings: MaterializerSettings, context: ActorRefFactory) = producer
  }

  case class IteratorProducerNode[I](iterator: Iterator[I]) extends ProducerNode[I] {
    def createProducer(settings: MaterializerSettings, context: ActorRefFactory): Producer[I] =
      if (iterator.isEmpty) EmptyProducer.asInstanceOf[Producer[I]]
      else new ActorProducer[I](context.actorOf(IteratorProducer.props(iterator, settings)))
  }
  case class IterableProducerNode[I](iterable: immutable.Iterable[I]) extends ProducerNode[I] {
    def createProducer(settings: MaterializerSettings, context: ActorRefFactory): Producer[I] =
      if (iterable.isEmpty) EmptyProducer.asInstanceOf[Producer[I]]
      else new ActorProducer[I](context.actorOf(IterableProducer.props(iterable, settings)))
  }
  case class ThunkProducerNode[I](f: () ⇒ I) extends ProducerNode[I] {
    def createProducer(settings: MaterializerSettings, context: ActorRefFactory): Producer[I] =
      new ActorProducer(context.actorOf(ActorProducer.props(settings, f)))
  }
  case class FutureProducerNode[I](future: Future[I]) extends ProducerNode[I] {
    def createProducer(settings: MaterializerSettings, context: ActorRefFactory): Producer[I] =
      future.value match {
        case Some(Success(element)) ⇒
          new ActorProducer[I](context.actorOf(IterableProducer.props(List(element), settings)))
        case Some(Failure(t)) ⇒
          new ErrorProducer(t).asInstanceOf[Producer[I]]
        case None ⇒
          new ActorProducer[I](context.actorOf(FutureProducer.props(future, settings)))
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

}

/**
 * INTERNAL API
 */
private[akka] class ActorBasedFlowMaterializer(settings: MaterializerSettings, _context: ActorRefFactory) extends FlowMaterializer {
  import Ast._
  import ActorBasedFlowMaterializer._

  private def context = ctx.get() match {
    case null ⇒ _context
    case x    ⇒ x
  }

  @tailrec private def processorChain(topConsumer: Consumer[_], ops: immutable.Seq[AstNode]): Consumer[_] = {
    ops match {
      case op :: tail ⇒
        val opProcessor: Processor[Any, Any] = processorForNode(op)
        opProcessor.produceTo(topConsumer.asInstanceOf[Consumer[Any]])
        processorChain(opProcessor, tail)
      case _ ⇒ topConsumer
    }
  }

  // Ops come in reverse order
  override def toProducer[I, O](producerNode: ProducerNode[I], ops: List[AstNode]): Producer[O] = {
    if (ops.isEmpty) producerNode.createProducer(settings, context).asInstanceOf[Producer[O]]
    else {
      val opProcessor = processorForNode(ops.head)
      val topConsumer = processorChain(opProcessor, ops.tail)
      producerNode.createProducer(settings, context).produceTo(topConsumer.asInstanceOf[Consumer[I]])
      opProcessor.asInstanceOf[Producer[O]]
    }
  }

  private val identityConsumer = Transform(
    new Transformer[Any, Any] {
      override def onNext(element: Any) = Nil
    })

  override def consume[I](producerNode: ProducerNode[I], ops: List[AstNode]): Unit = {
    val consumer = ops match {
      case Nil ⇒
        new ActorConsumer[Any](context.actorOf(ActorConsumer.props(settings, identityConsumer)))
      case head :: tail ⇒
        val c = new ActorConsumer[Any](context.actorOf(ActorConsumer.props(settings, head)))
        processorChain(c, tail)
    }
    producerNode.createProducer(settings, context).produceTo(consumer.asInstanceOf[Consumer[I]])
  }

  def processorForNode(op: AstNode): Processor[Any, Any] = new ActorProcessor(context.actorOf(ActorProcessor.props(settings, op)))

}
