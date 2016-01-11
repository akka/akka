/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.impl.Stages.{ MaterializingStageFactory, StageModule }
import akka.stream.impl.StreamLayout.{ EmptyModule, Module }
import akka.stream._
import akka.stream.scaladsl.OperationAttributes._
import akka.util.Collections.EmptyImmutableSeq
import org.reactivestreams.Processor
import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.concurrent.Future
import scala.language.higherKinds
import akka.stream.stage._
import akka.stream.impl.{ Stages, StreamLayout, FlowModule }

/**
 * A `Flow` is a set of stream processing steps that has one open input and one open output.
 */
final class Flow[-In, +Out, +Mat](private[stream] override val module: Module)
  extends FlowOps[Out, Mat] with Graph[FlowShape[In, Out], Mat] {

  override val shape: FlowShape[In, Out] = module.shape.asInstanceOf[FlowShape[In, Out]]

  override type Repr[+O, +M] = Flow[In @uncheckedVariance, O, M]

  private[stream] def isIdentity: Boolean = this.module.isInstanceOf[Stages.Identity]

  /**
   * Transform this [[Flow]] by appending the given processing steps.
   * {{{
   *     +----------------------------+
   *     | Resulting Flow             |
   *     |                            |
   *     |  +------+        +------+  |
   *     |  |      |        |      |  |
   * In ~~> | this | ~Out~> | flow | ~~> T
   *     |  |      |        |      |  |
   *     |  +------+        +------+  |
   *     +----------------------------+
   * }}}
   * The materialized value of the combined [[Flow]] will be the materialized
   * value of the current flow (ignoring the other Flow’s value), use
   * [[Flow#viaMat viaMat]] if a different strategy is needed.
   */
  def via[T, Mat2](flow: Flow[Out, T, Mat2]): Flow[In, T, Mat] = viaMat(flow)(Keep.left)

  /**
   * Transform this [[Flow]] by appending the given processing steps.
   * {{{
   *     +----------------------------+
   *     | Resulting Flow             |
   *     |                            |
   *     |  +------+        +------+  |
   *     |  |      |        |      |  |
   * In ~~> | this | ~Out~> | flow | ~~> T
   *     |  |      |        |      |  |
   *     |  +------+        +------+  |
   *     +----------------------------+
   * }}}
   * The `combine` function is used to compose the materialized values of this flow and that
   * flow into the materialized value of the resulting Flow.
   */
  def viaMat[T, Mat2, Mat3](flow: Flow[Out, T, Mat2])(combine: (Mat, Mat2) ⇒ Mat3): Flow[In, T, Mat3] = {
    if (this.isIdentity) flow.asInstanceOf[Flow[In, T, Mat3]]
    else {
      val flowCopy = flow.module.carbonCopy
      new Flow(
        module
          .growConnect(flowCopy, shape.outlet, flowCopy.shape.inlets.head, combine)
          .replaceShape(FlowShape(shape.inlet, flowCopy.shape.outlets.head)))
    }
  }

  /**
   * Connect this [[Flow]] to a [[Sink]], concatenating the processing steps of both.
   * {{{
   *     +----------------------------+
   *     | Resulting Sink             |
   *     |                            |
   *     |  +------+        +------+  |
   *     |  |      |        |      |  |
   * In ~~> | flow | ~Out~> | sink |  |
   *     |  |      |        |      |  |
   *     |  +------+        +------+  |
   *     +----------------------------+
   * }}}
   * The materialized value of the combined [[Sink]] will be the materialized
   * value of the current flow (ignoring the given Sink’s value), use
   * [[Flow#toMat[Mat2* toMat]] if a different strategy is needed.
   */
  def to[Mat2](sink: Sink[Out, Mat2]): Sink[In, Mat] = {
    toMat(sink)(Keep.left)
  }

  /**
   * Connect this [[Flow]] to a [[Sink]], concatenating the processing steps of both.
   * {{{
   *     +----------------------------+
   *     | Resulting Sink             |
   *     |                            |
   *     |  +------+        +------+  |
   *     |  |      |        |      |  |
   * In ~~> | flow | ~Out~> | sink |  |
   *     |  |      |        |      |  |
   *     |  +------+        +------+  |
   *     +----------------------------+
   * }}}
   * The `combine` function is used to compose the materialized values of this flow and that
   * Sink into the materialized value of the resulting Sink.
   */
  def toMat[Mat2, Mat3](sink: Sink[Out, Mat2])(combine: (Mat, Mat2) ⇒ Mat3): Sink[In, Mat3] = {
    if (isIdentity) sink.asInstanceOf[Sink[In, Mat3]]
    else {
      val sinkCopy = sink.module.carbonCopy
      new Sink(
        module
          .growConnect(sinkCopy, shape.outlet, sinkCopy.shape.inlets.head, combine)
          .replaceShape(SinkShape(shape.inlet)))
    }
  }

  /**
   * Transform the materialized value of this Flow, leaving all other properties as they were.
   */
  def mapMaterialized[Mat2](f: Mat ⇒ Mat2): Repr[Out, Mat2] =
    new Flow(module.transformMaterializedValue(f.asInstanceOf[Any ⇒ Any]))

  /**
   * Join this [[Flow]] to another [[Flow]], by cross connecting the inputs and outputs, creating a [[RunnableFlow]].
   * {{{
   * +------+        +-------+
   * |      | ~Out~> |       |
   * | this |        | other |
   * |      | <~In~  |       |
   * +------+        +-------+
   * }}}
   * The materialized value of the combined [[Flow]] will be the materialized
   * value of the current flow (ignoring the other Flow’s value), use
   * [[Flow#joinMat[Mat2* joinMat]] if a different strategy is needed.
   */
  def join[Mat2](flow: Flow[Out, In, Mat2]): RunnableFlow[Mat] = joinMat(flow)(Keep.left)

  /**
   * Join this [[Flow]] to another [[Flow]], by cross connecting the inputs and outputs, creating a [[RunnableFlow]]
   * {{{
   * +------+        +-------+
   * |      | ~Out~> |       |
   * | this |        | other |
   * |      | <~In~  |       |
   * +------+        +-------+
   * }}}
   * The `combine` function is used to compose the materialized values of this flow and that
   * Flow into the materialized value of the resulting Flow.
   */
  def joinMat[Mat2, Mat3](flow: Flow[Out, In, Mat2])(combine: (Mat, Mat2) ⇒ Mat3): RunnableFlow[Mat3] = {
    val flowCopy = flow.module.carbonCopy
    RunnableFlow(
      module
        .grow(flowCopy, combine)
        .connect(shape.outlet, flowCopy.shape.inlets.head)
        .connect(flowCopy.shape.outlets.head, shape.inlet))
  }

  /**
   * Join this [[Flow]] to a [[BidiFlow]] to close off the “top” of the protocol stack:
   * {{{
   * +---------------------------+
   * | Resulting Flow            |
   * |                           |
   * | +------+        +------+  |
   * | |      | ~Out~> |      | ~~> O2
   * | | flow |        | bidi |  |
   * | |      | <~In~  |      | <~~ I2
   * | +------+        +------+  |
   * +---------------------------+
   * }}}
   * The materialized value of the combined [[Flow]] will be the materialized
   * value of the current flow (ignoring the [[BidiFlow]]’s value), use
   * [[Flow#joinMat[I2* joinMat]] if a different strategy is needed.
   */
  def join[I2, O2, Mat2](bidi: BidiFlow[Out, O2, I2, In, Mat2]): Flow[I2, O2, Mat] = joinMat(bidi)(Keep.left)

  /**
   * Join this [[Flow]] to a [[BidiFlow]] to close off the “top” of the protocol stack:
   * {{{
   * +---------------------------+
   * | Resulting Flow            |
   * |                           |
   * | +------+        +------+  |
   * | |      | ~Out~> |      | ~~> O2
   * | | flow |        | bidi |  |
   * | |      | <~In~  |      | <~~ I2
   * | +------+        +------+  |
   * +---------------------------+
   * }}}
   * The `combine` function is used to compose the materialized values of this flow and that
   * [[BidiFlow]] into the materialized value of the resulting [[Flow]].
   */
  def joinMat[I2, O2, Mat2, M](bidi: BidiFlow[Out, O2, I2, In, Mat2])(combine: (Mat, Mat2) ⇒ M): Flow[I2, O2, M] = {
    val copy = bidi.module.carbonCopy
    val ins = copy.shape.inlets
    val outs = copy.shape.outlets
    new Flow(module
      .grow(copy, combine)
      .connect(shape.outlet, ins(0))
      .connect(outs(1), shape.inlet)
      .replaceShape(FlowShape(ins(1), outs(0))))
  }

  /**
   * Concatenate the given [[Source]] to this [[Flow]], meaning that once this
   * Flow’s input is exhausted and all result elements have been generated,
   * the Source’s elements will be produced. Note that the Source is materialized
   * together with this Flow and just kept from producing elements by asserting
   * back-pressure until its time comes.
   *
   * The resulting Flow’s materialized value is a Tuple2 containing both materialized
   * values (of this Flow and that Source).
   */
  def concat[Out2 >: Out, Mat2](source: Source[Out2, Mat2]): Flow[In, Out2, (Mat, Mat2)] = {
    this.viaMat(Flow(source) { implicit builder ⇒
      s ⇒
        import FlowGraph.Implicits._
        val concat = builder.add(Concat[Out2]())
        s.outlet ~> concat.in(1)
        (concat.in(0), concat.out)
    })(Keep.both)
  }

  /** INTERNAL API */
  override private[stream] def andThen[U](op: StageModule): Repr[U, Mat] = {
    //No need to copy here, op is a fresh instanc
    if (this.isIdentity) new Flow(op).asInstanceOf[Repr[U, Mat]]
    else new Flow(module.growConnect(op, shape.outlet, op.inPort).replaceShape(FlowShape(shape.inlet, op.outPort)))
  }

  private[stream] def andThenMat[U, Mat2](op: MaterializingStageFactory): Repr[U, Mat2] = {
    if (this.isIdentity) new Flow(op).asInstanceOf[Repr[U, Mat2]]
    else new Flow(module.growConnect(op, shape.outlet, op.inPort, Keep.right).replaceShape(FlowShape(shape.inlet, op.outPort)))
  }

  private[stream] def andThenMat[U, Mat2, O >: Out](processorFactory: () ⇒ (Processor[O, U], Mat2)): Repr[U, Mat2] = {
    val op = Stages.DirectProcessor(processorFactory.asInstanceOf[() ⇒ (Processor[Any, Any], Any)])
    if (this.isIdentity) new Flow(op).asInstanceOf[Repr[U, Mat2]]
    else new Flow[In, U, Mat2](module.growConnect(op, shape.outlet, op.inPort, Keep.right).replaceShape(FlowShape(shape.inlet, op.outPort)))
  }

  /**
   * Change the attributes of this [[Flow]] to the given ones. Note that this
   * operation has no effect on an empty Flow (because the attributes apply
   * only to the contained processing stages).
   */
  override def withAttributes(attr: OperationAttributes): Repr[Out, Mat] = {
    if (this.module eq EmptyModule) this
    else new Flow(module.withAttributes(attr).wrap())
  }

  /**
   * Connect the `Source` to this `Flow` and then connect it to the `Sink` and run it. The returned tuple contains
   * the materialized values of the `Source` and `Sink`, e.g. the `Subscriber` of a of a [[Source#subscriber]] and
   * and `Publisher` of a [[Sink#publisher]].
   */
  def runWith[Mat1, Mat2](source: Source[In, Mat1], sink: Sink[Out, Mat2])(implicit materializer: FlowMaterializer): (Mat1, Mat2) = {
    source.via(this).toMat(sink)(Keep.both).run()
  }

  // FIXME remove (in favor of .via)
  def section[O, O2 >: Out, Mat2, Mat3](attributes: OperationAttributes, combine: (Mat, Mat2) ⇒ Mat3)(section: Flow[O2, O2, Unit] ⇒ Flow[O2, O, Mat2]): Flow[In, O, Mat3] = {
    val subFlow = section(Flow[O2]).module.carbonCopy.withAttributes(attributes).wrap()
    if (this.isIdentity) new Flow(subFlow).asInstanceOf[Flow[In, O, Mat3]]
    else new Flow(
      module
        .growConnect(subFlow, shape.outlet, subFlow.shape.inlets.head, combine)
        .replaceShape(FlowShape(shape.inlet, subFlow.shape.outlets.head)))
  }

  /**
   * Applies given [[OperationAttributes]] to a given section.
   */
  // FIXME remove (in favor of .via)
  def section[O, O2 >: Out, Mat2](attributes: OperationAttributes)(section: Flow[O2, O2, Unit] ⇒ Flow[O2, O, Mat2]): Flow[In, O, Mat2] = {
    this.section[O, O2, Mat2, Mat2](attributes, Keep.right)(section)
  }

}

