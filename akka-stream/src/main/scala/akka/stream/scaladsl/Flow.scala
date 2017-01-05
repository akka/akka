/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.event.LoggingAdapter
import akka.stream._
import akka.Done
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl._
import akka.stream.impl.fusing._
import akka.stream.stage._
import org.reactivestreams.{ Processor, Publisher, Subscriber, Subscription }
import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.language.higherKinds
import akka.stream.impl.fusing.FlattenMerge

import akka.NotUsed

/**
 * A `Flow` is a set of stream processing steps that has one open input and one open output.
 */
final class Flow[-In, +Out, +Mat](override val module: Module)
  extends FlowOpsMat[Out, Mat] with Graph[FlowShape[In, Out], Mat] {

  override val shape: FlowShape[In, Out] = module.shape.asInstanceOf[FlowShape[In, Out]]

  override def toString: String = s"Flow($shape, $module)"

  override type Repr[+O] = Flow[In @uncheckedVariance, O, Mat @uncheckedVariance]
  override type ReprMat[+O, +M] = Flow[In @uncheckedVariance, O, M]

  override type Closed = Sink[In @uncheckedVariance, Mat @uncheckedVariance]
  override type ClosedMat[+M] = Sink[In @uncheckedVariance, M]

  private[stream] def isIdentity: Boolean = this.module eq GraphStages.Identity.module

  override def via[T, Mat2](flow: Graph[FlowShape[Out, T], Mat2]): Repr[T] = viaMat(flow)(Keep.left)

  override def viaMat[T, Mat2, Mat3](flow: Graph[FlowShape[Out, T], Mat2])(combine: (Mat, Mat2) ⇒ Mat3): Flow[In, T, Mat3] =
    if (this.isIdentity) {
      import Predef.Map.empty
      import StreamLayout.{ CompositeModule, Ignore, IgnorableMatValComp, Transform, Atomic, Combine }
      val m = flow.module
      val mat =
        if (combine == Keep.left) {
          if (IgnorableMatValComp(m)) Ignore else Transform(_ ⇒ NotUsed, Atomic(m))
        } else Combine(combine.asInstanceOf[(Any, Any) ⇒ Any], Ignore, Atomic(m))
      new Flow(CompositeModule(Set(m), m.shape, empty, empty, mat, Attributes.none))
    } else {
      val flowCopy = flow.module.carbonCopy
      new Flow(
        module
          .fuse(flowCopy, shape.out, flowCopy.shape.inlets.head, combine)
          .replaceShape(FlowShape(shape.in, flowCopy.shape.outlets.head)))
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
  def to[Mat2](sink: Graph[SinkShape[Out], Mat2]): Sink[In, Mat] = toMat(sink)(Keep.left)

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
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def toMat[Mat2, Mat3](sink: Graph[SinkShape[Out], Mat2])(combine: (Mat, Mat2) ⇒ Mat3): Sink[In, Mat3] = {
    if (isIdentity)
      Sink.fromGraph(sink.asInstanceOf[Graph[SinkShape[In], Mat2]])
        .mapMaterializedValue(combine(NotUsed.asInstanceOf[Mat], _))
    else {
      val sinkCopy = sink.module.carbonCopy
      new Sink(
        module
          .fuse(sinkCopy, shape.out, sinkCopy.shape.inlets.head, combine)
          .replaceShape(SinkShape(shape.in)))
    }
  }

  /**
   * Transform the materialized value of this Flow, leaving all other properties as they were.
   */
  override def mapMaterializedValue[Mat2](f: Mat ⇒ Mat2): ReprMat[Out, Mat2] =
    new Flow(module.transformMaterializedValue(f.asInstanceOf[Any ⇒ Any]))

  /**
   * Join this [[Flow]] to another [[Flow]], by cross connecting the inputs and outputs, creating a [[RunnableGraph]].
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
  def join[Mat2](flow: Graph[FlowShape[Out, In], Mat2]): RunnableGraph[Mat] = joinMat(flow)(Keep.left)

  /**
   * Join this [[Flow]] to another [[Flow]], by cross connecting the inputs and outputs, creating a [[RunnableGraph]]
   * {{{
   * +------+        +-------+
   * |      | ~Out~> |       |
   * | this |        | other |
   * |      | <~In~  |       |
   * +------+        +-------+
   * }}}
   * The `combine` function is used to compose the materialized values of this flow and that
   * Flow into the materialized value of the resulting Flow.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def joinMat[Mat2, Mat3](flow: Graph[FlowShape[Out, In], Mat2])(combine: (Mat, Mat2) ⇒ Mat3): RunnableGraph[Mat3] = {
    val flowCopy = flow.module.carbonCopy
    RunnableGraph(
      module
        .compose(flowCopy, combine)
        .wire(shape.out, flowCopy.shape.inlets.head)
        .wire(flowCopy.shape.outlets.head, shape.in))
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
  def join[I2, O2, Mat2](bidi: Graph[BidiShape[Out, O2, I2, In], Mat2]): Flow[I2, O2, Mat] = joinMat(bidi)(Keep.left)

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
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def joinMat[I2, O2, Mat2, M](bidi: Graph[BidiShape[Out, O2, I2, In], Mat2])(combine: (Mat, Mat2) ⇒ M): Flow[I2, O2, M] = {
    val copy = bidi.module.carbonCopy
    val ins = copy.shape.inlets
    val outs = copy.shape.outlets
    new Flow(module
      .compose(copy, combine)
      .wire(shape.out, ins.head)
      .wire(outs(1), shape.in)
      .replaceShape(FlowShape(ins(1), outs.head)))
  }

  /**
   * Change the attributes of this [[Flow]] to the given ones and seal the list
   * of attributes. This means that further calls will not be able to remove these
   * attributes, but instead add new ones. Note that this
   * operation has no effect on an empty Flow (because the attributes apply
   * only to the contained processing stages).
   */
  override def withAttributes(attr: Attributes): Repr[Out] =
    if (isIdentity) this
    else new Flow(module.withAttributes(attr))

  /**
   * Add the given attributes to this Flow. Further calls to `withAttributes`
   * will not remove these attributes. Note that this
   * operation has no effect on an empty Flow (because the attributes apply
   * only to the contained processing stages).
   */
  override def addAttributes(attr: Attributes): Repr[Out] = withAttributes(module.attributes and attr)

  /**
   * Add a ``name`` attribute to this Flow.
   */
  override def named(name: String): Repr[Out] = addAttributes(Attributes.name(name))

  /**
   * Put an asynchronous boundary around this `Flow`
   */
  override def async: Repr[Out] = addAttributes(Attributes.asyncBoundary)

  /**
   * Connect the `Source` to this `Flow` and then connect it to the `Sink` and run it. The returned tuple contains
   * the materialized values of the `Source` and `Sink`, e.g. the `Subscriber` of a of a [[Source#subscriber]] and
   * and `Publisher` of a [[Sink#publisher]].
   */
  def runWith[Mat1, Mat2](source: Graph[SourceShape[In], Mat1], sink: Graph[SinkShape[Out], Mat2])(implicit materializer: Materializer): (Mat1, Mat2) =
    Source.fromGraph(source).via(this).toMat(sink)(Keep.both).run()

  /**
   * Converts this Flow to a [[RunnableGraph]] that materializes to a Reactive Streams [[org.reactivestreams.Processor]]
   * which implements the operations encapsulated by this Flow. Every materialization results in a new Processor
   * instance, i.e. the returned [[RunnableGraph]] is reusable.
   *
   * @return A [[RunnableGraph]] that materializes to a Processor when run() is called on it.
   */
  def toProcessor: RunnableGraph[Processor[In @uncheckedVariance, Out @uncheckedVariance]] =
    Source.asSubscriber[In].via(this).toMat(Sink.asPublisher[Out](false))(Keep.both[Subscriber[In], Publisher[Out]])
      .mapMaterializedValue {
        case (sub, pub) ⇒ new Processor[In, Out] {
          override def onError(t: Throwable): Unit = sub.onError(t)
          override def onSubscribe(s: Subscription): Unit = sub.onSubscribe(s)
          override def onComplete(): Unit = sub.onComplete()
          override def onNext(t: In): Unit = sub.onNext(t)
          override def subscribe(s: Subscriber[_ >: Out]): Unit = pub.subscribe(s)
        }
      }

  /** Converts this Scala DSL element to it's Java DSL counterpart. */
  def asJava: javadsl.Flow[In, Out, Mat] = new javadsl.Flow(this)
}

object Flow {
  private[this] val identity: Flow[Any, Any, NotUsed] = new Flow[Any, Any, NotUsed](GraphStages.Identity.module)

  /**
   * Creates a Flow from a Reactive Streams [[org.reactivestreams.Processor]]
   */
  def fromProcessor[I, O](processorFactory: () ⇒ Processor[I, O]): Flow[I, O, NotUsed] = {
    fromProcessorMat(() ⇒ (processorFactory(), NotUsed))
  }

  /**
   * Creates a Flow from a Reactive Streams [[org.reactivestreams.Processor]] and returns a materialized value.
   */
  def fromProcessorMat[I, O, M](processorFactory: () ⇒ (Processor[I, O], M)): Flow[I, O, M] =
    new Flow(ProcessorModule(processorFactory))

  /**
   * Returns a `Flow` which outputs all its inputs.
   */
  def apply[T]: Flow[T, T, NotUsed] = identity.asInstanceOf[Flow[T, T, NotUsed]]

  /**
   * Creates a [Flow] which will use the given function to transform its inputs to outputs. It is equivalent
   * to `Flow[T].map(f)`
   */
  def fromFunction[A, B](f: A ⇒ B): Flow[A, B, NotUsed] = apply[A].map(f)

  /**
   * A graph with the shape of a flow logically is a flow, this method makes
   * it so also in type.
   */
  def fromGraph[I, O, M](g: Graph[FlowShape[I, O], M]): Flow[I, O, M] =
    g match {
      case f: Flow[I, O, M]         ⇒ f
      case f: javadsl.Flow[I, O, M] ⇒ f.asScala
      case other                    ⇒ new Flow(other.module)
    }

  /**
   * Creates a `Flow` from a `Sink` and a `Source` where the Flow's input
   * will be sent to the Sink and the Flow's output will come from the Source.
   */
  def fromSinkAndSource[I, O](sink: Graph[SinkShape[I], _], source: Graph[SourceShape[O], _]): Flow[I, O, NotUsed] =
    fromSinkAndSourceMat(sink, source)(Keep.none)

  /**
   * Creates a `Flow` from a `Sink` and a `Source` where the Flow's input
   * will be sent to the Sink and the Flow's output will come from the Source.
   *
   * The `combine` function is used to compose the materialized values of the `sink` and `source`
   * into the materialized value of the resulting [[Flow]].
   */
  def fromSinkAndSourceMat[I, O, M1, M2, M](sink: Graph[SinkShape[I], M1], source: Graph[SourceShape[O], M2])(combine: (M1, M2) ⇒ M): Flow[I, O, M] =
    fromGraph(GraphDSL.create(sink, source)(combine) { implicit b ⇒ (in, out) ⇒ FlowShape(in.in, out.out) })
}

object RunnableGraph {
  /**
   * A graph with a closed shape is logically a runnable graph, this method makes
   * it so also in type.
   */
  def fromGraph[Mat](g: Graph[ClosedShape, Mat]): RunnableGraph[Mat] =
    g match {
      case r: RunnableGraph[Mat] ⇒ r
      case other                 ⇒ RunnableGraph(other.module)
    }
}
/**
 * Flow with attached input and output, can be executed.
 */
final case class RunnableGraph[+Mat](val module: StreamLayout.Module) extends Graph[ClosedShape, Mat] {
  require(module.isRunnable)
  override def shape = ClosedShape

  /**
   * Transform only the materialized value of this RunnableGraph, leaving all other properties as they were.
   */
  def mapMaterializedValue[Mat2](f: Mat ⇒ Mat2): RunnableGraph[Mat2] =
    copy(module.transformMaterializedValue(f.asInstanceOf[Any ⇒ Any]))

  /**
   * Run this flow and return the materialized instance from the flow.
   */
  def run()(implicit materializer: Materializer): Mat = materializer.materialize(this)

  override def addAttributes(attr: Attributes): RunnableGraph[Mat] =
    withAttributes(module.attributes and attr)

  override def withAttributes(attr: Attributes): RunnableGraph[Mat] =
    new RunnableGraph(module.withAttributes(attr))

  override def named(name: String): RunnableGraph[Mat] =
    addAttributes(Attributes.name(name))

  override def async: RunnableGraph[Mat] =
    addAttributes(Attributes.asyncBoundary)
}

/**
 * Scala API: Operations offered by Sources and Flows with a free output side: the DSL flows left-to-right only.
 *
 * INTERNAL API: this trait will be changed in binary-incompatible ways for classes that are derived from it!
 * Do not implement this interface outside the Akka code base!
 *
 * Binary compatibility is only maintained for callers of this trait’s interface.
 */
trait FlowOps[+Out, +Mat] {
  import akka.stream.impl.Stages._
  import GraphDSL.Implicits._

  type Repr[+O] <: FlowOps[O, Mat] {
    type Repr[+OO] = FlowOps.this.Repr[OO]
    type Closed = FlowOps.this.Closed
  }

  // result of closing a Source is RunnableGraph, closing a Flow is Sink
  type Closed

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
  def via[T, Mat2](flow: Graph[FlowShape[Out, T], Mat2]): Repr[T]

  /**
   * Recover allows to send last element on failure and gracefully complete the stream
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This stage can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * Throwing an exception inside `recover` _will_ be logged on ERROR level automatically.
   *
   * '''Emits when''' element is available from the upstream or upstream is failed and pf returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or upstream failed with exception pf can handle
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def recover[T >: Out](pf: PartialFunction[Throwable, T]): Repr[T] = via(Recover(pf))

  /**
   * RecoverWith allows to switch to alternative Source on flow failure. It will stay in effect after
   * a failure has been recovered so that each time there is a failure it is fed into the `pf` and a new
   * Source may be materialized.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This stage can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * Throwing an exception inside `recoverWith` _will_ be logged on ERROR level automatically.
   *
   * '''Emits when''' element is available from the upstream or upstream is failed and element is available
   * from alternative Source
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or upstream failed with exception pf can handle
   *
   * '''Cancels when''' downstream cancels
   *
   */
  @deprecated("Use recoverWithRetries instead.", "2.4.4")
  def recoverWith[T >: Out](pf: PartialFunction[Throwable, Graph[SourceShape[T], NotUsed]]): Repr[T] =
    via(new RecoverWith(-1, pf))

  /**
   * RecoverWithRetries allows to switch to alternative Source on flow failure. It will stay in effect after
   * a failure has been recovered up to `attempts` number of times so that each time there is a failure
   * it is fed into the `pf` and a new Source may be materialized. Note that if you pass in 0, this won't
   * attempt to recover at all. Passing -1 will behave exactly the same as  `recoverWith`.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This stage can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * Throwing an exception inside `recoverWithRetries` _will_ be logged on ERROR level automatically.
   *
   * '''Emits when''' element is available from the upstream or upstream is failed and element is available
   * from alternative Source
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or upstream failed with exception pf can handle
   *
   * '''Cancels when''' downstream cancels
   *
   * @param attempts Maximum number of retries or -1 to retry indefinitely
   * @param pf Receives the failure cause and returns the new Source to be materialized if any
   * @throws IllegalArgumentException if `attempts` is a negative number other than -1
   *
   */
  def recoverWithRetries[T >: Out](attempts: Int, pf: PartialFunction[Throwable, Graph[SourceShape[T], NotUsed]]): Repr[T] =
    via(new RecoverWith(attempts, pf))

  /**
   * While similar to [[recover]] this stage can be used to transform an error signal to a different one *without* logging
   * it as an error in the process. So in that sense it is NOT exactly equivalent to `recover(t => throw t2)` since recover
   * would log the `t2` error.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This stage can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * Similarily to [[recover]] throwing an exception inside `mapError` _will_ be logged.
   *
   * '''Emits when''' element is available from the upstream or upstream is failed and pf returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or upstream failed with exception pf can handle
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def mapError(pf: PartialFunction[Throwable, Throwable]): Repr[Out] = via(MapError(pf))

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def map[T](f: Out ⇒ T): Repr[T] = via(Map(f))

  /**
   * Transform each input element into an `Iterable` of output elements that is
   * then flattened into the output stream.
   *
   * The returned `Iterable` MUST NOT contain `null` values,
   * as they are illegal as stream elements - according to the Reactive Streams specification.
   *
   * '''Emits when''' the mapping function returns an element or there are still remaining elements
   * from the previously calculated collection
   *
   * '''Backpressures when''' downstream backpressures or there are still remaining elements from the
   * previously calculated collection
   *
   * '''Completes when''' upstream completes and all remaining elements have been emitted
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def mapConcat[T](f: Out ⇒ immutable.Iterable[T]): Repr[T] = statefulMapConcat(() ⇒ f)

  /**
   * Transform each input element into an `Iterable` of output elements that is
   * then flattened into the output stream. The transformation is meant to be stateful,
   * which is enabled by creating the transformation function anew for every materialization —
   * the returned function will typically close over mutable objects to store state between
   * invocations. For the stateless variant see [[FlowOps.mapConcat]].
   *
   * The returned `Iterable` MUST NOT contain `null` values,
   * as they are illegal as stream elements - according to the Reactive Streams specification.
   *
   * '''Emits when''' the mapping function returns an element or there are still remaining elements
   * from the previously calculated collection
   *
   * '''Backpressures when''' downstream backpressures or there are still remaining elements from the
   * previously calculated collection
   *
   * '''Completes when''' upstream completes and all remaining elements has been emitted
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[FlowOps.mapConcat]]
   */
  def statefulMapConcat[T](f: () ⇒ Out ⇒ immutable.Iterable[T]): Repr[T] =
    via(new StatefulMapConcat(f))

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `Future` and the
   * value of that future will be emitted downstream. The number of Futures
   * that shall run in parallel is given as the first argument to ``mapAsync``.
   * These Futures may complete in any order, but the elements that
   * are emitted downstream are in the same order as received from upstream.
   *
   * If the function `f` throws an exception or if the `Future` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision.Stop]]
   * the stream will be completed with failure.
   *
   * If the function `f` throws an exception or if the `Future` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision.Resume]] or
   * [[akka.stream.Supervision.Restart]] the element is dropped and the stream continues.
   *
   * The function `f` is always invoked on the elements in the order they arrive.
   *
   * '''Emits when''' the Future returned by the provided function finishes for the next element in sequence
   *
   * '''Backpressures when''' the number of futures reaches the configured parallelism and the downstream
   * backpressures or the first future is not completed
   *
   * '''Completes when''' upstream completes and all futures have been completed and all elements have been emitted
   *
   * '''Cancels when''' downstream cancels
   *
   * @see [[#mapAsyncUnordered]]
   */
  def mapAsync[T](parallelism: Int)(f: Out ⇒ Future[T]): Repr[T] = via(MapAsync(parallelism, f))

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `Future` and the
   * value of that future will be emitted downstream. The number of Futures
   * that shall run in parallel is given as the first argument to ``mapAsyncUnordered``.
   * Each processed element will be emitted downstream as soon as it is ready, i.e. it is possible
   * that the elements are not emitted downstream in the same order as received from upstream.
   *
   * If the function `f` throws an exception or if the `Future` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision.Stop]]
   * the stream will be completed with failure.
   *
   * If the function `f` throws an exception or if the `Future` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision.Resume]] or
   * [[akka.stream.Supervision.Restart]] the element is dropped and the stream continues.
   *
   * The function `f` is always invoked on the elements in the order they arrive (even though the result of the futures
   * returned by `f` might be emitted in a different order).
   *
   * '''Emits when''' any of the Futures returned by the provided function complete
   *
   * '''Backpressures when''' the number of futures reaches the configured parallelism and the downstream backpressures
   *
   * '''Completes when''' upstream completes and all futures have been completed and all elements have been emitted
   *
   * '''Cancels when''' downstream cancels
   *
   * @see [[#mapAsync]]
   */
  def mapAsyncUnordered[T](parallelism: Int)(f: Out ⇒ Future[T]): Repr[T] = via(MapAsyncUnordered(parallelism, f))

  /**
   * Only pass on those elements that satisfy the given predicate.
   *
   * '''Emits when''' the given predicate returns true for the element
   *
   * '''Backpressures when''' the given predicate returns true for the element and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def filter(p: Out ⇒ Boolean): Repr[Out] = via(Filter(p))

  /**
   * Only pass on those elements that NOT satisfy the given predicate.
   *
   * '''Emits when''' the given predicate returns false for the element
   *
   * '''Backpressures when''' the given predicate returns false for the element and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def filterNot(p: Out ⇒ Boolean): Repr[Out] =
    via(Flow[Out].filter(!p(_)).withAttributes(DefaultAttributes.filterNot))

  /**
   * Terminate processing (and cancel the upstream publisher) after predicate
   * returns false for the first time,
   * Due to input buffering some elements may have been requested from upstream publishers
   * that will then not be processed downstream of this step.
   *
   * The stream will be completed without producing any elements if predicate is false for
   * the first stream element.
   *
   * '''Emits when''' the predicate is true
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' predicate returned false (or 1 after predicate returns false if `inclusive` or upstream completes
   *
   * '''Cancels when''' predicate returned false or downstream cancels
   *
   * See also [[FlowOps.limit]], [[FlowOps.limitWeighted]]
   */
  def takeWhile(p: Out ⇒ Boolean): Repr[Out] = takeWhile(p, false)

  /**
   * Terminate processing (and cancel the upstream publisher) after predicate
   * returns false for the first time, including the first failed element iff inclusive is true
   * Due to input buffering some elements may have been requested from upstream publishers
   * that will then not be processed downstream of this step.
   *
   * The stream will be completed without producing any elements if predicate is false for
   * the first stream element.
   *
   * '''Emits when''' the predicate is true
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' predicate returned false (or 1 after predicate returns false if `inclusive` or upstream completes
   *
   * '''Cancels when''' predicate returned false or downstream cancels
   *
   * See also [[FlowOps.limit]], [[FlowOps.limitWeighted]]
   */
  def takeWhile(p: Out ⇒ Boolean, inclusive: Boolean): Repr[Out] = via(TakeWhile(p, inclusive))

  /**
   * Discard elements at the beginning of the stream while predicate is true.
   * All elements will be taken after predicate returns false first time.
   *
   * '''Emits when''' predicate returned false and for all following stream elements
   *
   * '''Backpressures when''' predicate returned false and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def dropWhile(p: Out ⇒ Boolean): Repr[Out] = via(DropWhile(p))

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step.
   * Non-matching elements are filtered out.
   *
   * '''Emits when''' the provided partial function is defined for the element
   *
   * '''Backpressures when''' the partial function is defined for the element and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def collect[T](pf: PartialFunction[Out, T]): Repr[T] = via(Collect(pf))

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * `n` must be positive, otherwise IllegalArgumentException is thrown.
   *
   * '''Emits when''' the specified number of elements have been accumulated or upstream completed
   *
   * '''Backpressures when''' a group has been assembled and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def grouped(n: Int): Repr[immutable.Seq[Out]] = via(Grouped(n))

  /**
   * Ensure stream boundedness by limiting the number of elements from upstream.
   * If the number of incoming elements exceeds max, it will signal
   * upstream failure `StreamLimitException` downstream.
   *
   * Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * '''Emits when''' upstream emits and the number of emitted elements has not reached max
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes and the number of emitted elements has not reached max
   *
   * '''Errors when''' the total number of incoming element exceeds max
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[FlowOps.take]], [[FlowOps.takeWithin]], [[FlowOps.takeWhile]]
   */
  def limit(max: Long): Repr[Out] = limitWeighted(max)(_ ⇒ 1)

  /**
   * Ensure stream boundedness by evaluating the cost of incoming elements
   * using a cost function. Exactly how many elements will be allowed to travel downstream depends on the
   * evaluated cost of each element. If the accumulated cost exceeds max, it will signal
   * upstream failure `StreamLimitException` downstream.
   *
   * Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * '''Emits when''' upstream emits and the accumulated cost has not reached max
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes and the number of emitted elements has not reached max
   *
   * '''Errors when''' when the accumulated cost exceeds max
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[FlowOps.take]], [[FlowOps.takeWithin]], [[FlowOps.takeWhile]]
   */
  def limitWeighted[T](max: Long)(costFn: Out ⇒ Long): Repr[Out] = via(LimitWeighted(max, costFn))

  /**
   * Apply a sliding window over the stream and return the windows as groups of elements, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * `n` must be positive, otherwise IllegalArgumentException is thrown.
   * `step` must be positive, otherwise IllegalArgumentException is thrown.
   *
   * '''Emits when''' enough elements have been collected within the window or upstream completed
   *
   * '''Backpressures when''' a window has been assembled and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def sliding(n: Int, step: Int = 1): Repr[immutable.Seq[Out]] = via(Sliding(n, step))

  /**
   * Similar to `fold` but is not a terminal operation,
   * emits its current value which starts at `zero` and then
   * applies the current and next value to the given function `f`,
   * emitting the next current value.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision.Restart]] current value starts at `zero` again
   * the stream will continue.
   *
   * '''Emits when''' the function scanning the element returns a new element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[FlowOps.scanAsync]]
   */
  def scan[T](zero: T)(f: (T, Out) ⇒ T): Repr[T] = via(Scan(zero, f))

  /**
   * Similar to `scan` but with a asynchronous function,
   * emits its current value which starts at `zero` and then
   * applies the current and next value to the given function `f`,
   * emitting a `Future` that resolves to the next current value.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision.Restart]] current value starts at `zero` again
   * the stream will continue.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision.Resume]] current value starts at the previous
   * current value, or zero when it doesn't have one, and the stream will continue.
   *
   * '''Emits when''' the future returned by f` completes
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes and the last future returned by `f` completes
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[FlowOps.scan]]
   */
  def scanAsync[T](zero: T)(f: (T, Out) ⇒ Future[T]): Repr[T] = via(ScanAsync(zero, f))

  /**
   * Similar to `scan` but only emits its result when the upstream completes,
   * after which it also completes. Applies the given function towards its current and next value,
   * yielding the next current value.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision.Restart]] current value starts at `zero` again
   * the stream will continue.
   *
   * '''Emits when''' upstream completes
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[FlowOps.scan]]
   */
  def fold[T](zero: T)(f: (T, Out) ⇒ T): Repr[T] = via(Fold(zero, f))

  /**
   * Similar to `fold` but with an asynchronous function.
   * Applies the given function towards its current and next value,
   * yielding the next current value.
   *
   * If the function `f` returns a failure and the supervision decision is
   * [[akka.stream.Supervision.Restart]] current value starts at `zero` again
   * the stream will continue.
   *
   * '''Emits when''' upstream completes
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[FlowOps.fold]]
   */
  def foldAsync[T](zero: T)(f: (T, Out) ⇒ Future[T]): Repr[T] = via(new FoldAsync(zero, f))

  /**
   * Similar to `fold` but uses first element as zero element.
   * Applies the given function towards its current and next value,
   * yielding the next current value.
   *
   * If the stream is empty (i.e. completes before signalling any elements),
   * the reduce stage will fail its downstream with a [[NoSuchElementException]],
   * which is semantically in-line with that Scala's standard library collections
   * do in such situations.
   *
   * '''Emits when''' upstream completes
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[FlowOps.fold]]
   */
  def reduce[T >: Out](f: (T, T) ⇒ T): Repr[T] = via(new Reduce[T](f))

  /**
   * Intersperses stream with provided element, similar to how [[scala.collection.immutable.List.mkString]]
   * injects a separator between a List's elements.
   *
   * Additionally can inject start and end marker elements to stream.
   *
   * Examples:
   *
   * {{{
   * val nums = Source(List(1,2,3)).map(_.toString)
   * nums.intersperse(",")            //   1 , 2 , 3
   * nums.intersperse("[", ",", "]")  // [ 1 , 2 , 3 ]
   * }}}
   *
   * In case you want to only prepend or only append an element (yet still use the `intercept` feature
   * to inject a separator between elements, you may want to use the following pattern instead of the 3-argument
   * version of intersperse (See [[Source.concat]] for semantics details):
   *
   * {{{
   * Source.single(">> ") ++ Source(List("1", "2", "3")).intersperse(",")
   * Source(List("1", "2", "3")).intersperse(",") ++ Source.single("END")
   * }}}
   *
   * '''Emits when''' upstream emits (or before with the `start` element if provided)
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def intersperse[T >: Out](start: T, inject: T, end: T): Repr[T] =
    via(Intersperse(Some(start), inject, Some(end)))

  /**
   * Intersperses stream with provided element, similar to how [[scala.collection.immutable.List.mkString]]
   * injects a separator between a List's elements.
   *
   * Additionally can inject start and end marker elements to stream.
   *
   * Examples:
   *
   * {{{
   * val nums = Source(List(1,2,3)).map(_.toString)
   * nums.intersperse(",")            //   1 , 2 , 3
   * nums.intersperse("[", ",", "]")  // [ 1 , 2 , 3 ]
   * }}}
   *
   * '''Emits when''' upstream emits (or before with the `start` element if provided)
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def intersperse[T >: Out](inject: T): Repr[T] =
    via(Intersperse(None, inject, None))

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the given number of elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
   *
   * `n` must be positive, and `d` must be greater than 0 seconds, otherwise
   * IllegalArgumentException is thrown.
   *
   * '''Emits when''' the configured time elapses since the last group has been emitted
   *
   * '''Backpressures when''' the configured time elapses since the last group has been emitted
   *
   * '''Completes when''' upstream completes (emits last group)
   *
   * '''Cancels when''' downstream completes
   */
  def groupedWithin(n: Int, d: FiniteDuration): Repr[immutable.Seq[Out]] =
    via(new GroupedWithin[Out](n, d))

  /**
   * Shifts elements emission in time by a specified amount. It allows to store elements
   * in internal buffer while waiting for next element to be emitted. Depending on the defined
   * [[akka.stream.DelayOverflowStrategy]] it might drop elements or backpressure the upstream if
   * there is no space available in the buffer.
   *
   * Delay precision is 10ms to avoid unnecessary timer scheduling cycles
   *
   * Internal buffer has default capacity 16. You can set buffer size by calling `withAttributes(inputBuffer)`
   *
   * '''Emits when''' there is a pending element in the buffer and configured time for this element elapsed
   *  * EmitEarly - strategy do not wait to emit element if buffer is full
   *
   * '''Backpressures when''' depending on OverflowStrategy
   *  * Backpressure - backpressures when buffer is full
   *  * DropHead, DropTail, DropBuffer - never backpressures
   *  * Fail - fails the stream if buffer gets full
   *
   * '''Completes when''' upstream completes and buffered elements have been drained
   *
   * '''Cancels when''' downstream cancels
   *
   * @param of time to shift all messages
   * @param strategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def delay(of: FiniteDuration, strategy: DelayOverflowStrategy = DelayOverflowStrategy.dropTail): Repr[Out] =
    via(new Delay[Out](of, strategy))

  /**
   * Discard the given number of elements at the beginning of the stream.
   * No elements will be dropped if `n` is zero or negative.
   *
   * '''Emits when''' the specified number of elements has been dropped already
   *
   * '''Backpressures when''' the specified number of elements has been dropped and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def drop(n: Long): Repr[Out] =
    via(Drop[Out](n))

  /**
   * Discard the elements received within the given duration at beginning of the stream.
   *
   * '''Emits when''' the specified time elapsed and a new upstream element arrives
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def dropWithin(d: FiniteDuration): Repr[Out] =
    via(new DropWithin[Out](d))

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * number of elements. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * The stream will be completed without producing any elements if `n` is zero
   * or negative.
   *
   * '''Emits when''' the specified number of elements to take has not yet been reached
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' the defined number of elements has been taken or upstream completes
   *
   * '''Cancels when''' the defined number of elements has been taken or downstream cancels
   *
   * See also [[FlowOps.limit]], [[FlowOps.limitWeighted]]
   */
  def take(n: Long): Repr[Out] =
    via(Take[Out](n))

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * duration. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * Note that this can be combined with [[#take]] to limit the number of elements
   * within the duration.
   *
   * '''Emits when''' an upstream element arrives
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or timer fires
   *
   * '''Cancels when''' downstream cancels or timer fires
   */
  def takeWithin(d: FiniteDuration): Repr[Out] = via(new TakeWithin[Out](d))

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by conflating elements into a summary
   * until the subscriber is ready to accept them. For example a conflate step might average incoming numbers if the
   * upstream publisher is faster.
   *
   * This version of conflate allows to derive a seed from the first element and change the aggregated type to be
   * different than the input type. See [[FlowOps.conflate]] for a simpler version that does not change types.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * '''Emits when''' downstream stops backpressuring and there is a conflated element available
   *
   * '''Backpressures when''' never
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * @param seed Provides the first state for a conflated value using the first unconsumed element as a start
   * @param aggregate Takes the currently aggregated value and the current pending element to produce a new aggregate
   *
   * See also [[FlowOps.conflate]], [[FlowOps.limit]], [[FlowOps.limitWeighted]] [[FlowOps.batch]] [[FlowOps.batchWeighted]]
   */
  def conflateWithSeed[S](seed: Out ⇒ S)(aggregate: (S, Out) ⇒ S): Repr[S] =
    via(Batch(1L, ConstantFun.zeroLong, seed, aggregate).withAttributes(DefaultAttributes.conflate))

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by conflating elements into a summary
   * until the subscriber is ready to accept them. For example a conflate step might average incoming numbers if the
   * upstream publisher is faster.
   *
   * This version of conflate does not change the output type of the stream. See [[FlowOps.conflateWithSeed]] for a
   * more flexible version that can take a seed function and transform elements while rolling up.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * '''Emits when''' downstream stops backpressuring and there is a conflated element available
   *
   * '''Backpressures when''' never
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * @param aggregate Takes the currently aggregated value and the current pending element to produce a new aggregate
   *
   * See also [[FlowOps.conflate]], [[FlowOps.limit]], [[FlowOps.limitWeighted]] [[FlowOps.batch]] [[FlowOps.batchWeighted]]
   */
  def conflate[O2 >: Out](aggregate: (O2, O2) ⇒ O2): Repr[O2] = conflateWithSeed[O2](ConstantFun.scalaIdentityFunction)(aggregate)

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by aggregating elements into batches
   * until the subscriber is ready to accept them. For example a batch step might store received elements in
   * an array up to the allowed max limit if the upstream publisher is faster.
   *
   * This only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * '''Emits when''' downstream stops backpressuring and there is an aggregated element available
   *
   * '''Backpressures when''' there are `max` batched elements and 1 pending element and downstream backpressures
   *
   * '''Completes when''' upstream completes and there is no batched/pending element waiting
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[FlowOps.conflateWithSeed]], [[FlowOps.batchWeighted]]
   *
   * @param max maximum number of elements to batch before backpressuring upstream (must be positive non-zero)
   * @param seed Provides the first state for a batched value using the first unconsumed element as a start
   * @param aggregate Takes the currently batched value and the current pending element to produce a new aggregate
   */
  def batch[S](max: Long, seed: Out ⇒ S)(aggregate: (S, Out) ⇒ S): Repr[S] =
    via(Batch(max, ConstantFun.oneLong, seed, aggregate).withAttributes(DefaultAttributes.batch))

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by aggregating elements into batches
   * until the subscriber is ready to accept them. For example a batch step might concatenate `ByteString`
   * elements up to the allowed max limit if the upstream publisher is faster.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * Batching will apply for all elements, even if a single element cost is greater than the total allowed limit.
   * In this case, previous batched elements will be emitted, then the "heavy" element will be emitted (after
   * being applied with the `seed` function) without batching further elements with it, and then the rest of the
   * incoming elements are batched.
   *
   * '''Emits when''' downstream stops backpressuring and there is a batched element available
   *
   * '''Backpressures when''' there are `max` weighted batched elements + 1 pending element and downstream backpressures
   *
   * '''Completes when''' upstream completes and there is no batched/pending element waiting
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[FlowOps.conflateWithSeed]], [[FlowOps.batch]]
   *
   * @param max maximum weight of elements to batch before backpressuring upstream (must be positive non-zero)
   * @param costFn a function to compute a single element weight
   * @param seed Provides the first state for a batched value using the first unconsumed element as a start
   * @param aggregate Takes the currently batched value and the current pending element to produce a new batch
   */
  def batchWeighted[S](max: Long, costFn: Out ⇒ Long, seed: Out ⇒ S)(aggregate: (S, Out) ⇒ S): Repr[S] =
    via(Batch(max, costFn, seed, aggregate).withAttributes(DefaultAttributes.batchWeighted))

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
   * '''Emits when''' downstream stops backpressuring
   *
   * '''Backpressures when''' downstream backpressures or iterator runs empty
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * @param seed Provides the first state for extrapolation using the first unconsumed element
   * @param extrapolate Takes the current extrapolation state to produce an output element and the next extrapolation
   *                    state.
   */
  def expand[U](extrapolate: Out ⇒ Iterator[U]): Repr[U] = via(new Expand(extrapolate))

  /**
   * Adds a fixed size buffer in the flow that allows to store elements from a faster upstream until it becomes full.
   * Depending on the defined [[akka.stream.OverflowStrategy]] it might drop elements or backpressure the upstream if
   * there is no space available
   *
   * '''Emits when''' downstream stops backpressuring and there is a pending element in the buffer
   *
   * '''Backpressures when''' depending on OverflowStrategy
   *  * Backpressure - backpressures when buffer is full
   *  * DropHead, DropTail, DropBuffer - never backpressures
   *  * Fail - fails the stream if buffer gets full
   *
   * '''Completes when''' upstream completes and buffered elements have been drained
   *
   * '''Cancels when''' downstream cancels
   *
   * @param size The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def buffer(size: Int, overflowStrategy: OverflowStrategy): Repr[Out] = via(fusing.Buffer(size, overflowStrategy))

  /**
   * Takes up to `n` elements from the stream (less than `n` only if the upstream completes before emitting `n` elements)
   * and returns a pair containing a strict sequence of the taken element
   * and a stream representing the remaining elements. If ''n'' is zero or negative, then this will return a pair
   * of an empty collection and a stream containing the whole upstream unchanged.
   *
   * In case of an upstream error, depending on the current state
   *  - the master stream signals the error if less than `n` elements has been seen, and therefore the substream
   *    has not yet been emitted
   *  - the tail substream signals the error after the prefix and tail has been emitted by the main stream
   *    (at that point the main stream has already completed)
   *
   * '''Emits when''' the configured number of prefix elements are available. Emits this prefix, and the rest
   * as a substream
   *
   * '''Backpressures when''' downstream backpressures or substream backpressures
   *
   * '''Completes when''' prefix elements have been consumed and substream has been consumed
   *
   * '''Cancels when''' downstream cancels or substream cancels
   */
  def prefixAndTail[U >: Out](n: Int): Repr[(immutable.Seq[Out], Source[U, NotUsed])] =
    via(new PrefixAndTail[Out](n))

  /**
   * This operation demultiplexes the incoming stream into separate output
   * streams, one for each element key. The key is computed for each element
   * using the given function. When a new key is encountered for the first time
   * a new substream is opened and subsequently fed with all elements belonging to
   * that key.
   *
   * The object returned from this method is not a normal [[Source]] or [[Flow]],
   * it is a [[SubFlow]]. This means that after this combinator all transformations
   * are applied to all encountered substreams in the same fashion. Substream mode
   * is exited either by closing the substream (i.e. connecting it to a [[Sink]])
   * or by merging the substreams back together; see the `to` and `mergeBack` methods
   * on [[SubFlow]] for more information.
   *
   * It is important to note that the substreams also propagate back-pressure as
   * any other stream, which means that blocking one substream will block the `groupBy`
   * operator itself—and thereby all substreams—once all internal or
   * explicit buffers are filled.
   *
   * If the group by function `f` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Stop]] the stream and substreams will be completed
   * with failure.
   *
   * If the group by function `f` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Resume]] or [[akka.stream.Supervision.Restart]]
   * the element is dropped and the stream and substreams continue.
   *
   * Function `f`  MUST NOT return `null`. This will throw exception and trigger supervision decision mechanism.
   *
   * '''Emits when''' an element for which the grouping function returns a group that has not yet been created.
   * Emits the new group
   *
   * '''Backpressures when''' there is an element pending for a group whose substream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels and all substreams cancel
   *
   * @param maxSubstreams configures the maximum number of substreams (keys)
   *        that are supported; if more distinct keys are encountered then the stream fails
   */
  def groupBy[K](maxSubstreams: Int, f: Out ⇒ K): SubFlow[Out, Mat, Repr, Closed] = {
    val merge = new SubFlowImpl.MergeBack[Out, Repr] {
      override def apply[T](flow: Flow[Out, T, NotUsed], breadth: Int): Repr[T] =
        via(new GroupBy(maxSubstreams, f))
          .map(_.via(flow))
          .via(new FlattenMerge(breadth))
    }
    val finish: (Sink[Out, NotUsed]) ⇒ Closed = s ⇒
      via(new GroupBy(maxSubstreams, f))
        .to(Sink.foreach(_.runWith(s)(GraphInterpreter.currentInterpreter.materializer)))
    new SubFlowImpl(Flow[Out], merge, finish)
  }

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
   * In case the *first* element of the stream matches the predicate, the first
   * substream emitted by splitWhen will start from that element. For example:
   *
   * {{{
   * true, false, false // first substream starts from the split-by element
   * true, false        // subsequent substreams operate the same way
   * }}}
   *
   * The object returned from this method is not a normal [[Source]] or [[Flow]],
   * it is a [[SubFlow]]. This means that after this combinator all transformations
   * are applied to all encountered substreams in the same fashion. Substream mode
   * is exited either by closing the substream (i.e. connecting it to a [[Sink]])
   * or by merging the substreams back together; see the `to` and `mergeBack` methods
   * on [[SubFlow]] for more information.
   *
   * It is important to note that the substreams also propagate back-pressure as
   * any other stream, which means that blocking one substream will block the `splitWhen`
   * operator itself—and thereby all substreams—once all internal or
   * explicit buffers are filled.
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Stop]] the stream and substreams will be completed
   * with failure.
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Resume]] or [[akka.stream.Supervision.Restart]]
   * the element is dropped and the stream and substreams continue.
   *
   * '''Emits when''' an element for which the provided predicate is true, opening and emitting
   * a new substream for subsequent element
   *
   * '''Backpressures when''' there is an element pending for the next substream, but the previous
   * is not fully consumed yet, or the substream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels and substreams cancel on `SubstreamCancelStrategy.drain`, downstream
   * cancels or any substream cancels on `SubstreamCancelStrategy.propagate`
   *
   * See also [[FlowOps.splitAfter]].
   */
  def splitWhen(substreamCancelStrategy: SubstreamCancelStrategy)(p: Out ⇒ Boolean): SubFlow[Out, Mat, Repr, Closed] = {
    val merge = new SubFlowImpl.MergeBack[Out, Repr] {
      override def apply[T](flow: Flow[Out, T, NotUsed], breadth: Int): Repr[T] =
        via(Split.when(p, substreamCancelStrategy))
          .map(_.via(flow))
          .via(new FlattenMerge(breadth))
    }

    val finish: (Sink[Out, NotUsed]) ⇒ Closed = s ⇒
      via(Split.when(p, substreamCancelStrategy))
        .to(Sink.foreach(_.runWith(s)(GraphInterpreter.currentInterpreter.materializer)))

    new SubFlowImpl(Flow[Out], merge, finish)
  }

  /**
   * This operation applies the given predicate to all incoming elements and
   * emits them to a stream of output streams, always beginning a new one with
   * the current element if the given predicate returns true for it.
   *
   * @see [[#splitWhen]]
   */
  def splitWhen(p: Out ⇒ Boolean): SubFlow[Out, Mat, Repr, Closed] =
    splitWhen(SubstreamCancelStrategy.drain)(p)

  /**
   * This operation applies the given predicate to all incoming elements and
   * emits them to a stream of output streams. It *ends* the current substream when the
   * predicate is true. This means that for the following series of predicate values,
   * three substreams will be produced with lengths 2, 2, and 3:
   *
   * {{{
   * false, true,        // elements go into first substream
   * false, true,        // elements go into second substream
   * false, false, true  // elements go into third substream
   * }}}
   *
   * The object returned from this method is not a normal [[Source]] or [[Flow]],
   * it is a [[SubFlow]]. This means that after this combinator all transformations
   * are applied to all encountered substreams in the same fashion. Substream mode
   * is exited either by closing the substream (i.e. connecting it to a [[Sink]])
   * or by merging the substreams back together; see the `to` and `mergeBack` methods
   * on [[SubFlow]] for more information.
   *
   * It is important to note that the substreams also propagate back-pressure as
   * any other stream, which means that blocking one substream will block the `splitAfter`
   * operator itself—and thereby all substreams—once all internal or
   * explicit buffers are filled.
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Stop]] the stream and substreams will be completed
   * with failure.
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision.Resume]] or [[akka.stream.Supervision.Restart]]
   * the element is dropped and the stream and substreams continue.
   *
   * '''Emits when''' an element passes through. When the provided predicate is true it emits the element
   * and opens a new substream for subsequent element
   *
   * '''Backpressures when''' there is an element pending for the next substream, but the previous
   * is not fully consumed yet, or the substream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels and substreams cancel on `SubstreamCancelStrategy.drain`, downstream
   * cancels or any substream cancels on `SubstreamCancelStrategy.propagate`
   *
   * See also [[FlowOps.splitWhen]].
   */
  def splitAfter(substreamCancelStrategy: SubstreamCancelStrategy)(p: Out ⇒ Boolean): SubFlow[Out, Mat, Repr, Closed] = {
    val merge = new SubFlowImpl.MergeBack[Out, Repr] {
      override def apply[T](flow: Flow[Out, T, NotUsed], breadth: Int): Repr[T] =
        via(Split.after(p, substreamCancelStrategy))
          .map(_.via(flow))
          .via(new FlattenMerge(breadth))
    }
    val finish: (Sink[Out, NotUsed]) ⇒ Closed = s ⇒
      via(Split.after(p, substreamCancelStrategy))
        .to(Sink.foreach(_.runWith(s)(GraphInterpreter.currentInterpreter.materializer)))
    new SubFlowImpl(Flow[Out], merge, finish)
  }

  /**
   * This operation applies the given predicate to all incoming elements and
   * emits them to a stream of output streams. It *ends* the current substream when the
   * predicate is true.
   *
   * @see [[#splitAfter]]
   */
  def splitAfter(p: Out ⇒ Boolean): SubFlow[Out, Mat, Repr, Closed] =
    splitAfter(SubstreamCancelStrategy.drain)(p)

  /**
   * Transform each input element into a `Source` of output elements that is
   * then flattened into the output stream by concatenation,
   * fully consuming one Source after the other.
   *
   * '''Emits when''' a currently consumed substream has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes and all consumed substreams complete
   *
   * '''Cancels when''' downstream cancels
   */
  def flatMapConcat[T, M](f: Out ⇒ Graph[SourceShape[T], M]): Repr[T] = map(f).via(new FlattenMerge[T, M](1))

  /**
   * Transform each input element into a `Source` of output elements that is
   * then flattened into the output stream by merging, where at most `breadth`
   * substreams are being consumed at any given time.
   *
   * '''Emits when''' a currently consumed substream has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes and all consumed substreams complete
   *
   * '''Cancels when''' downstream cancels
   */
  def flatMapMerge[T, M](breadth: Int, f: Out ⇒ Graph[SourceShape[T], M]): Repr[T] = map(f).via(new FlattenMerge[T, M](breadth))

  /**
   * If the first element has not passed through this stage before the provided timeout, the stream is failed
   * with a [[scala.concurrent.TimeoutException]].
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses before first element arrives
   *
   * '''Cancels when''' downstream cancels
   */
  def initialTimeout(timeout: FiniteDuration): Repr[Out] = via(new Timers.Initial[Out](timeout))

  /**
   * If the completion of the stream does not happen until the provided timeout, the stream is failed
   * with a [[scala.concurrent.TimeoutException]].
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses before upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def completionTimeout(timeout: FiniteDuration): Repr[Out] = via(new Timers.Completion[Out](timeout))

  /**
   * If the time between two processed elements exceeds the provided timeout, the stream is failed
   * with a [[scala.concurrent.TimeoutException]]. The timeout is checked periodically,
   * so the resolution of the check is one period (equals to timeout value).
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses between two emitted elements
   *
   * '''Cancels when''' downstream cancels
   */
  def idleTimeout(timeout: FiniteDuration): Repr[Out] = via(new Timers.Idle[Out](timeout))

  /**
   * If the time between the emission of an element and the following downstream demand exceeds the provided timeout,
   * the stream is failed with a [[scala.concurrent.TimeoutException]]. The timeout is checked periodically,
   * so the resolution of the check is one period (equals to timeout value).
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses between element emission and downstream demand.
   *
   * '''Cancels when''' downstream cancels
   */
  def backpressureTimeout(timeout: FiniteDuration): Repr[Out] = via(new Timers.BackpressureTimeout[Out](timeout))

  /**
   * Injects additional elements if upstream does not emit for a configured amount of time. In other words, this
   * stage attempts to maintains a base rate of emitted elements towards the downstream.
   *
   * If the downstream backpressures then no element is injected until downstream demand arrives. Injected elements
   * do not accumulate during this period.
   *
   * Upstream elements are always preferred over injected elements.
   *
   * '''Emits when''' upstream emits an element or if the upstream was idle for the configured period
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def keepAlive[U >: Out](maxIdle: FiniteDuration, injectedElem: () ⇒ U): Repr[U] =
    via(new Timers.IdleInject[Out, U](maxIdle, injectedElem))

  /**
   * Sends elements downstream with speed limited to `elements/per`. In other words, this stage set the maximum rate
   * for emitting messages. This combinator works for streams where all elements have the same cost or length.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size or maximumBurst).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as number of elements. If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Bucket is full when stream just materialized and started.
   *
   * Parameter `mode` manages behaviour when upstream is faster than throttle rate:
   *  - [[akka.stream.ThrottleMode.Shaping]] makes pauses before emitting messages to meet throttle rate
   *  - [[akka.stream.ThrottleMode.Enforcing]] fails with exception when upstream is faster than throttle rate. Enforcing
   *  cannot emit elements that cost more than the maximumBurst
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def throttle(elements: Int, per: FiniteDuration, maximumBurst: Int, mode: ThrottleMode): Repr[Out] =
    throttle(elements, per, maximumBurst, ConstantFun.oneInt, mode)

  /**
   * Sends elements downstream with speed limited to `cost/per`. Cost is
   * calculating for each element individually by calling `calculateCost` function.
   * This combinator works for streams when elements have different cost(length).
   * Streams of `ByteString` for example.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size or maximumBurst).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as element cost. If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Elements that costs more than the allowed burst will be delayed proportionally
   * to their cost minus available tokens, meeting the target rate.
   *
   * It is recommended to use non-zero burst sizes as they improve both performance and throttling precision by allowing
   * the implementation to avoid using the scheduler when input rates fall below the enforced limit and to reduce
   * most of the inaccuracy caused by the scheduler resolution (which is in the range of milliseconds).
   *
   * Throttler always enforces the rate limit, but in certain cases (mostly due to limited scheduler resolution) it
   * enforces a tighter bound than what was prescribed. This can be also mitigated by increasing the burst size.
   *
   * Parameter `mode` manages behaviour when upstream is faster than throttle rate:
   *  - [[akka.stream.ThrottleMode.Shaping]] makes pauses before emitting messages to meet throttle rate
   *  - [[akka.stream.ThrottleMode.Enforcing]] fails with exception when upstream is faster than throttle rate. Enforcing
   *  cannot emit elements that cost more than the maximumBurst
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def throttle(cost: Int, per: FiniteDuration, maximumBurst: Int,
               costCalculation: (Out) ⇒ Int, mode: ThrottleMode): Repr[Out] =
    via(new Throttle(cost, per, maximumBurst, costCalculation, mode))

  /**
   * Detaches upstream demand from downstream demand without detaching the
   * stream rates; in other words acts like a buffer of size 1.
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def detach: Repr[Out] = via(GraphStages.detacher)

  /**
   * Delays the initial element by the specified duration.
   *
   * '''Emits when''' upstream emits an element if the initial delay is already elapsed
   *
   * '''Backpressures when''' downstream backpressures or initial delay is not yet elapsed
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def initialDelay(delay: FiniteDuration): Repr[Out] = via(new Timers.DelayInitial[Out](delay))

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
   *
   * Uses implicit [[LoggingAdapter]] if available, otherwise uses an internally created one,
   * which uses `akka.stream.Log` as it's source (use this class to configure slf4j loggers).
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def log(name: String, extract: Out ⇒ Any = ConstantFun.scalaIdentityFunction)(implicit log: LoggingAdapter = null): Repr[Out] =
    via(Log(name, extract.asInstanceOf[Any ⇒ Any], Option(log)))

  /**
   * Combine the elements of current flow and the given [[Source]] into a stream of tuples.
   *
   * '''Emits when''' all of the inputs have an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' any upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zip[U](that: Graph[SourceShape[U], _]): Repr[(Out, U)] = via(zipGraph(that))

  protected def zipGraph[U, M](that: Graph[SourceShape[U], M]): Graph[FlowShape[Out @uncheckedVariance, (Out, U)], M] =
    GraphDSL.create(that) { implicit b ⇒ r ⇒
      val zip = b.add(Zip[Out, U]())
      r ~> zip.in1
      FlowShape(zip.in0, zip.out)
    }

  /**
   * Put together the elements of current flow and the given [[Source]]
   * into a stream of combined elements using a combiner function.
   *
   * '''Emits when''' all of the inputs have an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' any upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zipWith[Out2, Out3](that: Graph[SourceShape[Out2], _])(combine: (Out, Out2) ⇒ Out3): Repr[Out3] =
    via(zipWithGraph(that)(combine))

  protected def zipWithGraph[Out2, Out3, M](that: Graph[SourceShape[Out2], M])(combine: (Out, Out2) ⇒ Out3): Graph[FlowShape[Out @uncheckedVariance, Out3], M] =
    GraphDSL.create(that) { implicit b ⇒ r ⇒
      val zip = b.add(ZipWith[Out, Out2, Out3](combine))
      r ~> zip.in1
      FlowShape(zip.in0, zip.out)
    }

  /**
   * Combine the elements of current flow into a stream of tuples consisting
   * of all elements paired with their index. Indices start at 0.
   *
   * '''Emits when''' upstream emits an element and is paired with their index
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zipWithIndex: Repr[(Out, Long)] = {
    statefulMapConcat[(Out, Long)] { () ⇒
      var index: Long = 0L
      elem ⇒ {
        val zipped = (elem, index)
        index += 1
        immutable.Iterable[(Out, Long)](zipped)
      }
    }
  }

  /**
   * Interleave is a deterministic merge of the given [[Source]] with elements of this [[Flow]].
   * It first emits `segmentSize` number of elements from this flow to downstream, then - same amount for `that`
   * source, then repeat process.
   *
   * Example:
   * {{{
   * Source(List(1, 2, 3)).interleave(List(4, 5, 6, 7), 2) // 1, 2, 4, 5, 3, 6, 7
   * }}}
   *
   * After one of upstreams is complete than all the rest elements will be emitted from the second one
   *
   * If it gets error from one of upstreams - stream completes with failure.
   *
   * '''Emits when''' element is available from the currently consumed upstream
   *
   * '''Backpressures when''' downstream backpressures. Signal to current
   * upstream, switch to next upstream when received `segmentSize` elements
   *
   * '''Completes when''' the [[Flow]] and given [[Source]] completes
   *
   * '''Cancels when''' downstream cancels
   */
  def interleave[U >: Out](that: Graph[SourceShape[U], _], segmentSize: Int): Repr[U] =
    via(interleaveGraph(that, segmentSize))

  protected def interleaveGraph[U >: Out, M](
    that:        Graph[SourceShape[U], M],
    segmentSize: Int): Graph[FlowShape[Out @uncheckedVariance, U], M] =
    GraphDSL.create(that) { implicit b ⇒ r ⇒
      val interleave = b.add(Interleave[U](2, segmentSize))
      r ~> interleave.in(1)
      FlowShape(interleave.in(0), interleave.out)
    }

  /**
   * Merge the given [[Source]] to this [[Flow]], taking elements as they arrive from input streams,
   * picking randomly when several elements ready.
   *
   * '''Emits when''' one of the inputs has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' all upstreams complete (eagerComplete=false) or one upstream completes (eagerComplete=true), default value is `false`
   *
   * '''Cancels when''' downstream cancels
   */
  def merge[U >: Out, M](that: Graph[SourceShape[U], M], eagerComplete: Boolean = false): Repr[U] =
    via(mergeGraph(that, eagerComplete))

  protected def mergeGraph[U >: Out, M](that: Graph[SourceShape[U], M], eagerComplete: Boolean): Graph[FlowShape[Out @uncheckedVariance, U], M] =
    GraphDSL.create(that) { implicit b ⇒ r ⇒
      val merge = b.add(Merge[U](2, eagerComplete))
      r ~> merge.in(1)
      FlowShape(merge.in(0), merge.out)
    }

  /**
   * Merge the given [[Source]] to this [[Flow]], taking elements as they arrive from input streams,
   * picking always the smallest of the available elements (waiting for one element from each side
   * to be available). This means that possible contiguity of the input streams is not exploited to avoid
   * waiting for elements, this merge will block when one of the inputs does not have more elements (and
   * does not complete).
   *
   * '''Emits when''' all of the inputs have an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' all upstreams complete
   *
   * '''Cancels when''' downstream cancels
   */
  def mergeSorted[U >: Out, M](that: Graph[SourceShape[U], M])(implicit ord: Ordering[U]): Repr[U] =
    via(mergeSortedGraph(that))

  protected def mergeSortedGraph[U >: Out, M](that: Graph[SourceShape[U], M])(implicit ord: Ordering[U]): Graph[FlowShape[Out @uncheckedVariance, U], M] =
    GraphDSL.create(that) { implicit b ⇒ r ⇒
      val merge = b.add(new MergeSorted[U])
      r ~> merge.in1
      FlowShape(merge.in0, merge.out)
    }

  /**
   * Concatenate the given [[Source]] to this [[Flow]], meaning that once this
   * Flow’s input is exhausted and all result elements have been generated,
   * the Source’s elements will be produced.
   *
   * Note that the [[Source]] is materialized together with this Flow and just kept
   * from producing elements by asserting back-pressure until its time comes.
   *
   * If this [[Flow]] gets upstream error - no elements from the given [[Source]] will be pulled.
   *
   * '''Emits when''' element is available from current stream or from the given [[Source]] when current is completed
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' given [[Source]] completes
   *
   * '''Cancels when''' downstream cancels
   */
  def concat[U >: Out, Mat2](that: Graph[SourceShape[U], Mat2]): Repr[U] =
    via(concatGraph(that))

  protected def concatGraph[U >: Out, Mat2](that: Graph[SourceShape[U], Mat2]): Graph[FlowShape[Out @uncheckedVariance, U], Mat2] =
    GraphDSL.create(that) { implicit b ⇒ r ⇒
      val merge = b.add(Concat[U]())
      r ~> merge.in(1)
      FlowShape(merge.in(0), merge.out)
    }

  /**
   * Prepend the given [[Source]] to this [[Flow]], meaning that before elements
   * are generated from this Flow, the Source's elements will be produced until it
   * is exhausted, at which point Flow elements will start being produced.
   *
   * Note that this Flow will be materialized together with the [[Source]] and just kept
   * from producing elements by asserting back-pressure until its time comes.
   *
   * If the given [[Source]] gets upstream error - no elements from this [[Flow]] will be pulled.
   *
   * '''Emits when''' element is available from the given [[Source]] or from current stream when the [[Source]] is completed
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' this [[Flow]] completes
   *
   * '''Cancels when''' downstream cancels
   */
  def prepend[U >: Out, Mat2](that: Graph[SourceShape[U], Mat2]): Repr[U] =
    via(prependGraph(that))

  protected def prependGraph[U >: Out, Mat2](that: Graph[SourceShape[U], Mat2]): Graph[FlowShape[Out @uncheckedVariance, U], Mat2] =
    GraphDSL.create(that) { implicit b ⇒ r ⇒
      val merge = b.add(Concat[U]())
      r ~> merge.in(0)
      FlowShape(merge.in(1), merge.out)
    }

  /**
   * Provides a secondary source that will be consumed if this stream completes without any
   * elements passing by. As soon as the first element comes through this stream, the alternative
   * will be cancelled.
   *
   * Note that this Flow will be materialized together with the [[Source]] and just kept
   * from producing elements by asserting back-pressure until its time comes or it gets
   * cancelled.
   *
   * On errors the stage is failed regardless of source of the error.
   *
   * '''Emits when''' element is available from first stream or first stream closed without emitting any elements and an element
   *                  is available from the second stream
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' the primary stream completes after emitting at least one element, when the primary stream completes
   *                      without emitting and the secondary stream already has completed or when the secondary stream completes
   *
   * '''Cancels when''' downstream cancels and additionally the alternative is cancelled as soon as an element passes
   *                    by from this stream.
   */
  def orElse[U >: Out, Mat2](secondary: Graph[SourceShape[U], Mat2]): Repr[U] =
    via(orElseGraph(secondary))

  protected def orElseGraph[U >: Out, Mat2](secondary: Graph[SourceShape[U], Mat2]): Graph[FlowShape[Out @uncheckedVariance, U], Mat2] =
    GraphDSL.create(secondary) { implicit b ⇒ secondary ⇒
      val orElse = b.add(OrElse[U]())

      secondary ~> orElse.in(1)

      FlowShape(orElse.in(0), orElse.out)
    }

  /**
   * Concatenates this [[Flow]] with the given [[Source]] so the first element
   * emitted by that source is emitted after the last element of this
   * flow.
   *
   * This is a shorthand for [[concat]]
   */
  def ++[U >: Out, M](that: Graph[SourceShape[U], M]): Repr[U] = concat(that)

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
  def to[Mat2](sink: Graph[SinkShape[Out], Mat2]): Closed

  /**
   * Attaches the given [[Sink]] to this [[Flow]], meaning that elements that passes
   * through will also be sent to the [[Sink]].
   *
   * '''Emits when''' element is available and demand exists both from the Sink and the downstream.
   *
   * '''Backpressures when''' downstream or Sink backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def alsoTo(that: Graph[SinkShape[Out], _]): Repr[Out] = via(alsoToGraph(that))

  protected def alsoToGraph[M](that: Graph[SinkShape[Out], M]): Graph[FlowShape[Out @uncheckedVariance, Out], M] =
    GraphDSL.create(that) { implicit b ⇒ r ⇒
      import GraphDSL.Implicits._
      val bcast = b.add(Broadcast[Out](2))
      bcast.out(1) ~> r
      FlowShape(bcast.in, bcast.out(0))
    }

  def withAttributes(attr: Attributes): Repr[Out]

  def addAttributes(attr: Attributes): Repr[Out]

  def named(name: String): Repr[Out]

  /**
   * Put an asynchronous boundary around this `Flow`.
   *
   * If this is a `SubFlow` (created e.g. by `groupBy`), this creates an
   * asynchronous boundary around each materialized sub-flow, not the
   * super-flow. That way, the super-flow will communicate with sub-flows
   * asynchronously.
   */
  def async: Repr[Out]

}

