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

  case class Transform(zero: Any, f: (Any, Any) ⇒ (Any, immutable.Seq[Any]), onComplete: Any ⇒ immutable.Seq[Any], isComplete: Any ⇒ Boolean) extends AstNode
  case class Recover(t: Transform) extends AstNode
}

/**
 * INTERNAL API
 */
private[akka] class ActorBasedProcessorGenerator(settings: GeneratorSettings, context: ActorRefFactory) extends ProcessorGenerator {
  import Ast._

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
  override def toProducer[I, O](producerToExtend: Producer[I], ops: List[AstNode]): Producer[O] = {
    if (ops.isEmpty) producerToExtend.asInstanceOf[Producer[O]]
    else {
      val opProcessor = processorForNode(ops.head)
      val topConsumer = processorChain(opProcessor, ops.tail)
      producerToExtend.getPublisher.subscribe(topConsumer.getSubscriber.asInstanceOf[Subscriber[I]])
      opProcessor.asInstanceOf[Producer[O]]
    }
  }

  private val identityConsumer = Transform((), (_, _) ⇒ () -> Nil, _ ⇒ Nil, _ ⇒ false)

  override def consume[I](producer: Producer[I], ops: List[AstNode]): Unit = {
    val consumer = ops match {
      case Nil ⇒
        new ActorConsumer[Any](context.actorOf(ActorConsumer.props(settings, identityConsumer)))
      case head :: tail ⇒
        val c = new ActorConsumer[Any](context.actorOf(ActorConsumer.props(settings, head)))
        processorChain(c, tail)
    }
    producer.produceTo(consumer.asInstanceOf[Consumer[I]])
  }

  def processorForNode(op: AstNode): Processor[Any, Any] = new ActorProcessor(context.actorOf(ActorProcessor.props(settings, op)))

}
