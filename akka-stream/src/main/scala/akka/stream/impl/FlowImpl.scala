/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.collection.immutable
import scala.concurrent.{ Future, Promise }
import scala.util.Try
import org.reactivestreams.api.Consumer
import org.reactivestreams.api.Producer
import Ast.{ AstNode, Transform }
import akka.stream.{ OverflowStrategy, FlowMaterializer, Transformer }
import akka.stream.{ FlattenStrategy, FlowMaterializer, Transformer }
import akka.stream.scaladsl.Flow
import scala.util.Success
import scala.util.Failure
import org.reactivestreams.api.Consumer
import akka.stream.scaladsl.Duct
import scala.concurrent.duration.FiniteDuration
import akka.stream.TimerTransformer
import akka.util.Collections.EmptyImmutableSeq

/**
 * INTERNAL API
 */
private[akka] case class FlowImpl[I, O](producerNode: Ast.ProducerNode[I], ops: List[Ast.AstNode]) extends Flow[O] with Builder[O] {
  import FlowImpl._
  import Ast._

  type Thing[T] = Flow[T]

  // Storing ops in reverse order
  override protected def andThen[U](op: Ast.AstNode): Flow[U] = this.copy(ops = op :: ops)

  override def append[U](duct: Duct[_ >: O, U]): Flow[U] =
    copy(ops = duct.ops ++: ops)

  override def appendJava[U](duct: akka.stream.javadsl.Duct[_ >: O, U]): Flow[U] =
    copy(ops = duct.ops ++: ops)

  override def toFuture(materializer: FlowMaterializer): Future[O] = {
    val p = Promise[O]()
    transform(new Transformer[O, Unit] {
      var done = false
      override def onNext(in: O) = { p success in; done = true; Nil }
      override def onError(e: Throwable) = { p failure e }
      override def isComplete = done
      override def onTermination(e: Option[Throwable]) = { p.tryFailure(new NoSuchElementException("empty stream")); Nil }
    }).consume(materializer)
    p.future
  }

  override def consume(materializer: FlowMaterializer): Unit =
    produceTo(materializer, new BlackholeConsumer(materializer.settings.maximumInputBufferSize))

  override def onComplete(materializer: FlowMaterializer)(callback: Try[Unit] ⇒ Unit): Unit =
    transform(new Transformer[O, Unit] {
      override def onNext(in: O) = Nil
      override def onError(e: Throwable) = {
        callback(Failure(e))
        throw e
      }
      override def onTermination(e: Option[Throwable]) = {
        callback(Builder.SuccessUnit)
        Nil
      }
    }).consume(materializer)

  override def toProducer(materializer: FlowMaterializer): Producer[O] = materializer.toProducer(producerNode, ops)

  override def produceTo(materializer: FlowMaterializer, consumer: Consumer[_ >: O]) =
    toProducer(materializer).produceTo(consumer.asInstanceOf[Consumer[O]])
}

/**
 * INTERNAL API
 */
private[akka] case class DuctImpl[In, Out](ops: List[Ast.AstNode]) extends Duct[In, Out] with Builder[Out] {

  type Thing[T] = Duct[In, T]

  // Storing ops in reverse order
  override protected def andThen[U](op: Ast.AstNode): Duct[In, U] = this.copy(ops = op :: ops)

  override def append[U](duct: Duct[_ >: In, U]): Duct[In, U] =
    copy(ops = duct.ops ++: ops)

  override def appendJava[U](duct: akka.stream.javadsl.Duct[_ >: In, U]): Duct[In, U] =
    copy(ops = duct.ops ++: ops)

  override def produceTo(materializer: FlowMaterializer, consumer: Consumer[Out]): Consumer[In] =
    materializer.ductProduceTo(consumer, ops)

  override def consume(materializer: FlowMaterializer): Consumer[In] =
    produceTo(materializer, new BlackholeConsumer(materializer.settings.maximumInputBufferSize))

  override def onComplete(materializer: FlowMaterializer)(callback: Try[Unit] ⇒ Unit): Consumer[In] =
    transform(new Transformer[Out, Unit] {
      override def onNext(in: Out) = Nil
      override def onError(e: Throwable) = {
        callback(Failure(e))
        throw e
      }
      override def onTermination(e: Option[Throwable]) = {
        callback(Builder.SuccessUnit)
        Nil
      }
    }).consume(materializer)

  override def build(materializer: FlowMaterializer): (Consumer[In], Producer[Out]) =
    materializer.ductBuild(ops)

}

/**
 * INTERNAL API
 */
