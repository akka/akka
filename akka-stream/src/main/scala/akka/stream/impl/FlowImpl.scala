/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.collection.immutable
import scala.concurrent.{ Future, Promise }
import scala.util.Try
import org.reactivestreams.api.Consumer
import org.reactivestreams.api.Producer
import Ast.{ AstNode, Recover, Transform }
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Flow
import scala.util.Success
import scala.util.Failure
import akka.stream.scaladsl.Transformer
import akka.stream.scaladsl.RecoveryTransformer
import akka.stream.scaladsl.Duct

/**
 * INTERNAL API
 */
private[akka] object FlowImpl {
  private val SuccessUnit = Success[Unit](())
}

/**
 * INTERNAL API
 */
private[akka] case class FlowImpl[I, O](producerNode: Ast.ProducerNode[I], ops: List[Ast.AstNode]) extends Flow[O] with Builder[O] {
  import FlowImpl._
  import Ast._

  type Thing[T] = Flow[T]

  // Storing ops in reverse order
  override protected def andThen[U](op: Ast.AstNode): Flow[U] = this.copy(ops = op :: ops)

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

  override def produceTo(materializer: FlowMaterializer, consumer: Consumer[O]) =
    toProducer(materializer).produceTo(consumer)
}

/**
 * INTERNAL API
 */
private[akka] case class DuctImpl[In, Out](ops: List[Ast.AstNode]) extends Duct[In, Out] with Builder[Out] {

  type Thing[T] = Duct[In, T]

  // Storing ops in reverse order
  override protected def andThen[U](op: Ast.AstNode): Duct[In, U] = this.copy(ops = op :: ops)

  override def produceTo(materializer: FlowMaterializer, consumer: Consumer[Out]): Consumer[In] =
    materializer.ductProduceTo(consumer, ops)

  override def consume(materializer: FlowMaterializer): Consumer[In] =
    materializer.ductConsume(ops)

  override def build(materializer: FlowMaterializer): (Consumer[In], Producer[Out]) =
    materializer.ductBuild(ops)

}

/**
 * INTERNAL API
 */
private[akka] object Builder {
  private val ListOfUnit = List(())

  private val takeCompletedTransformer: Transformer[Any, Any] = new Transformer[Any, Any] {
    override def onNext(elem: Any) = Nil
    override def isComplete = true
  }

  private val identityTransformer: Transformer[Any, Any] = new Transformer[Any, Any] {
    override def onNext(elem: Any) = List(elem)
  }
}

/**
 * INTERNAL API
 * Builder of `Flow` or `Duct` things
 */
private[akka] trait Builder[Out] {
  import Builder._
  import akka.stream.impl.Ast._
  import scala.language.higherKinds

  type Thing[T]

  protected def andThen[U](op: Ast.AstNode): Thing[U]

  def map[U](f: Out ⇒ U): Thing[U] =
    transform(new Transformer[Out, U] {
      override def onNext(in: Out) = List(f(in))
    })

  def filter(p: Out ⇒ Boolean): Thing[Out] =
    transform(new Transformer[Out, Out] {
      override def onNext(in: Out) = if (p(in)) List(in) else Nil
    })

  def foreach(c: Out ⇒ Unit): Thing[Unit] =
    transform(new Transformer[Out, Unit] {
      override def onNext(in: Out) = { c(in); Nil }
      override def onComplete() = ListOfUnit
    })

  def fold[U](zero: U)(f: (U, Out) ⇒ U): Thing[U] =
    transform(new FoldTransformer[U](zero, f))

  // Without this class compiler complains about 
  // "Parameter type in structural refinement may not refer to an abstract type defined outside that refinement"
  class FoldTransformer[S](var state: S, f: (S, Out) ⇒ S) extends Transformer[Out, S] {
    override def onNext(in: Out): immutable.Seq[S] = { state = f(state, in); Nil }
    override def onComplete(): immutable.Seq[S] = List(state)
  }

  def drop(n: Int): Thing[Out] =
    transform(new Transformer[Out, Out] {
      var delegate: Transformer[Out, Out] =
        if (n == 0) identityTransformer.asInstanceOf[Transformer[Out, Out]]
        else new Transformer[Out, Out] {
          var c = n
          override def onNext(in: Out) = {
            c -= 1
            if (c == 0)
              delegate = identityTransformer.asInstanceOf[Transformer[Out, Out]]
            Nil
          }
        }

      override def onNext(in: Out) = delegate.onNext(in)
    })

  def take(n: Int): Thing[Out] =
    transform(new Transformer[Out, Out] {
      var delegate: Transformer[Out, Out] =
        if (n == 0) takeCompletedTransformer.asInstanceOf[Transformer[Out, Out]]
        else new Transformer[Out, Out] {
          var c = n
          override def onNext(in: Out) = {
            c -= 1
            if (c == 0)
              delegate = takeCompletedTransformer.asInstanceOf[Transformer[Out, Out]]
            List(in)
          }
        }

      override def onNext(in: Out) = delegate.onNext(in)
      override def isComplete = delegate.isComplete
    })

  def grouped(n: Int): Thing[immutable.Seq[Out]] =
    transform(new Transformer[Out, immutable.Seq[Out]] {
      var buf: Vector[Out] = Vector.empty
      override def onNext(in: Out) = {
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

  def mapConcat[U](f: Out ⇒ immutable.Seq[U]): Thing[U] =
    transform(new Transformer[Out, U] {
      override def onNext(in: Out) = f(in)
    })

  def transform[U](transformer: Transformer[Out, U]): Thing[U] =
    andThen(Transform(transformer.asInstanceOf[Transformer[Any, Any]]))

  def transformRecover[U](recoveryTransformer: RecoveryTransformer[Out, U]): Thing[U] =
    andThen(Recover(recoveryTransformer.asInstanceOf[RecoveryTransformer[Any, Any]]))

  def zip[O2](other: Producer[O2]): Thing[(Out, O2)] = andThen(Zip(other.asInstanceOf[Producer[Any]]))

  def concat[U >: Out](next: Producer[U]): Thing[U] = andThen(Concat(next.asInstanceOf[Producer[Any]]))

  def merge[U >: Out](other: Producer[U]): Thing[U] = andThen(Merge(other.asInstanceOf[Producer[Any]]))

  def splitWhen(p: (Out) ⇒ Boolean): Thing[Producer[Out]] = andThen(SplitWhen(p.asInstanceOf[Any ⇒ Boolean]))

  def groupBy[K](f: (Out) ⇒ K): Thing[(K, Producer[Out])] = andThen(GroupBy(f.asInstanceOf[Any ⇒ Any]))

}

