/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.collection.immutable
import scala.concurrent.{ Future, Promise }
import scala.util.Try
import org.reactivestreams.api.Producer
import Ast.{ AstNode, Recover, Transform }
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Flow
import scala.util.Success
import scala.util.Failure

/**
 * INTERNAL API
 */
private[akka] object FlowImpl {
  private val SuccessUnit = Success[Unit](())
  private val OnCompleteSuccessToken = (true, Nil)
  private val OnCompleteFailureToken = (false, Nil)
}

/**
 * INTERNAL API
 */
private[akka] case class FlowImpl[I, O](producerNode: Ast.ProducerNode[I], ops: List[Ast.AstNode]) extends Flow[O] {
  import FlowImpl._
  import Ast._
  // Storing ops in reverse order
  private def andThen[U](op: AstNode): Flow[U] = this.copy(ops = op :: ops)

  override def map[U](f: O ⇒ U): Flow[U] = transform(())((_, in) ⇒ ((), List(f(in))))

  override def filter(p: O ⇒ Boolean): Flow[O] = transform(())((_, in) ⇒ if (p(in)) ((), List(in)) else ((), Nil))

  override def foreach(c: O ⇒ Unit): Flow[Unit] = transform(())((_, in) ⇒ c(in) -> Nil, _ ⇒ List(()))

  override def fold[U](zero: U)(f: (U, O) ⇒ U): Flow[U] = transform(zero)((z, in) ⇒ f(z, in) -> Nil, z ⇒ List(z))

  override def drop(n: Int): Flow[O] = transform(n)((x, in) ⇒ if (x == 0) 0 -> List(in) else (x - 1) -> Nil)

  override def take(n: Int): Flow[O] = transform(n)((x, in) ⇒ if (x == 0) 0 -> Nil else (x - 1) -> List(in), isComplete = _ == 0)

  override def grouped(n: Int): Flow[immutable.Seq[O]] =
    transform(immutable.Seq.empty[O])((buf, in) ⇒ {
      val group = buf :+ in
      if (group.size == n) (Nil, List(group))
      else (group, Nil)
    }, x ⇒ if (x.isEmpty) Nil else List(x))

  override def mapConcat[U](f: O ⇒ immutable.Seq[U]): Flow[U] = transform(())((_, in) ⇒ ((), f(in)))

  override def transform[S, U](zero: S)(
    f: (S, O) ⇒ (S, immutable.Seq[U]),
    onComplete: S ⇒ immutable.Seq[U] = (_: S) ⇒ Nil,
    isComplete: S ⇒ Boolean = (_: S) ⇒ false,
    cleanup: S ⇒ Unit = (_: S) ⇒ ()): Flow[U] =
    andThen(Transform(
      zero,
      f.asInstanceOf[(Any, Any) ⇒ (Any, immutable.Seq[Any])],
      onComplete.asInstanceOf[Any ⇒ immutable.Seq[Any]],
      isComplete.asInstanceOf[Any ⇒ Boolean],
      cleanup.asInstanceOf[Any ⇒ Unit]))

  override def transformRecover[S, U](zero: S)(
    f: (S, Try[O]) ⇒ (S, immutable.Seq[U]),
    onComplete: S ⇒ immutable.Seq[U] = (_: S) ⇒ Nil,
    isComplete: S ⇒ Boolean = (_: S) ⇒ false,
    cleanup: S ⇒ Unit = (_: S) ⇒ ()): Flow[U] =
    andThen(Recover(Transform(
      zero,
      f.asInstanceOf[(Any, Any) ⇒ (Any, immutable.Seq[Any])],
      onComplete.asInstanceOf[Any ⇒ immutable.Seq[Any]],
      isComplete.asInstanceOf[Any ⇒ Boolean],
      cleanup.asInstanceOf[Any ⇒ Unit])))

  override def zip[O2](other: Producer[O2]): Flow[(O, O2)] = andThen(Zip(other.asInstanceOf[Producer[Any]]))

  override def concat[U >: O](next: Producer[U]): Flow[U] = andThen(Concat(next.asInstanceOf[Producer[Any]]))

  override def merge[U >: O](other: Producer[U]): Flow[U] = andThen(Merge(other.asInstanceOf[Producer[Any]]))

  override def splitWhen(p: (O) ⇒ Boolean): Flow[Producer[O]] = andThen(SplitWhen(p.asInstanceOf[Any ⇒ Boolean]))

  override def groupBy[K](f: (O) ⇒ K): Flow[(K, Producer[O])] = andThen(GroupBy(f.asInstanceOf[Any ⇒ Any]))

  override def toFuture(materializer: FlowMaterializer): Future[O] = {
    val p = Promise[O]()
    transformRecover(0)((x, in) ⇒ { p complete in; 1 -> Nil },
      onComplete = _ ⇒ { p.tryFailure(new NoSuchElementException("empty stream")); Nil },
      isComplete = _ == 1).consume(materializer)
    p.future
  }

  override def consume(materializer: FlowMaterializer): Unit = materializer.consume(producerNode, ops)

  def onComplete(materializer: FlowMaterializer)(callback: Try[Unit] ⇒ Unit): Unit =
    transformRecover(true)(
      f = {
        case (_, fail @ Failure(ex)) ⇒
          callback(fail.asInstanceOf[Try[Unit]])
          OnCompleteFailureToken
        case _ ⇒ OnCompleteSuccessToken
      },
      onComplete = ok ⇒ { if (ok) callback(SuccessUnit); Nil }).consume(materializer)

  override def toProducer(materializer: FlowMaterializer): Producer[O] = materializer.toProducer(producerNode, ops)
}