/**
 * INTERNAL API: this trait will be changed in binary-incompatible ways for classes that are derived from it!
 * Do not implement this interface outside the Akka code base!
 *
 * Binary compatibility is only maintained for callers of this trait’s interface.
 */
trait FlowOpsMat[+Out, +Mat] extends FlowOps[Out, Mat] {

  type Repr[+O] <: ReprMat[O, Mat] {
    type Repr[+OO] = FlowOpsMat.this.Repr[OO]
    type ReprMat[+OO, +MM] = FlowOpsMat.this.ReprMat[OO, MM]
    type Closed = FlowOpsMat.this.Closed
    type ClosedMat[+M] = FlowOpsMat.this.ClosedMat[M]
  }
  type ReprMat[+O, +M] <: FlowOpsMat[O, M] {
    type Repr[+OO] = FlowOpsMat.this.ReprMat[OO, M @uncheckedVariance]
    type ReprMat[+OO, +MM] = FlowOpsMat.this.ReprMat[OO, MM]
    type Closed = FlowOpsMat.this.ClosedMat[M @uncheckedVariance]
    type ClosedMat[+MM] = FlowOpsMat.this.ClosedMat[MM]
  }
  type ClosedMat[+M] <: Graph[_, M]

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
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def viaMat[T, Mat2, Mat3](flow: Graph[FlowShape[Out, T], Mat2])(combine: (Mat, Mat2) ⇒ Mat3): ReprMat[T, Mat3]

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
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def toMat[Mat2, Mat3](sink: Graph[SinkShape[Out], Mat2])(combine: (Mat, Mat2) ⇒ Mat3): ClosedMat[Mat3]

