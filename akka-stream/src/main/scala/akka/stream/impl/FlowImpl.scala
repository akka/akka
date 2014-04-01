/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.collection.immutable
import scala.concurrent.{ Future, Promise }
import scala.util.Try

import org.reactivestreams.api.Producer

import Ast.{ AstNode, Recover, Transform }
import akka.stream.ProcessorGenerator
import akka.stream.scala_api.Flow

/**
 * INTERNAL API
 */
private[akka] case class FlowImpl[I, O](producerNode: Ast.ProducerNode[I], ops: List[Ast.AstNode]) extends Flow[O] {
  import Ast._
  // Storing ops in reverse order
  private def andThen[U](op: AstNode): Flow[U] = this.copy(ops = op :: ops)

  def map[U](f: O ⇒ U): Flow[U] = transform(())((_, in) ⇒ ((), List(f(in))))

  def filter(p: O ⇒ Boolean): Flow[O] = transform(())((_, in) ⇒ if (p(in)) ((), List(in)) else ((), Nil))

  def foreach(c: O ⇒ Unit): Flow[Unit] = transform(())((_, in) ⇒ c(in) -> Nil, _ ⇒ List(()))

  def fold[U](zero: U)(f: (U, O) ⇒ U): Flow[U] = transform(zero)((z, in) ⇒ f(z, in) -> Nil, z ⇒ List(z))

  def drop(n: Int): Flow[O] = transform(n)((x, in) ⇒ if (x == 0) 0 -> List(in) else (x - 1) -> Nil)

  def take(n: Int): Flow[O] = transform(n)((x, in) ⇒ if (x == 0) 0 -> Nil else (x - 1) -> List(in), isComplete = _ == 0)

  def grouped(n: Int): Flow[immutable.Seq[O]] =
    transform(immutable.Seq.empty[O])((buf, in) ⇒ {
      val group = buf :+ in
      if (group.size == n) (Nil, List(group))
      else (group, Nil)
    }, x ⇒ if (x.isEmpty) Nil else List(x))

  def mapConcat[U](f: O ⇒ immutable.Seq[U]): Flow[U] = transform(())((_, in) ⇒ ((), f(in)))

  def transform[S, U](zero: S)(
    f: (S, O) ⇒ (S, immutable.Seq[U]),
    onComplete: S ⇒ immutable.Seq[U] = (_: S) ⇒ Nil,
    isComplete: S ⇒ Boolean = (_: S) ⇒ false): Flow[U] =
    andThen(Transform(
      zero,
      f.asInstanceOf[(Any, Any) ⇒ (Any, immutable.Seq[Any])],
      onComplete.asInstanceOf[Any ⇒ immutable.Seq[Any]],
      isComplete.asInstanceOf[Any ⇒ Boolean]))

  def transformRecover[S, U](zero: S)(
    f: (S, Try[O]) ⇒ (S, immutable.Seq[U]),
    onComplete: S ⇒ immutable.Seq[U] = (_: S) ⇒ Nil,
    isComplete: S ⇒ Boolean = (_: S) ⇒ false): Flow[U] =
    andThen(Recover(Transform(
      zero,
      f.asInstanceOf[(Any, Any) ⇒ (Any, immutable.Seq[Any])],
      onComplete.asInstanceOf[Any ⇒ immutable.Seq[Any]],
      isComplete.asInstanceOf[Any ⇒ Boolean])))

  override def zip[O2](other: Producer[O2]): Flow[(O, O2)] = andThen(Zip(other.asInstanceOf[Producer[Any]]))

  override def concat[U >: O](next: Producer[U]): Flow[U] = andThen(Concat(next.asInstanceOf[Producer[Any]]))

  override def merge[U >: O](other: Producer[U]): Flow[U] = andThen(Merge(other.asInstanceOf[Producer[Any]]))

  override def splitWhen(p: (O) ⇒ Boolean): Flow[Producer[O]] = andThen(SplitWhen(p.asInstanceOf[Any ⇒ Boolean]))

  override def groupBy[K](f: (O) ⇒ K): Flow[(K, Producer[O])] = andThen(GroupBy(f.asInstanceOf[Any ⇒ Any]))

  def toFuture(generator: ProcessorGenerator): Future[O] = {
    val p = Promise[O]()
    transformRecover(0)((x, in) ⇒ { p complete in; 1 -> Nil }, isComplete = _ == 1).consume(generator)
    p.future
  }

  def consume(generator: ProcessorGenerator): Unit = generator.consume(producerNode, ops)

  def toProducer(generator: ProcessorGenerator): Producer[O] = generator.toProducer(producerNode, ops)
}