private[akka] object Builder {
  val SuccessUnit = Success[Unit](())
  private val ListOfUnit = List(())

  private case object TakeWithinTimerKey
  private case object DropWithinTimerKey
  private case object GroupedWithinTimerKey

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
    andThen(SingleElement(f.asInstanceOf[Any ⇒ Any], "map"))

  def filter(p: Out ⇒ Boolean): Thing[Out] = {
    val f = (in: Out) ⇒ if (p(in)) in else null
    andThen(SingleElement(f.asInstanceOf[Any ⇒ Any], "filter"))
  }

  def collect[U](pf: PartialFunction[Out, U]): Thing[U] = {
    val f = (in: Out) ⇒ if (pf.isDefinedAt(in)) pf(in) else null
    andThen(SingleElement(f.asInstanceOf[Any ⇒ Any], "collect"))
  }

  def foreach(c: Out ⇒ Unit): Thing[Unit] =
    transform(new Transformer[Out, Unit] {
      override def onNext(in: Out) = { c(in); Nil }
      override def onTermination(e: Option[Throwable]) = ListOfUnit
      override def name = "foreach"
    })

  def fold[U](zero: U)(f: (U, Out) ⇒ U): Thing[U] =
    transform(new FoldTransformer[U](zero, f))

  // Without this class compiler complains about
  // "Parameter type in structural refinement may not refer to an abstract type defined outside that refinement"
  class FoldTransformer[S](var state: S, f: (S, Out) ⇒ S) extends Transformer[Out, S] {
    override def onNext(in: Out): immutable.Seq[S] = { state = f(state, in); Nil }
    override def onTermination(e: Option[Throwable]): immutable.Seq[S] = List(state)
    override def name = "fold"
  }

  def drop(n: Int): Thing[Out] = {
    val f: Any ⇒ Any =
      if (n == 0) identity
      else {
        var c = n
        (in: Any) ⇒ {
          if (c == 0) in
          else {
            c -= 1
            null
          }
        }
      }
    andThen(SingleElement(f, "drop"))
  }

  def dropWithin(d: FiniteDuration): Thing[Out] =
    transform(new TimerTransformer[Out, Out] {
      scheduleOnce(DropWithinTimerKey, d)

      var delegate: Transformer[Out, Out] =
        new Transformer[Out, Out] {
          override def onNext(in: Out) = Nil
        }

      override def onNext(in: Out) = delegate.onNext(in)
      override def onTimer(timerKey: Any) = {
        delegate = identityTransformer.asInstanceOf[Transformer[Out, Out]]
        Nil
      }
      override def name = "dropWithin"
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
      override def name = "take"
    })

  def takeWithin(d: FiniteDuration): Thing[Out] =
    transform(new TimerTransformer[Out, Out] {
      scheduleOnce(TakeWithinTimerKey, d)

      var delegate: Transformer[Out, Out] = identityTransformer.asInstanceOf[Transformer[Out, Out]]

      override def onNext(in: Out) = delegate.onNext(in)
      override def isComplete = delegate.isComplete
      override def onTimer(timerKey: Any) = {
        delegate = takeCompletedTransformer.asInstanceOf[Transformer[Out, Out]]
        Nil
      }
      override def name = "takeWithin"
    })

  def prefixAndTail(n: Int): Thing[(immutable.Seq[Out], Producer[Out])] = andThen(PrefixAndTail(n))

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
      override def onTermination(e: Option[Throwable]) = if (buf.isEmpty) Nil else List(buf)
      override def name = "grouped"
    })

  def groupedWithin(n: Int, d: FiniteDuration): Thing[immutable.Seq[Out]] =
    transform(new TimerTransformer[Out, immutable.Seq[Out]] {
      schedulePeriodically(GroupedWithinTimerKey, d)
      var buf: Vector[Out] = Vector.empty

      override def onNext(in: Out) = {
        buf :+= in
        if (buf.size == n) {
          // start new time window
          schedulePeriodically(GroupedWithinTimerKey, d)
          emitGroup()
        } else Nil
      }
      override def onTermination(e: Option[Throwable]) = if (buf.isEmpty) Nil else List(buf)
      override def onTimer(timerKey: Any) = emitGroup()
      private def emitGroup(): immutable.Seq[immutable.Seq[Out]] =
        if (buf.isEmpty) EmptyImmutableSeq
        else {
          val group = buf
          buf = Vector.empty
          List(group)
        }
      override def name = "groupedWithin"
    })

  def mapConcat[U](f: Out ⇒ immutable.Seq[U]): Thing[U] =
    transform(new Transformer[Out, U] {
      override def onNext(in: Out) = f(in)
      override def name = "mapConcat"
    })

  def transform[U](transformer: Transformer[Out, U]): Thing[U] =
    andThen(Transform(transformer.asInstanceOf[Transformer[Any, Any]]))

  def zip[O2](other: Producer[O2]): Thing[(Out, O2)] = andThen(Zip(other.asInstanceOf[Producer[Any]]))

  def concat[U >: Out](next: Producer[U]): Thing[U] = andThen(Concat(next.asInstanceOf[Producer[Any]]))

  def merge[U >: Out](other: Producer[U]): Thing[U] = andThen(Merge(other.asInstanceOf[Producer[Any]]))

  def splitWhen(p: (Out) ⇒ Boolean): Thing[Producer[Out]] = andThen(SplitWhen(p.asInstanceOf[Any ⇒ Boolean]))

  def groupBy[K](f: (Out) ⇒ K): Thing[(K, Producer[Out])] = andThen(GroupBy(f.asInstanceOf[Any ⇒ Any]))

  def tee(other: Consumer[_ >: Out]): Thing[Out] = andThen(Tee(other.asInstanceOf[Consumer[Any]]))

  def conflate[S](seed: Out ⇒ S, aggregate: (S, Out) ⇒ S): Thing[S] =
    andThen(Conflate(seed.asInstanceOf[Any ⇒ Any], aggregate.asInstanceOf[(Any, Any) ⇒ Any]))

  def expand[S, U](seed: Out ⇒ S, extrapolate: S ⇒ (U, S)): Thing[U] =
    andThen(Expand(seed.asInstanceOf[Any ⇒ Any], extrapolate.asInstanceOf[Any ⇒ (Any, Any)]))

  def buffer(size: Int, overflowStrategy: OverflowStrategy): Thing[Out] = {
    require(size > 0, s"Buffer size must be larger than zero but was [$size]")
    andThen(Buffer(size, overflowStrategy))
  }

  def flatten[U](strategy: FlattenStrategy[Out, U]): Thing[U] = strategy match {
    case _: FlattenStrategy.Concat[Out] ⇒ andThen(ConcatAll)
    case _                              ⇒ throw new IllegalArgumentException(s"Unsupported flattening strategy [${strategy.getClass.getSimpleName}]")
  }

}

