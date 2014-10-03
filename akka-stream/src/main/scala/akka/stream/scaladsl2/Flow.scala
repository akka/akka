/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.stream.impl2.Ast._
import akka.stream.{ TimerTransformer, Transformer, OverflowStrategy }
import akka.util.Collections.EmptyImmutableSeq
import scala.collection.immutable
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.concurrent.Future

import scala.language.higherKinds

/**
 * A `Flow` is a set of stream processing steps that has one open input and one open output.
 */
trait Flow[-In, +Out] extends FlowOps[Out] {
  override type Repr[+O] <: Flow[In, O]

  /**
   * Transform this flow by appending the given processing steps.
   */
  def connect[T](flow: Flow[Out, T]): Flow[In, T]

  /**
   * Connect this flow to a sink, concatenating the processing steps of both.
   */
  def connect(sink: Sink[Out]): Sink[In]
}

object Flow {
  /**
   * Helper to create `Flow` without a [[Source]] or a [[Sink]].
   * Example usage: `Flow[Int]`
   */
  def apply[T]: Flow[T, T] = Pipe.empty[T]
}

/**
 * Flow with attached input and output, can be executed.
 */
trait RunnableFlow {
  def run()(implicit materializer: FlowMaterializer): MaterializedFlow
}

/**
 * Returned by [[RunnableFlow#run]] and can be used as parameter to the
 * accessor method to retrieve the materialized `Tap` or `Drain`, e.g.
 * [[SubscriberTap#subscriber]] or [[PublisherDrain#publisher]].
 */
trait MaterializedFlow extends MaterializedTap with MaterializedDrain

/**
 * Scala API: Operations offered by Flows and Sources with a free output side: the DSL flows left-to-right only.
 */
trait FlowOps[+Out] {
  import FlowOps._
  type Repr[+O]

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   */
  def map[T](f: Out ⇒ T): Repr[T] =
    transform("map", () ⇒ new Transformer[Out, T] {
      def onNext(in: Out) = List(f(in))
    })

