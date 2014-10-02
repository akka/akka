/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scala.collection.immutable
import akka.stream.impl2.Ast._
import org.reactivestreams._
import scala.concurrent.Future
import akka.stream.Transformer
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.Duration
import akka.util.Collections.EmptyImmutableSeq
import akka.stream.TimerTransformer
import akka.stream.OverflowStrategy

import scala.annotation.unchecked.uncheckedVariance
import scala.language.higherKinds
import scala.language.existentials

private[scaladsl2] object PipeOps {
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
 * Scala API: Operations offered by flows with a free output side: the DSL flows left-to-right only.
 */
private[scaladsl2] trait PipeOps[+Out] extends FlowOps[Out] {
  import PipeOps._
  type Repr[+O]

  // Storing ops in reverse order
  protected def andThen[U](op: AstNode): Repr[U]

  override def map[T](f: Out ⇒ T): Repr[T] =
    transform("map", () ⇒ new Transformer[Out, T] {
      override def onNext(in: Out) = List(f(in))
    })

  override def mapConcat[T](f: Out ⇒ immutable.Seq[T]): Repr[T] =
    transform("mapConcat", () ⇒ new Transformer[Out, T] {
      override def onNext(in: Out) = f(in)
    })

  override def mapAsync[T](f: Out ⇒ Future[T]): Repr[T] =
    andThen(MapAsync(f.asInstanceOf[Any ⇒ Future[Any]]))

  override def mapAsyncUnordered[T](f: Out ⇒ Future[T]): Repr[T] =
    andThen(MapAsyncUnordered(f.asInstanceOf[Any ⇒ Future[Any]]))

  override def filter(p: Out ⇒ Boolean): Repr[Out] =
    transform("filter", () ⇒ new Transformer[Out, Out] {
      override def onNext(in: Out) = if (p(in)) List(in) else Nil
    })

  override def collect[T](pf: PartialFunction[Out, T]): Repr[T] =
    transform("collect", () ⇒ new Transformer[Out, T] {
      override def onNext(in: Out) = if (pf.isDefinedAt(in)) List(pf(in)) else Nil
    })

  override def grouped(n: Int): Repr[immutable.Seq[Out]] = {
    require(n > 0, "n must be greater than 0")
    transform("grouped", () ⇒ new Transformer[Out, immutable.Seq[Out]] {
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
    })
  }

  override def groupedWithin(n: Int, d: FiniteDuration): Repr[immutable.Seq[Out]] = {
    require(n > 0, "n must be greater than 0")
    require(d > Duration.Zero)
    timerTransform("groupedWithin", () ⇒ new TimerTransformer[Out, immutable.Seq[Out]] {
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
    })
  }

  override def drop(n: Int): Repr[Out] =
    transform("drop", () ⇒ new Transformer[Out, Out] {
      var delegate: Transformer[Out, Out] =
        if (n <= 0) identityTransformer.asInstanceOf[Transformer[Out, Out]]
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

  override def dropWithin(d: FiniteDuration): Repr[Out] =
    timerTransform("dropWithin", () ⇒ new TimerTransformer[Out, Out] {
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
    })

  override def take(n: Int): Repr[Out] =
    transform("take", () ⇒ new Transformer[Out, Out] {
      var delegate: Transformer[Out, Out] =
        if (n <= 0) takeCompletedTransformer.asInstanceOf[Transformer[Out, Out]]
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

  override def takeWithin(d: FiniteDuration): Repr[Out] =
    timerTransform("takeWithin", () ⇒ new TimerTransformer[Out, Out] {
      scheduleOnce(TakeWithinTimerKey, d)

      var delegate: Transformer[Out, Out] = identityTransformer.asInstanceOf[Transformer[Out, Out]]

      override def onNext(in: Out) = delegate.onNext(in)
      override def isComplete = delegate.isComplete
      override def onTimer(timerKey: Any) = {
        delegate = takeCompletedTransformer.asInstanceOf[Transformer[Out, Out]]
        Nil
      }
    })

  override def conflate[S](seed: Out ⇒ S, aggregate: (S, Out) ⇒ S): Repr[S] =
    andThen(Conflate(seed.asInstanceOf[Any ⇒ Any], aggregate.asInstanceOf[(Any, Any) ⇒ Any]))

  override def expand[S, U](seed: Out ⇒ S, extrapolate: S ⇒ (U, S)): Repr[U] =
    andThen(Expand(seed.asInstanceOf[Any ⇒ Any], extrapolate.asInstanceOf[Any ⇒ (Any, Any)]))

  override def buffer(size: Int, overflowStrategy: OverflowStrategy): Repr[Out] = {
    require(size > 0, s"Buffer size must be larger than zero but was [$size]")
    andThen(Buffer(size, overflowStrategy))
  }

  override def transform[T](name: String, mkTransformer: () ⇒ Transformer[Out, T]): Repr[T] = {
    andThen(Transform(name, mkTransformer.asInstanceOf[() ⇒ Transformer[Any, Any]]))
  }

  override def prefixAndTail[U >: Out](n: Int): Repr[(immutable.Seq[Out], Source[U])] =
    andThen(PrefixAndTail(n))

  override def groupBy[K, U >: Out](f: Out ⇒ K): Repr[(K, Source[U])] =
    andThen(GroupBy(f.asInstanceOf[Any ⇒ Any]))

  override def splitWhen[U >: Out](p: Out ⇒ Boolean): Repr[Source[U]] =
    andThen(SplitWhen(p.asInstanceOf[Any ⇒ Boolean]))

  override def flatten[U](strategy: FlattenStrategy[Out, U]): Repr[U] = strategy match {
    case _: FlattenStrategy.Concat[Out] ⇒ andThen(ConcatAll)
    case _                              ⇒ throw new IllegalArgumentException(s"Unsupported flattening strategy [${strategy.getClass.getSimpleName}]")
  }

  override def timerTransform[U](name: String, mkTransformer: () ⇒ TimerTransformer[Out, U]): Repr[U] =
    andThen(TimerTransform(name, mkTransformer.asInstanceOf[() ⇒ TimerTransformer[Any, Any]]))
}

private[scaladsl2] object Pipe {
  private val emptyInstance = Pipe[Any, Any](ops = Nil)
  def empty[T]: Pipe[T, T] = emptyInstance.asInstanceOf[Pipe[T, T]]

  val OnlyPipesErrorMessage = "Only pipes are supported currently!"
}

/**
 * Flow with one open input and one open output..
 */
private[scaladsl2] final case class Pipe[-In, +Out](ops: List[AstNode]) extends Flow[In, Out] with PipeOps[Out] {
  override type Repr[+O] = Pipe[In @uncheckedVariance, O]

  override protected def andThen[U](op: AstNode): Repr[U] = this.copy(ops = op :: ops)

  def withDrain(out: Drain[Out]): SinkPipe[In] = SinkPipe(out, ops)

  def withTap(in: Tap[In]): SourcePipe[Out] = SourcePipe(in, ops)

  override def connect[T](flow: Flow[Out, T]): Flow[In, T] = flow match {
    case p: Pipe[T, In] ⇒ Pipe(p.ops ++: ops)
    case _              ⇒ throw new IllegalArgumentException(Pipe.OnlyPipesErrorMessage)
  }

  override def connect(sink: Sink[Out]): Sink[In] = sink match {
    case sp: SinkPipe[Out] ⇒ sp.prependPipe(this)
    case d: Drain[Out]     ⇒ this.withDrain(d)
    case _                 ⇒ throw new IllegalArgumentException(Pipe.OnlyPipesErrorMessage)
  }
}

/**
 *  Pipe with open input and attached output. Can be used as a `Subscriber`.
 */
private[scaladsl2] final case class SinkPipe[-In](output: Drain[_], ops: List[AstNode]) extends Sink[In] {

  def withTap(in: Tap[In]): RunnablePipe = RunnablePipe(in, output, ops)

  def prependPipe[T](pipe: Pipe[T, In]): SinkPipe[T] = SinkPipe(output, ops ::: pipe.ops)

  override def toSubscriber()(implicit materializer: FlowMaterializer): Subscriber[In @uncheckedVariance] = {
    val subIn = SubscriberTap[In]()
    val mf = withTap(subIn).run()
    subIn.subscriber(mf)
  }
}

/**
 * Pipe with open output and attached input. Can be used as a `Publisher`.
 */
private[scaladsl2] final case class SourcePipe[+Out](input: Tap[_], ops: List[AstNode]) extends Source[Out] with PipeOps[Out] {
  override type Repr[+O] = SourcePipe[O]

  override protected def andThen[U](op: AstNode): Repr[U] = SourcePipe(input, op :: ops)

  def withDrain(out: Drain[Out]): RunnablePipe = RunnablePipe(input, out, ops)

  def appendPipe[T](pipe: Pipe[Out, T]): SourcePipe[T] = SourcePipe(input, pipe.ops ++: ops)

  override def connect[T](flow: Flow[Out, T]): Source[T] = flow match {
    case p: Pipe[Out, T] ⇒ appendPipe(p)
    case _               ⇒ throw new IllegalArgumentException(Pipe.OnlyPipesErrorMessage)
  }

  override def connect(sink: Sink[Out]): RunnableFlow = sink match {
    case sp: SinkPipe[Out] ⇒ RunnablePipe(input, sp.output, sp.ops ++: ops)
    case d: Drain[Out]     ⇒ this.withDrain(d)
    case _                 ⇒ throw new IllegalArgumentException(Pipe.OnlyPipesErrorMessage)
  }

  override def toPublisher()(implicit materializer: FlowMaterializer): Publisher[Out @uncheckedVariance] = {
    val pubOut = PublisherDrain[Out]
    val mf = withDrain(pubOut).run()
    pubOut.publisher(mf)
  }

  override def toFanoutPublisher(initialBufferSize: Int, maximumBufferSize: Int)(implicit materializer: FlowMaterializer): Publisher[Out @uncheckedVariance] = {
    val pubOut = PublisherDrain.withFanout[Out](initialBufferSize, maximumBufferSize)
    val mf = withDrain(pubOut).run()
    pubOut.publisher(mf)
  }

  override def publishTo(subscriber: Subscriber[Out @uncheckedVariance])(implicit materializer: FlowMaterializer): Unit =
    toPublisher().subscribe(subscriber)

  override def consume()(implicit materializer: FlowMaterializer): Unit =
    withDrain(BlackholeDrain).run()
}

/**
 * Pipe with attached input and output, can be executed.
 */
private[scaladsl2] final case class RunnablePipe(input: Tap[_], output: Drain[_], ops: List[AstNode]) extends RunnableFlow {
  def run()(implicit materializer: FlowMaterializer): MaterializedPipe =
    materializer.materialize(input, output, ops)
}

/**
 * Returned by [[RunnablePipe#run]] and can be used as parameter to the
 * accessor method to retrieve the materialized `Tap` or `Drain`, e.g.
 * [[SubscriberTap#subscriber]] or [[PublisherDrain#publisher]].
 */
private[stream] class MaterializedPipe(tapKey: AnyRef, matTap: Any, drainKey: AnyRef, matDrain: Any) extends MaterializedFlow {
  /**
   * Do not call directly. Use accessor method in the concrete `Tap`, e.g. [[SubscriberTap#subscriber]].
   */
  override def getTapFor[T](key: TapWithKey[_, T]): T =
    if (key == tapKey) matTap.asInstanceOf[T]
    else throw new IllegalArgumentException(s"Tap key [$key] doesn't match the tap [$tapKey] of this flow")

  /**
   * Do not call directly. Use accessor method in the concrete `Drain`, e.g. [[PublisherDrain#publisher]].
   */
  def getDrainFor[T](key: DrainWithKey[_, T]): T =
    if (key == drainKey) matDrain.asInstanceOf[T]
    else throw new IllegalArgumentException(s"Drain key [$key] doesn't match the drain [$drainKey] of this flow")
}
