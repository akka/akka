/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.impl.Ast._
import akka.stream.scaladsl.OperationAttributes._
import akka.stream.{ TimerTransformer, TransformerLike, OverflowStrategy }
import akka.util.Collections.EmptyImmutableSeq
import scala.collection.immutable
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.concurrent.Future
import scala.language.higherKinds
import akka.stream.FlowMaterializer
import akka.stream.FlattenStrategy
import akka.stream.stage._

/**
 * A `Flow` is a set of stream processing steps that has one open input and one open output.
 */
trait Flow[-In, +Out] extends FlowOps[Out] {
  override type Repr[+O] <: Flow[In, O]

  /**
   * Transform this [[Flow]] by appending the given processing steps.
   */
  def via[T](flow: Flow[Out, T]): Flow[In, T]

  /**
   * Connect this [[Flow]] to a [[Sink]], concatenating the processing steps of both.
   */
  def to(sink: Sink[Out]): Sink[In]

  /**
   * Join this [[Flow]] to another [[Flow]], by cross connecting the inputs and outputs, creating a [[RunnableFlow]]
   */
  def join(flow: Flow[Out, In]): RunnableFlow

  /**
   *
   * Connect the `Source` to this `Flow` and then connect it to the `Sink` and run it. The returned tuple contains
   * the materialized values of the `Source` and `Sink`, e.g. the `Subscriber` of a [[SubscriberSource]] and
   * and `Publisher` of a [[PublisherSink]].
   */
  def runWith(source: Source[In], sink: Sink[Out])(implicit materializer: FlowMaterializer): (source.MaterializedType, sink.MaterializedType) = {
    val m = source.via(this).to(sink).run()
    (m.get(source), m.get(sink))
  }

  /**
   * Returns a new `Flow` that concatenates a secondary `Source` to this flow so that,
   * the first element emitted by the given ("second") source is emitted after the last element of this Flow.
   */
  def concat(second: Source[In]): Flow[In, Out] = {
    Flow() { b ⇒
      val concatter = Concat[Out]
      val source = UndefinedSource[In]
      val sink = UndefinedSink[Out]

      b.addEdge(source, this, concatter.first)
        .addEdge(second, this, concatter.second)
        .addEdge(concatter.out, sink)

      source → sink
    }
  }

  /**
   * Add a key that will have a value available after materialization.
   * The key can only use other keys if they have been added to the flow
   * before this key.
   */
  def withKey(key: Key): Flow[In, Out]

  /**
   * Applies given [[OperationAttributes]] to a given section.
   */
  def section[I <: In, O](attributes: OperationAttributes)(section: Flow[In, Out] ⇒ Flow[I, O]): Flow[I, O] =
    section(this.withAttributes(attributes)).withAttributes(OperationAttributes.none)

}

object Flow {
  /**
   * Creates an empty `Flow` of type `T`
   */
  def empty[T]: Flow[T, T] = Pipe.empty[T]

  /**
   * Helper to create `Flow` without a [[Source]] or a [[Sink]].
   * Example usage: `Flow[Int]`
   */
  def apply[T]: Flow[T, T] = Pipe.empty[T]

  /**
   * Creates a `Flow` by using an empty [[FlowGraphBuilder]] on a block that expects a [[FlowGraphBuilder]] and
   * returns the `UndefinedSource` and `UndefinedSink`.
   */
  def apply[I, O]()(block: FlowGraphBuilder ⇒ (UndefinedSource[I], UndefinedSink[O])): Flow[I, O] =
    createFlowFromBuilder(new FlowGraphBuilder(), block)

  /**
   * Creates a `Flow` by using a [[FlowGraphBuilder]] from this [[PartialFlowGraph]] on a block that expects
   * a [[FlowGraphBuilder]] and returns the `UndefinedSource` and `UndefinedSink`.
   */
  def apply[I, O](graph: PartialFlowGraph)(block: FlowGraphBuilder ⇒ (UndefinedSource[I], UndefinedSink[O])): Flow[I, O] =
    createFlowFromBuilder(new FlowGraphBuilder(graph), block)