  /**
   * Transform each input element into a sequence of output elements that is
   * then flattened into the output stream.
   */
  def mapConcat[T](f: Out ⇒ immutable.Seq[T]): Repr[T] =
    transform("mapConcat", () ⇒ new Transformer[Out, T] {
      def onNext(in: Out) = f(in)
    })

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `Future` of the
   * element that will be emitted downstream. As many futures as requested elements by
   * downstream may run in parallel and may complete in any order, but the elements that
   * are emitted downstream are in the same order as from upstream.
   *
   * @see [[#mapAsyncUnordered]]
   */
  def mapAsync[T](f: Out ⇒ Future[T]): Repr[T] =
    andThen(MapAsync(f.asInstanceOf[Any ⇒ Future[Any]]))

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `Future` of the
   * element that will be emitted downstream. As many futures as requested elements by
   * downstream may run in parallel and each processed element will be emitted dowstream
   * as soon as it is ready, i.e. it is possible that the elements are not emitted downstream
   * in the same order as from upstream.
   *
   * @see [[#mapAsync]]
   */
  def mapAsyncUnordered[T](f: Out ⇒ Future[T]): Repr[T] =
    andThen(MapAsyncUnordered(f.asInstanceOf[Any ⇒ Future[Any]]))

  /**
   * Only pass on those elements that satisfy the given predicate.
   */
  def filter(p: Out ⇒ Boolean): Repr[Out] =
    transform("filter", () ⇒ new Transformer[Out, Out] {
      def onNext(in: Out) = if (p(in)) List(in) else Nil
    })

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step.
   * Non-matching elements are filtered out.
   */
  def collect[T](pf: PartialFunction[Out, T]): Repr[T] =
    transform("collect", () ⇒ new Transformer[Out, T] {
      def onNext(in: Out) = if (pf.isDefinedAt(in)) List(pf(in)) else Nil
    })

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * `n` must be positive, otherwise IllegalArgumentException is thrown.
   */
  def grouped(n: Int): Repr[immutable.Seq[Out]] = {
    require(n > 0, "n must be greater than 0")
    transform("grouped", () ⇒ new Transformer[Out, immutable.Seq[Out]] {
      var buf: Vector[Out] = Vector.empty
      def onNext(in: Out) = {
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
  def groupedWithin(n: Int, d: FiniteDuration): Repr[immutable.Seq[Out]] = {
    require(n > 0, "n must be greater than 0")
    require(d > Duration.Zero)
    timerTransform("groupedWithin", () ⇒ new TimerTransformer[Out, immutable.Seq[Out]] {
      schedulePeriodically(GroupedWithinTimerKey, d)
      var buf: Vector[Out] = Vector.empty

      def onNext(in: Out) = {
        buf :+= in
        if (buf.size == n) {
          // start new time window
          schedulePeriodically(GroupedWithinTimerKey, d)
          emitGroup()
        } else Nil
      }
      override def onTermination(e: Option[Throwable]) = if (buf.isEmpty) Nil else List(buf)
      def onTimer(timerKey: Any) = emitGroup()
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
   * Discard the given number of elements at the beginning of the stream.
   * No elements will be dropped if `n` is zero or negative.
   */
  def drop(n: Int): Repr[Out] =
    transform("drop", () ⇒ new Transformer[Out, Out] {
      var delegate: Transformer[Out, Out] =
        if (n <= 0) identityTransformer.asInstanceOf[Transformer[Out, Out]]
        else new Transformer[Out, Out] {
          var c = n
          def onNext(in: Out) = {
            c -= 1
            if (c == 0)
              delegate = identityTransformer.asInstanceOf[Transformer[Out, Out]]
            Nil
          }
        }

      def onNext(in: Out) = delegate.onNext(in)
    })

  /**
   * Discard the elements received within the given duration at beginning of the stream.
   */
  def dropWithin(d: FiniteDuration): Repr[Out] =
    timerTransform("dropWithin", () ⇒ new TimerTransformer[Out, Out] {
      scheduleOnce(DropWithinTimerKey, d)

      var delegate: Transformer[Out, Out] =
        new Transformer[Out, Out] {
          def onNext(in: Out) = Nil
        }

      def onNext(in: Out) = delegate.onNext(in)
      def onTimer(timerKey: Any) = {
        delegate = identityTransformer.asInstanceOf[Transformer[Out, Out]]
        Nil
      }
    })

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * number of elements. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * The stream will be completed without producing any elements if `n` is zero
   * or negative.
   */
  def take(n: Int): Repr[Out] =
    transform("take", () ⇒ new Transformer[Out, Out] {
      var delegate: Transformer[Out, Out] =
        if (n <= 0) takeCompletedTransformer.asInstanceOf[Transformer[Out, Out]]
        else new Transformer[Out, Out] {
          var c = n
          def onNext(in: Out) = {
            c -= 1
            if (c == 0)
              delegate = takeCompletedTransformer.asInstanceOf[Transformer[Out, Out]]
            List(in)
          }
        }

      def onNext(in: Out) = delegate.onNext(in)
      override def isComplete = delegate.isComplete
    })

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * duration. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * Note that this can be combined with [[#take]] to limit the number of elements
   * within the duration.
   */
  def takeWithin(d: FiniteDuration): Repr[Out] =
    timerTransform("takeWithin", () ⇒ new TimerTransformer[Out, Out] {
      scheduleOnce(TakeWithinTimerKey, d)

      var delegate: Transformer[Out, Out] = identityTransformer.asInstanceOf[Transformer[Out, Out]]

      def onNext(in: Out) = delegate.onNext(in)
      override def isComplete = delegate.isComplete
      def onTimer(timerKey: Any) = {
        delegate = takeCompletedTransformer.asInstanceOf[Transformer[Out, Out]]
        Nil
      }
    })

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by conflating elements into a summary
   * until the subscriber is ready to accept them. For example a conflate step might average incoming numbers if the
   * upstream publisher is faster.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * @param seed Provides the first state for a conflated value using the first unconsumed element as a start
   * @param aggregate Takes the currently aggregated value and the current pending element to produce a new aggregate
   */
  def conflate[S](seed: Out ⇒ S, aggregate: (S, Out) ⇒ S): Repr[S] =
    andThen(Conflate(seed.asInstanceOf[Any ⇒ Any], aggregate.asInstanceOf[(Any, Any) ⇒ Any]))

  /**
   * Allows a faster downstream to progress independently of a slower publisher by extrapolating elements from an older
   * element until new element comes from the upstream. For example an expand step might repeat the last element for
   * the subscriber until it receives an update from upstream.
   *
   * This element will never "drop" upstream elements as all elements go through at least one extrapolation step.
   * This means that if the upstream is actually faster than the upstream it will be backpressured by the downstream
   * subscriber.
   *
   * @param seed Provides the first state for extrapolation using the first unconsumed element
   * @param extrapolate Takes the current extrapolation state to produce an output element and the next extrapolation
   *                    state.
   */
  def expand[S, U](seed: Out ⇒ S, extrapolate: S ⇒ (U, S)): Repr[U] =
    andThen(Expand(seed.asInstanceOf[Any ⇒ Any], extrapolate.asInstanceOf[Any ⇒ (Any, Any)]))

  /**
   * Adds a fixed size buffer in the flow that allows to store elements from a faster upstream until it becomes full.
   * Depending on the defined [[OverflowStrategy]] it might drop elements or backpressure the upstream if there is no
   * space available
   *
   * @param size The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def buffer(size: Int, overflowStrategy: OverflowStrategy): Repr[Out] = {
    require(size > 0, s"Buffer size must be larger than zero but was [$size]")
    andThen(Buffer(size, overflowStrategy))
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
  def transform[T](name: String, mkTransformer: () ⇒ Transformer[Out, T]): Repr[T] = {
    andThen(Transform(name, mkTransformer.asInstanceOf[() ⇒ Transformer[Any, Any]]))
  }

  /**
   * Takes up to `n` elements from the stream and returns a pair containing a strict sequence of the taken element
   * and a stream representing the remaining elements. If ''n'' is zero or negative, then this will return a pair
   * of an empty collection and a stream containing the whole upstream unchanged.
   */
  def prefixAndTail[U >: Out](n: Int): Repr[(immutable.Seq[Out], Source[U])] =
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
  def groupBy[K, U >: Out](f: Out ⇒ K): Repr[(K, Source[U])] =
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
  def splitWhen[U >: Out](p: Out ⇒ Boolean): Repr[Source[U]] =
    andThen(SplitWhen(p.asInstanceOf[Any ⇒ Boolean]))

  /**
   * Transforms a stream of streams into a contiguous stream of elements using the provided flattening strategy.
   * This operation can be used on a stream of element type [[Source]].
   */
  def flatten[U](strategy: FlattenStrategy[Out, U]): Repr[U] = strategy match {
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
  def timerTransform[U](name: String, mkTransformer: () ⇒ TimerTransformer[Out, U]): Repr[U] =
    andThen(TimerTransform(name, mkTransformer.asInstanceOf[() ⇒ TimerTransformer[Any, Any]]))

  /** INTERNAL API */
  // Storing ops in reverse order
  protected def andThen[U](op: AstNode): Repr[U]
}

/**
 * INTERNAL API
 */
private[scaladsl2] object FlowOps {
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
