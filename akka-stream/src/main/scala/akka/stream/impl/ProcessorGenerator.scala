/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.annotation.tailrec
import scala.collection.immutable

import org.reactivestreams.api.{ Consumer, Processor, Producer }
import org.reactivestreams.spi.Subscriber

import akka.actor.ActorRefFactory
import akka.stream.{ GeneratorSettings, ProcessorGenerator }

/**
 * INTERNAL API
 */
private[akka] object Ast {
  trait AstNode

  case class Transform(
    zero: Any,
    f: (Any, Any) ⇒ (Any, immutable.Seq[Any]),
    onComplete: Any ⇒ immutable.Seq[Any],
    isComplete: Any ⇒ Boolean,
    cleanup: Any ⇒ Unit) extends AstNode

  case class Recover(t: Transform) extends AstNode
  case class GroupBy(f: Any ⇒ Any) extends AstNode
  case class SplitWhen(p: Any ⇒ Boolean) extends AstNode
  case class Merge(other: Producer[Any]) extends AstNode
  case class Zip(other: Producer[Any]) extends AstNode
  case class Concat(next: Producer[Any]) extends AstNode

  trait ProducerNode[I] {
    def createProducer(settings: GeneratorSettings, context: ActorRefFactory): Producer[I]
  }

  case class ExistingProducer[I](producer: Producer[I]) extends ProducerNode[I] {
    def createProducer(settings: GeneratorSettings, context: ActorRefFactory) = producer
  }

  case class IteratorProducerNode[I](iterator: Iterator[I]) extends ProducerNode[I] {
    def createProducer(settings: GeneratorSettings, context: ActorRefFactory): Producer[I] =
      if (iterator.isEmpty) EmptyProducer.asInstanceOf[Producer[I]]
      else new ActorProducer[I](context.actorOf(IteratorProducer.props(iterator, settings)))
  }
  case class IterableProducerNode[I](iterable: immutable.Iterable[I]) extends ProducerNode[I] {
    def createProducer(settings: GeneratorSettings, context: ActorRefFactory): Producer[I] =
      if (iterable.isEmpty) EmptyProducer.asInstanceOf[Producer[I]]
      else new ActorProducer[I](context.actorOf(IterableProducer.props(iterable, settings)))
  }
  case class ThunkProducerNode[I](f: () ⇒ I) extends ProducerNode[I] {
    def createProducer(settings: GeneratorSettings, context: ActorRefFactory): Producer[I] =
      new ActorProducer(context.actorOf(ActorProducer.props(settings, f)))
  }
}

/**
 * INTERNAL API
 */
private[akka] object ActorBasedProcessorGenerator {

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
private[akka] class ActorBasedProcessorGenerator(settings: GeneratorSettings, _context: ActorRefFactory) extends ProcessorGenerator {
  import Ast._
  import ActorBasedProcessorGenerator._

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

  private val identityConsumer = Transform((), (_, _) ⇒ () -> Nil, _ ⇒ Nil, _ ⇒ false, _ ⇒ ())

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