  private def createFlowFromBuilder[I, O](builder: FlowGraphBuilder,
                                          block: FlowGraphBuilder ⇒ (UndefinedSource[I], UndefinedSink[O])): Flow[I, O] = {
    val (in, out) = block(builder)
    builder.partialBuild().toFlow(in, out)
  }

  /**
   * Create a [[Flow]] from a seemingly disconnected [[Source]] and [[Sink]] pair.
   */
  def apply[I, O](sink: Sink[I], source: Source[O]): Flow[I, O] = GraphFlow(sink, source)
}

/**
 * Flow with attached input and output, can be executed.
 */
trait RunnableFlow {
  def run()(implicit materializer: FlowMaterializer): MaterializedMap
}

/**
 * Scala API: Operations offered by Sources and Flows with a free output side: the DSL flows left-to-right only.
 */
trait FlowOps[+Out] {
  import FlowOps._
  type Repr[+O] <: FlowOps[O]

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   */
  def map[T](f: Out ⇒ T): Repr[T] = andThen(Map(f.asInstanceOf[Any ⇒ Any]))

  /**
   * Transform each input element into a sequence of output elements that is
   * then flattened into the output stream.
   */
  def mapConcat[T](f: Out ⇒ immutable.Seq[T]): Repr[T] = andThen(MapConcat(f.asInstanceOf[Any ⇒ immutable.Seq[Any]]))

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
  def filter(p: Out ⇒ Boolean): Repr[Out] = andThen(Filter(p.asInstanceOf[Any ⇒ Boolean]))

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step.
   * Non-matching elements are filtered out.
   */
  def collect[T](pf: PartialFunction[Out, T]): Repr[T] = andThen(Collect(pf.asInstanceOf[PartialFunction[Any, Any]]))

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * `n` must be positive, otherwise IllegalArgumentException is thrown.
   */
  def grouped(n: Int): Repr[immutable.Seq[Out]] = andThen(Grouped(n))