  /**
   * Combine the elements of current flow and the given [[Source]] into a stream of tuples.
   *
   * @see [[#zip]].
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def zipMat[U, Mat2, Mat3](that: Graph[SourceShape[U], Mat2])(matF: (Mat, Mat2) ⇒ Mat3): ReprMat[(Out, U), Mat3] =
    viaMat(zipGraph(that))(matF)

  /**
   * Put together the elements of current flow and the given [[Source]]
   * into a stream of combined elements using a combiner function.
   *
   * @see [[#zipWith]].
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def zipWithMat[Out2, Out3, Mat2, Mat3](that: Graph[SourceShape[Out2], Mat2])(combine: (Out, Out2) ⇒ Out3)(matF: (Mat, Mat2) ⇒ Mat3): ReprMat[Out3, Mat3] =
    viaMat(zipWithGraph(that)(combine))(matF)

  /**
   * Merge the given [[Source]] to this [[Flow]], taking elements as they arrive from input streams,
   * picking randomly when several elements ready.
   *
   * @see [[#merge]].
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def mergeMat[U >: Out, Mat2, Mat3](that: Graph[SourceShape[U], Mat2], eagerComplete: Boolean = false)(matF: (Mat, Mat2) ⇒ Mat3): ReprMat[U, Mat3] =
    viaMat(mergeGraph(that, eagerComplete))(matF)

  /**
   * Interleave is a deterministic merge of the given [[Source]] with elements of this [[Flow]].
   * It first emits `segmentSize` number of elements from this flow to downstream, then - same amount for `that` source,
   * then repeat process.
   *
   * After one of upstreams is complete than all the rest elements will be emitted from the second one
   *
   * If it gets error from one of upstreams - stream completes with failure.
   *
   * @see [[#interleave]].
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def interleaveMat[U >: Out, Mat2, Mat3](that: Graph[SourceShape[U], Mat2], request: Int)(matF: (Mat, Mat2) ⇒ Mat3): ReprMat[U, Mat3] =
    viaMat(interleaveGraph(that, request))(matF)

  /**
   * Merge the given [[Source]] to this [[Flow]], taking elements as they arrive from input streams,
   * picking always the smallest of the available elements (waiting for one element from each side
   * to be available). This means that possible contiguity of the input streams is not exploited to avoid
   * waiting for elements, this merge will block when one of the inputs does not have more elements (and
   * does not complete).
   *
   * @see [[#mergeSorted]].
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def mergeSortedMat[U >: Out, Mat2, Mat3](that: Graph[SourceShape[U], Mat2])(matF: (Mat, Mat2) ⇒ Mat3)(implicit ord: Ordering[U]): ReprMat[U, Mat3] =
    viaMat(mergeSortedGraph(that))(matF)

  /**
   * Concatenate the given [[Source]] to this [[Flow]], meaning that once this
   * Flow’s input is exhausted and all result elements have been generated,
   * the Source’s elements will be produced.
   *
   * Note that the [[Source]] is materialized together with this Flow and just kept
   * from producing elements by asserting back-pressure until its time comes.
   *
   * If this [[Flow]] gets upstream error - no elements from the given [[Source]] will be pulled.
   *
   * @see [[#concat]].
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def concatMat[U >: Out, Mat2, Mat3](that: Graph[SourceShape[U], Mat2])(matF: (Mat, Mat2) ⇒ Mat3): ReprMat[U, Mat3] =
    viaMat(concatGraph(that))(matF)

  /**
   * Prepend the given [[Source]] to this [[Flow]], meaning that before elements
   * are generated from this Flow, the Source's elements will be produced until it
   * is exhausted, at which point Flow elements will start being produced.
   *
   * Note that this Flow will be materialized together with the [[Source]] and just kept
   * from producing elements by asserting back-pressure until its time comes.
   *
   * If the given [[Source]] gets upstream error - no elements from this [[Flow]] will be pulled.
   *
   * @see [[#prepend]].
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def prependMat[U >: Out, Mat2, Mat3](that: Graph[SourceShape[U], Mat2])(matF: (Mat, Mat2) ⇒ Mat3): ReprMat[U, Mat3] =
    viaMat(prependGraph(that))(matF)

  /**
   * Provides a secondary source that will be consumed if this stream completes without any
   * elements passing by. As soon as the first element comes through this stream, the alternative
   * will be cancelled.
   *
   * Note that this Flow will be materialized together with the [[Source]] and just kept
   * from producing elements by asserting back-pressure until its time comes or it gets
   * cancelled.
   *
   * On errors the stage is failed regardless of source of the error.
   *
   * '''Emits when''' element is available from first stream or first stream closed without emitting any elements and an element
   *                  is available from the second stream
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' the primary stream completes after emitting at least one element, when the primary stream completes
   *                      without emitting and the secondary stream already has completed or when the secondary stream completes
   *
   * '''Cancels when''' downstream cancels and additionally the alternative is cancelled as soon as an element passes
   *                    by from this stream.
   */
  def orElseMat[U >: Out, Mat2, Mat3](secondary: Graph[SourceShape[U], Mat2])(matF: (Mat, Mat2) ⇒ Mat3): ReprMat[U, Mat3] =
    viaMat(orElseGraph(secondary))(matF)

