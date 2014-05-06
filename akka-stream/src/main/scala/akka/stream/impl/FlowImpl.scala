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
import akka.stream.scaladsl.Transformer
import akka.stream.scaladsl.RecoveryTransformer

/**
 * INTERNAL API
 */
private[akka] object FlowImpl {
  private val SuccessUnit = Success[Unit](())
  private val ListOfUnit = List(())

  val takeCompletedTransformer: Transformer[Any, Any] = new Transformer[Any, Any] {
    override def onNext(elem: Any) = Nil
    override def isComplete = true
  }

  val identityTransformer: Transformer[Any, Any] = new Transformer[Any, Any] {
    override def onNext(elem: Any) = List(elem)
  }
}

/**
 * INTERNAL API
 */
private[akka] case class FlowImpl[I, O](producerNode: Ast.ProducerNode[I], ops: List[Ast.AstNode]) extends Flow[O] {
  import FlowImpl._
  import Ast._
  // Storing ops in reverse order
  private def andThen[U](op: AstNode): Flow[U] = this.copy(ops = op :: ops)

  override def map[U](f: O ⇒ U): Flow[U] =
    transform(new Transformer[O, U] {
      override def onNext(in: O) = List(f(in))
    })

  override def filter(p: O ⇒ Boolean): Flow[O] =
    transform(new Transformer[O, O] {
      override def onNext(in: O) = if (p(in)) List(in) else Nil
    })

  override def foreach(c: O ⇒ Unit): Flow[Unit] =
    transform(new Transformer[O, Unit] {
      override def onNext(in: O) = { c(in); Nil }
      override def onComplete() = ListOfUnit
    })

  override def fold[U](zero: U)(f: (U, O) ⇒ U): Flow[U] =
    transform(new FoldTransformer[U](zero, f))

  // Without this class compiler complains about 
  // "Parameter type in structural refinement may not refer to an abstract type defined outside that refinement"
  class FoldTransformer[S](var state: S, f: (S, O) ⇒ S) extends Transformer[O, S] {
    override def onNext(in: O): immutable.Seq[S] = { state = f(state, in); Nil }
    override def onComplete(): immutable.Seq[S] = List(state)
  }

  override def drop(n: Int): Flow[O] =
    transform(new Transformer[O, O] {
      var delegate: Transformer[O, O] =
        if (n == 0) identityTransformer.asInstanceOf[Transformer[O, O]]
        else new Transformer[O, O] {
          var c = n
          override def onNext(in: O) = {
            c -= 1
            if (c == 0)
              delegate = identityTransformer.asInstanceOf[Transformer[O, O]]
            Nil
          }
        }

      override def onNext(in: O) = delegate.onNext(in)
    })

  override def take(n: Int): Flow[O] =
    transform(new Transformer[O, O] {
      var delegate: Transformer[O, O] =
        if (n == 0) takeCompletedTransformer.asInstanceOf[Transformer[O, O]]
        else new Transformer[O, O] {
          var c = n
          override def onNext(in: O) = {
            c -= 1
            if (c == 0)
              delegate = takeCompletedTransformer.asInstanceOf[Transformer[O, O]]
            List(in)
          }

          override def isComplete = c == 0
        }

      override def onNext(in: O) = delegate.onNext(in)
      override def isComplete = delegate.isComplete
    })

  override def grouped(n: Int): Flow[immutable.Seq[O]] =
    transform(new Transformer[O, immutable.Seq[O]] {
      var buf: Vector[O] = Vector.empty
      override def onNext(in: O) = {
        buf :+= in
        if (buf.size == n) {
          val group = buf
          buf = Vector.empty
          List(group)
        } else
          Nil
      }
      override def onComplete() = if (buf.isEmpty) Nil else List(buf)
    })

  override def mapConcat[U](f: O ⇒ immutable.Seq[U]): Flow[U] =
    transform(new Transformer[O, U] {
      override def onNext(in: O) = f(in)
    })

  override def transform[U](transformer: Transformer[O, U]): Flow[U] =
    andThen(Transform(transformer.asInstanceOf[Transformer[Any, Any]]))

  override def transformRecover[U](recoveryTransformer: RecoveryTransformer[O, U]): Flow[U] =
    andThen(Recover(recoveryTransformer.asInstanceOf[RecoveryTransformer[Any, Any]]))

  override def zip[O2](other: Producer[O2]): Flow[(O, O2)] = andThen(Zip(other.asInstanceOf[Producer[Any]]))

  override def concat[U >: O](next: Producer[U]): Flow[U] = andThen(Concat(next.asInstanceOf[Producer[Any]]))

  override def merge[U >: O](other: Producer[U]): Flow[U] = andThen(Merge(other.asInstanceOf[Producer[Any]]))

  override def splitWhen(p: (O) ⇒ Boolean): Flow[Producer[O]] = andThen(SplitWhen(p.asInstanceOf[Any ⇒ Boolean]))

  override def groupBy[K](f: (O) ⇒ K): Flow[(K, Producer[O])] = andThen(GroupBy(f.asInstanceOf[Any ⇒ Any]))

  override def toFuture(materializer: FlowMaterializer): Future[O] = {
    val p = Promise[O]()
    transformRecover(new RecoveryTransformer[O, Unit] {
      var done = false
      override def onNext(in: O) = { p success in; done = true; Nil }
      override def onError(e: Throwable) = { p failure e; Nil }
      override def isComplete = done
      override def onComplete() = { p.tryFailure(new NoSuchElementException("empty stream")); Nil }
    }).consume(materializer)
    p.future
  }

  override def consume(materializer: FlowMaterializer): Unit = materializer.consume(producerNode, ops)

  def onComplete(materializer: FlowMaterializer)(callback: Try[Unit] ⇒ Unit): Unit =
    transformRecover(new RecoveryTransformer[O, Unit] {
      var ok = true
      override def onNext(in: O) = Nil
      override def onError(e: Throwable) = {
        callback(Failure(e))
        ok = false
        Nil
      }
      override def onComplete() = { if (ok) callback(SuccessUnit); Nil }
    }).consume(materializer)

  override def toProducer(materializer: FlowMaterializer): Producer[O] = materializer.toProducer(producerNode, ops)
}