  /**
   * Similar to `fold` but is not a terminal operation,
   * emits its current value which starts at `zero` and then
   * applies the current and next value to the given function `f`,
   * emitting the next current value.
   */
  def scan[T](zero: T)(f: (T, Out) ⇒ T): Repr[T] = andThen(Scan(zero, f.asInstanceOf[(Any, Any) ⇒ Any]))

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
  def groupedWithin(n: Int, d: FiniteDuration): Repr[Out]#Repr[immutable.Seq[Out]] = {
    require(n > 0, "n must be greater than 0")
    require(d > Duration.Zero)
    withAttributes(name("groupedWithin")).timerTransform(() ⇒ new TimerTransformer[Out, immutable.Seq[Out]] {
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
  def drop(n: Int): Repr[Out] = andThen(Drop(n))

  /**
   * Discard the elements received within the given duration at beginning of the stream.
   */
  def dropWithin(d: FiniteDuration): Repr[Out]#Repr[Out] =
    withAttributes(name("dropWithin")).timerTransform(() ⇒ new TimerTransformer[Out, Out] {
      scheduleOnce(DropWithinTimerKey, d)

      var delegate: TransformerLike[Out, Out] =
        new TransformerLike[Out, Out] {
          def onNext(in: Out) = Nil
        }

      def onNext(in: Out) = delegate.onNext(in)
      def onTimer(timerKey: Any) = {
        delegate = FlowOps.identityTransformer[Out]
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
  def take(n: Int): Repr[Out] = andThen(Take(n))

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * duration. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * Note that this can be combined with [[#take]] to limit the number of elements
   * within the duration.
   */
  def takeWithin(d: FiniteDuration): Repr[Out]#Repr[Out] =
    withAttributes(name("takeWithin")).timerTransform(() ⇒ new TimerTransformer[Out, Out] {
      scheduleOnce(TakeWithinTimerKey, d)

      var delegate: TransformerLike[Out, Out] = FlowOps.identityTransformer[Out]

      override def onNext(in: Out) = delegate.onNext(in)
      override def isComplete = delegate.isComplete
      override def onTimer(timerKey: Any) = {
        delegate = FlowOps.completedTransformer[Out]
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
  def conflate[S](seed: Out ⇒ S)(aggregate: (S, Out) ⇒ S): Repr[S] =
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
  def expand[S, U](seed: Out ⇒ S)(extrapolate: S ⇒ (U, S)): Repr[U] =
    andThen(Expand(seed.asInstanceOf[Any ⇒ Any], extrapolate.asInstanceOf[Any ⇒ (Any, Any)]))

  /**
   * Adds a fixed size buffer in the flow that allows to store elements from a faster upstream until it becomes full.
   * Depending on the defined [[akka.stream.OverflowStrategy]] it might drop elements or backpressure the upstream if
   * there is no space available
   *
   * @param size The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def buffer(size: Int, overflowStrategy: OverflowStrategy): Repr[Out] =
    andThen(Buffer(size, overflowStrategy))

  /**
   * Generic transformation of a stream with a custom processing [[akka.stream.stage.Stage]].
   * This operator makes it possible to extend the `Flow` API when there is no specialized
   * operator that performs the transformation.
   */
  def transform[T](mkStage: () ⇒ Stage[Out, T]): Repr[T] =
    andThen(StageFactory(mkStage))

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
   * This operation can be used on a stream of element type [[akka.stream.scaladsl.Source]].
   */
  def flatten[U](strategy: akka.stream.FlattenStrategy[Out, U]): Repr[U] = strategy match {
    case _: FlattenStrategy.Concat[Out] ⇒ andThen(ConcatAll())
    case _ ⇒
      throw new IllegalArgumentException(s"Unsupported flattening strategy [${strategy.getClass.getName}]")
  }

  /**
   * INTERNAL API - meant for removal / rewrite. See https://github.com/akka/akka/issues/16393
   *
   * Transformation of a stream, with additional support for scheduled events.
   *
   * For each element the [[akka.stream.TransformerLike#onNext]]
   * function is invoked, expecting a (possibly empty) sequence of output elements
   * to be produced.
   * After handing off the elements produced from one input element to the downstream
   * subscribers, the [[akka.stream.TransformerLike#isComplete]] predicate determines whether to end
   * stream processing at this point; in that case the upstream subscription is
   * canceled. Before signaling normal completion to the downstream subscribers,
   * the [[akka.stream.TransformerLike#onTermination]] function is invoked to produce a (possibly empty)
   * sequence of elements in response to the end-of-stream event.
   *
   * [[akka.stream.TransformerLike#onError]] is called when failure is signaled from upstream.
   *
   * After normal completion or error the [[akka.stream.TransformerLike#cleanup]] function is called.
   *
   * It is possible to keep state in the concrete [[akka.stream.Transformer]] instance with
   * ordinary instance variables. The [[akka.stream.Transformer]] is executed by an actor and
   * therefore you do not have to add any additional thread safety or memory
   * visibility constructs to access the state from the callback methods.
   *
   * Note that you can use [[#transform]] if you just need to transform elements time plays no role in the transformation.
   */
  private[akka] def timerTransform[U](mkStage: () ⇒ TimerTransformer[Out, U]): Repr[U] =
    andThen(TimerTransform(mkStage.asInstanceOf[() ⇒ TimerTransformer[Any, Any]]))

  /** INTERNAL API */
  private[scaladsl] def withAttributes(attr: OperationAttributes): Repr[Out]

  /** INTERNAL API */
  // Storing ops in reverse order
  private[scaladsl] def andThen[U](op: AstNode): Repr[U]
}

/**
 * INTERNAL API
 */
private[stream] object FlowOps {
  private case object TakeWithinTimerKey
  private case object DropWithinTimerKey
  private case object GroupedWithinTimerKey

  private[this] final case object CompletedTransformer extends TransformerLike[Any, Any] {
    override def onNext(elem: Any) = Nil
    override def isComplete = true
  }

  private[this] final case object IdentityTransformer extends TransformerLike[Any, Any] {
    override def onNext(elem: Any) = List(elem)
  }

  def completedTransformer[T]: TransformerLike[T, T] = CompletedTransformer.asInstanceOf[TransformerLike[T, T]]
  def identityTransformer[T]: TransformerLike[T, T] = IdentityTransformer.asInstanceOf[TransformerLike[T, T]]

  def identityStage[T]: Stage[T, T] = new PushStage[T, T] {
    override def onPush(elem: T, ctx: Context[T]): Directive = ctx.push(elem)
  }
}