  /**
   * Attaches the given [[Sink]] to this [[Flow]], meaning that elements that passes
   * through will also be sent to the [[Sink]].
   *
   * @see [[#alsoTo]]
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def alsoToMat[Mat2, Mat3](that: Graph[SinkShape[Out], Mat2])(matF: (Mat, Mat2) ⇒ Mat3): ReprMat[Out, Mat3] =
    viaMat(alsoToGraph(that))(matF)

  /**
   * Materializes to `Future[Done]` that completes on getting termination message.
   * The Future completes with success when received complete message from upstream or cancel
   * from downstream. It fails with the same error when received error message from
   * downstream.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def watchTermination[Mat2]()(matF: (Mat, Future[Done]) ⇒ Mat2): ReprMat[Out, Mat2] =
    viaMat(GraphStages.terminationWatcher)(matF)

  /**
   * Transform the materialized value of this graph, leaving all other properties as they were.
   */
  def mapMaterializedValue[Mat2](f: Mat ⇒ Mat2): ReprMat[Out, Mat2]

  /**
   * Materializes to `FlowMonitor[Out]` that allows monitoring of the current flow. All events are propagated
   * by the monitor unchanged. Note that the monitor inserts a memory barrier every time it processes an
   * event, and may therefor affect performance.
   * The `combine` function is used to combine the `FlowMonitor` with this flow's materialized value.
   */
  def monitor[Mat2]()(combine: (Mat, FlowMonitor[Out]) ⇒ Mat2): ReprMat[Out, Mat2] =
    viaMat(GraphStages.monitor)(combine)

}
