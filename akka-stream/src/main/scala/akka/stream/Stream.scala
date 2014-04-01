/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.collection.immutable
import org.reactivestreams.api.{ Consumer, Producer }
import scala.util.control.NonFatal
import akka.stream.impl._
import akka.actor.ActorRefFactory
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try
import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.stream.impl.Ast.IteratorProducerNode
import akka.stream.impl.Ast.IterableProducerNode
import akka.stream.impl.Ast.ExistingProducer
import scala.annotation.unchecked.uncheckedVariance

object Stream {
  def apply[T](producer: Producer[T]): Stream[T] = StreamImpl(ExistingProducer(producer), Nil)
  def apply[T](iterator: Iterator[T]): Stream[T] = StreamImpl(IteratorProducerNode(iterator), Nil)
  def apply[T](iterable: immutable.Iterable[T]): Stream[T] = StreamImpl(IterableProducerNode(iterable), Nil)

  def apply[T](gen: ProcessorGenerator, f: () ⇒ T): Stream[T] = apply(gen.produce(f))

  object Stop extends RuntimeException with NoStackTrace
}

trait Stream[+T] {
  def map[U](f: T ⇒ U): Stream[U]
  def filter(p: T ⇒ Boolean): Stream[T]
  def foreach(c: T ⇒ Unit): Stream[Unit]
  def fold[U](zero: U)(f: (U, T) ⇒ U): Stream[U]
  def drop(n: Int): Stream[T]
  def take(n: Int): Stream[T]
  def grouped(n: Int): Stream[immutable.Seq[T]]
  def mapConcat[U](f: T ⇒ immutable.Seq[U]): Stream[U]
  def transform[S, U](zero: S)(
    f: (S, T) ⇒ (S, immutable.Seq[U]),
    onComplete: S ⇒ immutable.Seq[U] = (_: S) ⇒ Nil,
    isComplete: S ⇒ Boolean = (_: S) ⇒ false): Stream[U]
  def transformRecover[S, U](zero: S)(
    f: (S, Try[T]) ⇒ (S, immutable.Seq[U]),
    onComplete: S ⇒ immutable.Seq[U] = (_: S) ⇒ Nil,
    isComplete: S ⇒ Boolean = (_: S) ⇒ false): Stream[U]

  def groupBy[K](f: T ⇒ K): Stream[(K, Producer[T @uncheckedVariance])]
  def splitWhen(p: T ⇒ Boolean): Stream[Producer[T @uncheckedVariance]]

  def merge[U >: T](other: Producer[U]): Stream[U]
  def zip[U](other: Producer[U]): Stream[(T, U)]
  def concat[U >: T](next: Producer[U]): Stream[U]

  def toFuture(generator: ProcessorGenerator): Future[T]
  def consume(generator: ProcessorGenerator): Unit
  def toProducer(generator: ProcessorGenerator): Producer[T @uncheckedVariance]
}

// FIXME is Processor the right naming here?
object ProcessorGenerator {
  def apply(settings: GeneratorSettings)(implicit context: ActorRefFactory): ProcessorGenerator =
    new ActorBasedProcessorGenerator(settings, context)
}

trait ProcessorGenerator {
  /**
   * INTERNAL API
   * ops are stored in reverse order
   */
  private[akka] def toProducer[I, O](producerNode: Ast.ProducerNode[I], ops: List[Ast.AstNode]): Producer[O]
  /**
   * INTERNAL API
   */
  private[akka] def consume[I](producerNode: Ast.ProducerNode[I], ops: List[Ast.AstNode]): Unit
  /**
   * INTERNAL API
   */
  private[akka] def produce[T](f: () ⇒ T): Producer[T]
}

// FIXME default values? Should we have an extension that reads from config?
case class GeneratorSettings(
  initialFanOutBufferSize: Int = 4,
  maxFanOutBufferSize: Int = 16,
  initialInputBufferSize: Int = 4,
  maximumInputBufferSize: Int = 16,
  upstreamSubscriptionTimeout: FiniteDuration = 3.seconds,
  downstreamSubscriptionTimeout: FiniteDuration = 3.seconds) {

  private def isPowerOfTwo(n: Integer): Boolean = (n & (n - 1)) == 0
  require(initialFanOutBufferSize > 0, "initialFanOutBufferSize must be > 0")
  require(maxFanOutBufferSize > 0, "maxFanOutBufferSize must be > 0")
  require(initialFanOutBufferSize <= maxFanOutBufferSize,
    s"initialFanOutBufferSize($initialFanOutBufferSize) must be <= maxFanOutBufferSize($maxFanOutBufferSize)")

  require(initialInputBufferSize > 0, "initialInputBufferSize must be > 0")
  require(isPowerOfTwo(initialInputBufferSize), "initialInputBufferSize must be a power of two")
  require(maximumInputBufferSize > 0, "maximumInputBufferSize must be > 0")
  require(isPowerOfTwo(maximumInputBufferSize), "initialInputBufferSize must be a power of two")
  require(initialInputBufferSize <= maximumInputBufferSize,
    s"initialInputBufferSize($initialInputBufferSize) must be <= maximumInputBufferSize($maximumInputBufferSize)")
}