object Flow extends FlowApply {

  private def shape[I, O](name: String): FlowShape[I, O] = FlowShape(new Inlet(name + ".in"), new Outlet(name + ".out"))

  /**
   * Helper to create `Flow` without a [[Source]] or a [[Sink]].
   * Example usage: `Flow[Int]`
   */
  def apply[T]: Flow[T, T, Unit] = new Flow[Any, Any, Any](Stages.Identity()).asInstanceOf[Flow[T, T, Unit]]

  /**
   * A graph with the shape of a flow logically is a flow, this method makes
   * it so also in type.
   */
  def wrap[I, O, M](g: Graph[FlowShape[I, O], M]): Flow[I, O, M] = new Flow(g.module)

}

/**
 * Flow with attached input and output, can be executed.
 */
case class RunnableFlow[+Mat](private[stream] val module: StreamLayout.Module) extends Graph[ClosedShape, Mat] {
  assert(module.isRunnable)
  def shape = ClosedShape

  /**
   * Transform only the materialized value of this RunnableFlow, leaving all other properties as they were.
   */
  def mapMaterialized[Mat2](f: Mat ⇒ Mat2): RunnableFlow[Mat2] =
    copy(module.transformMaterializedValue(f.asInstanceOf[Any ⇒ Any]))

  /**
   * Run this flow and return the materialized instance from the flow.
   */
  def run()(implicit materializer: FlowMaterializer): Mat = materializer.materialize(this)
}

