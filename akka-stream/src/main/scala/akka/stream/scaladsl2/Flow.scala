/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scala.collection.immutable
import scala.collection.immutable
import akka.stream.impl2.Ast._
import org.reactivestreams._
import scala.annotation.unchecked.uncheckedVariance
import scala.language.higherKinds
import akka.stream.Transformer
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.Duration
import akka.util.Collections.EmptyImmutableSeq
import akka.stream.TimerTransformer

/**
 * This is the interface from which all concrete Flows inherit. No generic
 * operations are presented because the concrete type of Flow (i.e. whether
 * it has a [[Source]] or a [[Sink]]) determines what is available.
 */
sealed trait Flow

object FlowOps {
  private case object GroupedWithinTimerKey
}

/**
 * Operations offered by flows with a free output side: the DSL flows left-to-right only.
 */
trait FlowOps[-In, +Out] {
  import FlowOps._
  type Repr[-I, +O] <: FlowOps[I, O]

  // Storing ops in reverse order
  protected def andThen[U](op: AstNode): Repr[In, U]

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   */
  def map[T](f: Out ⇒ T): Repr[In, T] =
    transform("map", () ⇒ new Transformer[Out, T] {
      override def onNext(in: Out) = List(f(in))
    })

  /**
   * Only pass on those elements that satisfy the given predicate.
   */
  def filter(p: Out ⇒ Boolean): Repr[In, Out] =
    transform("filter", () ⇒ new Transformer[Out, Out] {
      override def onNext(in: Out) = if (p(in)) List(in) else Nil
    })

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step.
   * Non-matching elements are filtered out.
   */
  def collect[T](pf: PartialFunction[Out, T]): Repr[In, T] =
    transform("collect", () ⇒ new Transformer[Out, T] {
      override def onNext(in: Out) = if (pf.isDefinedAt(in)) List(pf(in)) else Nil
    })

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * `n` must be positive, otherwise IllegalArgumentException is thrown.
   */
  def grouped(n: Int): Repr[In, immutable.Seq[Out]] = {
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

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the given number of elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
   *
   * `n` must be positive, and `d` must be greater than 0 seconds, otherwise
   * IllegalArgumentException is thrown.
   */
  def groupedWithin(n: Int, d: FiniteDuration): Repr[In, immutable.Seq[Out]] = {
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

  /**
   * Generic transformation of a stream: for each element the [[akka.stream.Transformer#onNext]]
   * function is invoked, expecting a (possibly empty) sequence of output elements
   * to be produced.
   * After handing off the elements produced from one input element to the downstream
   * subscribers, the [[akka.stream.Transformer#isComplete]] predicate determines whether to end
   * stream processing at this point; in that case the upstream subscription is
   * canceled. Before signaling normal completion to the downstream subscribers,
   * the [[akka.stream.Transformer#onComplete]] function is invoked to produce a (possibly empty)
   * sequence of elements in response to the end-of-stream event.
   *
   * [[akka.stream.Transformer#onError]] is called when failure is signaled from upstream.
   *
   * After normal completion or error the [[akka.stream.Transformer#cleanup]] function is called.
   *
   * It is possible to keep state in the concrete [[akka.stream.Transformer]] instance with
   * ordinary instance variables. The [[akka.stream.Transformer]] is executed by an actor and
   * therefore you do not have to add any additional thread safety or memory
   * visibility constructs to access the state from the callback methods.
   *
   * Note that you can use [[#timerTransform]] if you need support for scheduled events in the transformer.
   */
  def transform[T](name: String, mkTransformer: () ⇒ Transformer[Out, T]): Repr[In, T] = {
    andThen(Transform(name, mkTransformer.asInstanceOf[() ⇒ Transformer[Any, Any]]))
  }

  /**
   * Takes up to `n` elements from the stream and returns a pair containing a strict sequence of the taken element
   * and a stream representing the remaining elements. If ''n'' is zero or negative, then this will return a pair
   * of an empty collection and a stream containing the whole upstream unchanged.
   */
  def prefixAndTail[U >: Out](n: Int): Repr[In, (immutable.Seq[Out], FlowWithSource[U, U])] =
    andThen(PrefixAndTail(n))

  /**
   * This operation demultiplexes the incoming stream into separate output
   * streams, one for each element key. The key is computed for each element
   * using the given function. When a new key is encountered for the first time
   * it is emitted to the downstream subscriber together with a fresh
   * flow that will eventually produce all the elements of the substream
   * for that key. Not consuming the elements from the created streams will
   * stop this processor from processing more elements, therefore you must take
   * care to unblock (or cancel) all of the produced streams even if you want
   * to consume only one of them.
   */
  def groupBy[K, U >: Out](f: Out ⇒ K): Repr[In, (K, FlowWithSource[U, U])] =
    andThen(GroupBy(f.asInstanceOf[Any ⇒ Any]))

  /**
   * This operation applies the given predicate to all incoming elements and
   * emits them to a stream of output streams, always beginning a new one with
   * the current element if the given predicate returns true for it. This means
   * that for the following series of predicate values, three substreams will
   * be produced with lengths 1, 2, and 3:
   *
   * {{{
   * false,             // element goes into first substream
   * true, false,       // elements go into second substream
   * true, false, false // elements go into third substream
   * }}}
   */
  def splitWhen[U >: Out](p: Out ⇒ Boolean): Repr[In, FlowWithSource[U, U]] =
    andThen(SplitWhen(p.asInstanceOf[Any ⇒ Boolean]))

  /**
   * Transforms a stream of streams into a contiguous stream of elements using the provided flattening strategy.
   * This operation can be used on a stream of element type [[StreamWithSource]].
   */
  def flatten[U](strategy: FlattenStrategy[Out, U]): Repr[In, U] = strategy match {
    case _: FlattenStrategy.Concat[Out] ⇒ andThen(ConcatAll)
    case _                              ⇒ throw new IllegalArgumentException(s"Unsupported flattening strategy [${strategy.getClass.getSimpleName}]")
  }

  /**
   * Transformation of a stream, with additional support for scheduled events.
   *
   * For each element the [[akka.stream.Transformer#onNext]]
   * function is invoked, expecting a (possibly empty) sequence of output elements
   * to be produced.
   * After handing off the elements produced from one input element to the downstream
   * subscribers, the [[akka.stream.Transformer#isComplete]] predicate determines whether to end
   * stream processing at this point; in that case the upstream subscription is
   * canceled. Before signaling normal completion to the downstream subscribers,
   * the [[akka.stream.Transformer#onComplete]] function is invoked to produce a (possibly empty)
   * sequence of elements in response to the end-of-stream event.
   *
   * [[akka.stream.Transformer#onError]] is called when failure is signaled from upstream.
   *
   * After normal completion or error the [[akka.stream.Transformer#cleanup]] function is called.
   *
   * It is possible to keep state in the concrete [[akka.stream.Transformer]] instance with
   * ordinary instance variables. The [[akka.stream.Transformer]] is executed by an actor and
   * therefore you do not have to add any additional thread safety or memory
   * visibility constructs to access the state from the callback methods.
   *
   * Note that you can use [[#transform]] if you just need to transform elements time plays no role in the transformation.
   */
  def timerTransform[U](name: String, mkTransformer: () ⇒ TimerTransformer[Out, U]): Repr[In, U] =
    andThen(TimerTransform(name, mkTransformer.asInstanceOf[() ⇒ TimerTransformer[Any, Any]]))
}

/**
 * Flow without attached input and without attached output, can be used as a `Processor`.
 */
final case class ProcessorFlow[-In, +Out](ops: List[AstNode]) extends FlowOps[In, Out] {
  override type Repr[-I, +O] = ProcessorFlow[I, O]

  override protected def andThen[U](op: AstNode): Repr[In, U] = this.copy(ops = op :: ops)

  def withSink(out: Sink[Out]): FlowWithSink[In, Out] = FlowWithSink(out, ops)
  def withSource(in: Source[In]): FlowWithSource[In, Out] = FlowWithSource(in, ops)

  def prepend[T](f: ProcessorFlow[T, In]): ProcessorFlow[T, Out] = ProcessorFlow(ops ::: f.ops)
  def prepend[T](f: FlowWithSource[T, In]): FlowWithSource[T, Out] = f.append(this)

  def append[T](f: ProcessorFlow[Out, T]): ProcessorFlow[In, T] = ProcessorFlow(f.ops ++: ops)
  def append[T](f: FlowWithSink[Out, T]): FlowWithSink[In, T] = f.prepend(this)
}

/**
 *  Flow with attached output, can be used as a `Subscriber`.
 */
final case class FlowWithSink[-In, +Out](private[scaladsl2] val output: Sink[Out @uncheckedVariance], ops: List[AstNode]) {

  def withSource(in: Source[In]): RunnableFlow[In, Out] = RunnableFlow(in, output, ops)
  def withoutSink: ProcessorFlow[In, Out] = ProcessorFlow(ops)

  def prepend[T](f: ProcessorFlow[T, In]): FlowWithSink[T, Out] = FlowWithSink(output, ops ::: f.ops)
  def prepend[T](f: FlowWithSource[T, In]): RunnableFlow[T, Out] = RunnableFlow(f.input, output, ops ::: f.ops)

  def toSubscriber()(implicit materializer: FlowMaterializer): Subscriber[In @uncheckedVariance] = {
    val subIn = SubscriberSource[In]()
    val mf = withSource(subIn).run()
    subIn.subscriber(mf)
  }
}

/**
 * Flow with attached input, can be used as a `Publisher`.
 */
final case class FlowWithSource[-In, +Out](private[scaladsl2] val input: Source[In @uncheckedVariance], ops: List[AstNode]) extends FlowOps[In, Out] {
  override type Repr[-I, +O] = FlowWithSource[I, O]

  override protected def andThen[U](op: AstNode): Repr[In, U] = this.copy(ops = op :: ops)

  def withSink(out: Sink[Out]): RunnableFlow[In, Out] = RunnableFlow(input, out, ops)
  def withoutSource: ProcessorFlow[In, Out] = ProcessorFlow(ops)

  def append[T](f: ProcessorFlow[Out, T]): FlowWithSource[In, T] = FlowWithSource(input, f.ops ++: ops)
  def append[T](f: FlowWithSink[Out, T]): RunnableFlow[In, T] = RunnableFlow(input, f.output, f.ops ++: ops)

  def toPublisher()(implicit materializer: FlowMaterializer): Publisher[Out @uncheckedVariance] = {
    val pubOut = PublisherSink[Out]
    val mf = withSink(pubOut).run()
    pubOut.publisher(mf)
  }

  def toFanoutPublisher(initialBufferSize: Int, maximumBufferSize: Int)(implicit materializer: FlowMaterializer): Publisher[Out @uncheckedVariance] = {
    val pubOut = PublisherSink.withFanout[Out](initialBufferSize, maximumBufferSize)
    val mf = withSink(pubOut).run()
    pubOut.publisher(mf)
  }

  def publishTo(subscriber: Subscriber[Out @uncheckedVariance])(implicit materializer: FlowMaterializer): Unit =
    toPublisher().subscribe(subscriber)

  def consume()(implicit materializer: FlowMaterializer): Unit =
    withSink(BlackholeSink).run()

}

/**
 * Flow with attached input and output, can be executed.
 */
final case class RunnableFlow[-In, +Out](private[scaladsl2] val input: Source[In @uncheckedVariance],
                                         private[scaladsl2] val output: Sink[Out @uncheckedVariance], ops: List[AstNode]) extends Flow {
  def withoutSink: FlowWithSource[In, Out] = FlowWithSource(input, ops)
  def withoutSource: FlowWithSink[In, Out] = FlowWithSink(output, ops)

  def run()(implicit materializer: FlowMaterializer): MaterializedFlow =
    materializer.materialize(input, output, ops)
}

/**
 * Returned by [[RunnableFlow#run]] and can be used as parameter to the
 * accessor method to retrieve the materialized `Source` or `Sink`, e.g.
 * [[SubscriberSource#subscriber]] or [[PublisherSink#publisher]].
 */
class MaterializedFlow(sourceKey: AnyRef, matSource: Any, sinkKey: AnyRef, matSink: Any) extends MaterializedSource with MaterializedSink {
  /**
   * Do not call directly. Use accessor method in the concrete `Source`, e.g. [[SubscriberSource#subscriber]].
   */
  override def getSourceFor[T](key: SourceWithKey[_, T]): T =
    if (key == sourceKey) matSource.asInstanceOf[T]
    else throw new IllegalArgumentException(s"Source key [$key] doesn't match the source [$sourceKey] of this flow")

  /**
   * Do not call directly. Use accessor method in the concrete `Sink`, e.g. [[PublisherSink#publisher]].
   */
  def getSinkFor[T](key: SinkWithKey[_, T]): T =
    if (key == sinkKey) matSink.asInstanceOf[T]
    else throw new IllegalArgumentException(s"Sink key [$key] doesn't match the sink [$sinkKey] of this flow")
}

trait MaterializedSource {
  def getSourceFor[T](sourceKey: SourceWithKey[_, T]): T
}

trait MaterializedSink {
  def getSinkFor[T](sinkKey: SinkWithKey[_, T]): T
}
