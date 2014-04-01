/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scala_api

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NoStackTrace

import org.reactivestreams.api.Producer

import akka.stream.ProcessorGenerator
import akka.stream.impl.Ast.{ ExistingProducer, IterableProducerNode, IteratorProducerNode }
import akka.stream.impl.FlowImpl

object Flow {
  def apply[T](producer: Producer[T]): Flow[T] = FlowImpl(ExistingProducer(producer), Nil)
  def apply[T](iterator: Iterator[T]): Flow[T] = FlowImpl(IteratorProducerNode(iterator), Nil)
  def apply[T](iterable: immutable.Iterable[T]): Flow[T] = FlowImpl(IterableProducerNode(iterable), Nil)

  def apply[T](gen: ProcessorGenerator, f: () ⇒ T): Flow[T] = apply(gen.produce(f))
}

trait Flow[+T] {
  def map[U](f: T ⇒ U): Flow[U]
  def filter(p: T ⇒ Boolean): Flow[T]
  def foreach(c: T ⇒ Unit): Flow[Unit]
  def fold[U](zero: U)(f: (U, T) ⇒ U): Flow[U]
  def drop(n: Int): Flow[T]
  def take(n: Int): Flow[T]
  def grouped(n: Int): Flow[immutable.Seq[T]]
  def mapConcat[U](f: T ⇒ immutable.Seq[U]): Flow[U]
  def transform[S, U](zero: S)(
    f: (S, T) ⇒ (S, immutable.Seq[U]),
    onComplete: S ⇒ immutable.Seq[U] = (_: S) ⇒ Nil,
    isComplete: S ⇒ Boolean = (_: S) ⇒ false): Flow[U]
  def transformRecover[S, U](zero: S)(
    f: (S, Try[T]) ⇒ (S, immutable.Seq[U]),
    onComplete: S ⇒ immutable.Seq[U] = (_: S) ⇒ Nil,
    isComplete: S ⇒ Boolean = (_: S) ⇒ false): Flow[U]

  def groupBy[K](f: T ⇒ K): Flow[(K, Producer[T @uncheckedVariance])]
  def splitWhen(p: T ⇒ Boolean): Flow[Producer[T @uncheckedVariance]]

  def merge[U >: T](other: Producer[U]): Flow[U]
  def zip[U](other: Producer[U]): Flow[(T, U)]
  def concat[U >: T](next: Producer[U]): Flow[U]

  def toFuture(generator: ProcessorGenerator): Future[T]
  def consume(generator: ProcessorGenerator): Unit
  def toProducer(generator: ProcessorGenerator): Producer[T @uncheckedVariance]
}