/**
 * Scala API: Operations offered by Sources and Flows with a free output side: the DSL flows left-to-right only.
 */
trait FlowOps[+Out, +Mat] {
  import akka.stream.impl.Stages._
  import FlowOps._
  type Repr[+O, +M] <: FlowOps[O, M]

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   */
  def map[T](f: Out ⇒ T): Repr[T, Mat] = andThen(Map(f.asInstanceOf[Any ⇒ Any]))

  /**
   * Transform each input element into a sequence of output elements that is
   * then flattened into the output stream.
   */
  def mapConcat[T](f: Out ⇒ immutable.Seq[T]): Repr[T, Mat] = andThen(MapConcat(f.asInstanceOf[Any ⇒ immutable.Seq[Any]]))

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `Future` and the
   * value of that future will be emitted downstreams. As many futures as requested elements by
   * downstream may run in parallel and may complete in any order, but the elements that
   * are emitted downstream are in the same order as received from upstream.
   *
   * If the group by function `f` throws an exception or if the `Future` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision.Stop]]
   * the stream will be completed with failure.
   *
   * If the group by function `f` throws an exception or if the `Future` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision.Resume]] or
   * [[akka.stream.Supervision.Restart]] the element is dropped and the stream continues.
   *
   * @see [[#mapAsyncUnordered]]
   */
  def mapAsync[T](f: Out ⇒ Future[T]): Repr[T, Mat] =
    andThen(MapAsync(f.asInstanceOf[Any ⇒ Future[Any]]))

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `Future` and the
   * value of that future will be emitted downstreams. As many futures as requested elements by
   * downstream may run in parallel and each processed element will be emitted dowstream
   * as soon as it is ready, i.e. it is possible that the elements are not emitted downstream
   * in the same order as received from upstream.
   *
   * If the group by function `f` throws an exception or if the `Future` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision.Stop]]
   * the stream will be completed with failure.
   *
   * If the group by function `f` throws an exception or if the `Future` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision.Resume]] or
   * [[akka.stream.Supervision.Restart]] the element is dropped and the stream continues.
   *
   * @see [[#mapAsync]]
   */
  def mapAsyncUnordered[T](f: Out ⇒ Future[T]): Repr[T, Mat] =
    andThen(MapAsyncUnordered(f.asInstanceOf[Any ⇒ Future[Any]]))

  /**
   * Only pass on those elements that satisfy the given predicate.
   */
  def filter(p: Out ⇒ Boolean): Repr[Out, Mat] = andThen(Filter(p.asInstanceOf[Any ⇒ Boolean]))

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step.
   * Non-matching elements are filtered out.
   */
  def collect[T](pf: PartialFunction[Out, T]): Repr[T, Mat] = andThen(Collect(pf.asInstanceOf[PartialFunction[Any, Any]]))

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * `n` must be positive, otherwise IllegalArgumentException is thrown.
   */
  def grouped(n: Int): Repr[immutable.Seq[Out], Mat] = andThen(Grouped(n))

  /**
   * Similar to `fold` but is not a terminal operation,
   * emits its current value which starts at `zero` and then
   * applies the current and next value to the given function `f`,
   * emitting the next current value.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision.Restart]] current value starts at `zero` again
   * the stream will continue.
   */
  def scan[T](zero: T)(f: (T, Out) ⇒ T): Repr[T, Mat] = andThen(Scan(zero, f.asInstanceOf[(Any, Any) ⇒ Any]))

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
  def groupedWithin(n: Int, d: FiniteDuration): Repr[Out, Mat]#Repr[immutable.Seq[Out], Mat] = {
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
  def drop(n: Long): Repr[Out, Mat] = andThen(Drop(n))

  /**
   * Discard the elements received within the given duration at beginning of the stream.
   */
  def dropWithin(d: FiniteDuration): Repr[Out, Mat]#Repr[Out, Mat] =
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
  def take(n: Long): Repr[Out, Mat] = andThen(Take(n))

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * duration. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * Note that this can be combined with [[#take]] to limit the number of elements
   * within the duration.
   */
  def takeWithin(d: FiniteDuration): Repr[Out, Mat]#Repr[Out, Mat] =
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
  def conflate[S](seed: Out ⇒ S)(aggregate: (S, Out) ⇒ S): Repr[S, Mat] =
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
   * Expand does not support [[akka.stream.Supervision.Restart]] and [[akka.stream.Supervision.Resume]].
   * Exceptions from the `seed` or `extrapolate` functions will complete the stream with failure.
   *
   * @param seed Provides the first state for extrapolation using the first unconsumed element
   * @param extrapolate Takes the current extrapolation state to produce an output element and the next extrapolation
   *                    state.
   */
  def expand[S, U](seed: Out ⇒ S)(extrapolate: S ⇒ (U, S)): Repr[U, Mat] =
    andThen(Expand(seed.asInstanceOf[Any ⇒ Any], extrapolate.asInstanceOf[Any ⇒ (Any, Any)]))

  /**
   * Adds a fixed size buffer in the flow that allows to store elements from a faster upstream until it becomes full.
   * Depending on the defined [[akka.stream.OverflowStrategy]] it might drop elements or backpressure the upstream if
   * there is no space available
   *
   * @param size The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def buffer(size: Int, overflowStrategy: OverflowStrategy): Repr[Out, Mat] =
    andThen(Buffer(size, overflowStrategy))

  /**
   * Generic transformation of a stream with a custom processing [[akka.stream.stage.Stage]].
   * This operator makes it possible to extend the `Flow` API when there is no specialized
   * operator that performs the transformation.
   */
  def transform[T](mkStage: () ⇒ Stage[Out, T]): Repr[T, Mat] =
    andThen(StageFactory(mkStage))

  private[akka] def transformMaterializing[T, M](mkStageAndMaterialized: () ⇒ (Stage[Out, T], M)): Repr[T, M] =
    andThenMat(MaterializingStageFactory(mkStageAndMaterialized))

  /**
   * Takes up to `n` elements from the stream and returns a pair containing a strict sequence of the taken element
   * and a stream representing the remaining elements. If ''n'' is zero or negative, then this will return a pair
   * of an empty collection and a stream containing the whole upstream unchanged.
   */
  def prefixAndTail[U >: Out](n: Int): Repr[(immutable.Seq[Out], Source[U, Unit]), Mat] =
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
   *
   * If the group by function `f` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Stop]] the stream and substreams will be completed
   * with failure.
   *
   * If the group by function `f` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Resume]] or [[akka.stream.Supervision.Restart]]
   * the element is dropped and the stream and substreams continue.
   */
  def groupBy[K, U >: Out](f: Out ⇒ K): Repr[(K, Source[U, Unit]), Mat] =
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
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Stop]] the stream and substreams will be completed
   * with failure.
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Resume]] or [[akka.stream.Supervision.Restart]]
   * the element is dropped and the stream and substreams continue.
   */
  def splitWhen[U >: Out](p: Out ⇒ Boolean): Repr[Source[U, Unit], Mat] =
    andThen(SplitWhen(p.asInstanceOf[Any ⇒ Boolean]))

  /**
   * Transforms a stream of streams into a contiguous stream of elements using the provided flattening strategy.
   * This operation can be used on a stream of element type [[akka.stream.scaladsl.Source]].
   */
  def flatten[U](strategy: akka.stream.FlattenStrategy[Out, U]): Repr[U, Mat] = strategy match {
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
   * After normal completion or failure the [[akka.stream.TransformerLike#cleanup]] function is called.
   *
   * It is possible to keep state in the concrete [[akka.stream.Transformer]] instance with
   * ordinary instance variables. The [[akka.stream.Transformer]] is executed by an actor and
   * therefore you do not have to add any additional thread safety or memory
   * visibility constructs to access the state from the callback methods.
   *
   * Note that you can use [[#transform]] if you just need to transform elements time plays no role in the transformation.
   */
  private[akka] def timerTransform[U](mkStage: () ⇒ TimerTransformer[Out, U]): Repr[U, Mat] =
    andThen(TimerTransform(mkStage.asInstanceOf[() ⇒ TimerTransformer[Any, Any]]))

  def withAttributes(attr: OperationAttributes): Repr[Out, Mat]

  def named(name: String): Repr[Out, Mat] = withAttributes(OperationAttributes.name(name))

  /** INTERNAL API */
  private[scaladsl] def andThen[U](op: StageModule): Repr[U, Mat]

  private[scaladsl] def andThenMat[U, Mat2](op: MaterializingStageFactory): Repr[U, Mat2]
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

}
