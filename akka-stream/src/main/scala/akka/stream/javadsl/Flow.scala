/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl

import akka.util.{ ConstantFun, Timeout }
import akka.{ Done, NotUsed }
import akka.event.LoggingAdapter
import akka.japi.{ function, Pair, Util }
import akka.stream._
import org.reactivestreams.Processor

import scala.concurrent.duration.FiniteDuration
import java.util.{ Comparator, Optional }
import java.util.concurrent.CompletionStage
import java.util.function.{ BiFunction, Supplier }

import akka.util.JavaDurationConverters._
import akka.actor.ActorRef
import akka.dispatch.ExecutionContexts
import akka.stream.impl.fusing.LazyFlow
import akka.annotation.ApiMayChange
import akka.util.unused
import com.github.ghik.silencer.silent

import scala.annotation.unchecked.uncheckedVariance
import scala.compat.java8.FutureConverters._
import scala.reflect.ClassTag

object Flow {

  /** Create a `Flow` which can process elements of type `T`. */
  def create[T](): javadsl.Flow[T, T, NotUsed] = fromGraph(scaladsl.Flow[T])

  def fromProcessor[I, O](processorFactory: function.Creator[Processor[I, O]]): javadsl.Flow[I, O, NotUsed] =
    new Flow(scaladsl.Flow.fromProcessor(() => processorFactory.create()))

  def fromProcessorMat[I, O, Mat](
      processorFactory: function.Creator[Pair[Processor[I, O], Mat]]): javadsl.Flow[I, O, Mat] =
    new Flow(scaladsl.Flow.fromProcessorMat { () =>
      val javaPair = processorFactory.create()
      (javaPair.first, javaPair.second)
    })

  /**
   * Creates a [Flow] which will use the given function to transform its inputs to outputs. It is equivalent
   * to `Flow.create[T].map(f)`
   */
  def fromFunction[I, O](f: function.Function[I, O]): javadsl.Flow[I, O, NotUsed] =
    Flow.create[I]().map(f)

  /** Create a `Flow` which can process elements of type `T`. */
  def of[T](@unused clazz: Class[T]): javadsl.Flow[T, T, NotUsed] = create[T]()

  /**
   * A graph with the shape of a flow logically is a flow, this method makes it so also in type.
   */
  def fromGraph[I, O, M](g: Graph[FlowShape[I, O], M]): Flow[I, O, M] =
    g match {
      case f: Flow[I, O, M] => f
      case other            => new Flow(scaladsl.Flow.fromGraph(other))
    }

  /**
   * Defers the creation of a [[Flow]] until materialization. The `factory` function
   * exposes [[ActorMaterializer]] which is going to be used during materialization and
   * [[Attributes]] of the [[Flow]] returned by this method.
   */
  def setup[I, O, M](
      factory: BiFunction[ActorMaterializer, Attributes, Flow[I, O, M]]): Flow[I, O, CompletionStage[M]] =
    scaladsl.Flow.setup((mat, attr) => factory(mat, attr).asScala).mapMaterializedValue(_.toJava).asJava

  /**
   * Creates a `Flow` from a `Sink` and a `Source` where the Flow's input
   * will be sent to the Sink and the Flow's output will come from the Source.
   *
   * The resulting flow can be visualized as:
   * {{{
   *     +----------------------------------------------+
   *     | Resulting Flow[I, O, NotUsed]                |
   *     |                                              |
   *     |  +---------+                  +-----------+  |
   *     |  |         |                  |           |  |
   * I  ~~> | Sink[I] | [no-connection!] | Source[O] | ~~> O
   *     |  |         |                  |           |  |
   *     |  +---------+                  +-----------+  |
   *     +----------------------------------------------+
   * }}}
   *
   * The completion of the Sink and Source sides of a Flow constructed using
   * this method are independent. So if the Sink receives a completion signal,
   * the Source side will remain unaware of that. If you are looking to couple
   * the termination signals of the two sides use `Flow.fromSinkAndSourceCoupled` instead.
   *
   * See also [[fromSinkAndSourceMat]] when access to materialized values of the parameters is needed.
   */
  def fromSinkAndSource[I, O](sink: Graph[SinkShape[I], _], source: Graph[SourceShape[O], _]): Flow[I, O, NotUsed] =
    new Flow(scaladsl.Flow.fromSinkAndSourceMat(sink, source)(scaladsl.Keep.none))

  /**
   * Creates a `Flow` from a `Sink` and a `Source` where the Flow's input
   * will be sent to the Sink and the Flow's output will come from the Source.
   *
   * The resulting flow can be visualized as:
   * {{{
   *     +-------------------------------------------------------+
   *     | Resulting Flow[I, O, M]                              |
   *     |                                                      |
   *     |  +-------------+                  +---------------+  |
   *     |  |             |                  |               |  |
   * I  ~~> | Sink[I, M1] | [no-connection!] | Source[O, M2] | ~~> O
   *     |  |             |                  |               |  |
   *     |  +-------------+                  +---------------+  |
   *     +------------------------------------------------------+
   * }}}
   *
   * The completion of the Sink and Source sides of a Flow constructed using
   * this method are independent. So if the Sink receives a completion signal,
   * the Source side will remain unaware of that. If you are looking to couple
   * the termination signals of the two sides use `Flow.fromSinkAndSourceCoupledMat` instead.
   *
   * The `combine` function is used to compose the materialized values of the `sink` and `source`
   * into the materialized value of the resulting [[Flow]].
   */
  def fromSinkAndSourceMat[I, O, M1, M2, M](
      sink: Graph[SinkShape[I], M1],
      source: Graph[SourceShape[O], M2],
      combine: function.Function2[M1, M2, M]): Flow[I, O, M] =
    new Flow(scaladsl.Flow.fromSinkAndSourceMat(sink, source)(combinerToScala(combine)))

  /**
   * Allows coupling termination (cancellation, completion, erroring) of Sinks and Sources while creating a Flow from them.
   * Similar to [[Flow.fromSinkAndSource]] however couples the termination of these two operators.
   *
   * The resulting flow can be visualized as:
   * {{{
   *     +---------------------------------------------+
   *     | Resulting Flow[I, O, NotUsed]               |
   *     |                                             |
   *     |  +---------+                 +-----------+  |
   *     |  |         |                 |           |  |
   * I  ~~> | Sink[I] | ~~~(coupled)~~~ | Source[O] | ~~> O
   *     |  |         |                 |           |  |
   *     |  +---------+                 +-----------+  |
   *     +---------------------------------------------+
   * }}}
   *
   * E.g. if the emitted [[Flow]] gets a cancellation, the [[Source]] of course is cancelled,
   * however the Sink will also be completed. The table below illustrates the effects in detail:
   *
   * <table>
   *   <tr>
   *     <th>Returned Flow</th>
   *     <th>Sink (<code>in</code>)</th>
   *     <th>Source (<code>out</code>)</th>
   *   </tr>
   *   <tr>
   *     <td><i>cause:</i> upstream (sink-side) receives completion</td>
   *     <td><i>effect:</i> receives completion</td>
   *     <td><i>effect:</i> receives cancel</td>
   *   </tr>
   *   <tr>
   *     <td><i>cause:</i> upstream (sink-side) receives error</td>
   *     <td><i>effect:</i> receives error</td>
   *     <td><i>effect:</i> receives cancel</td>
   *   </tr>
   *   <tr>
   *     <td><i>cause:</i> downstream (source-side) receives cancel</td>
   *     <td><i>effect:</i> completes</td>
   *     <td><i>effect:</i> receives cancel</td>
   *   </tr>
   *   <tr>
   *     <td><i>effect:</i> cancels upstream, completes downstream</td>
   *     <td><i>effect:</i> completes</td>
   *     <td><i>cause:</i> signals complete</td>
   *   </tr>
   *   <tr>
   *     <td><i>effect:</i> cancels upstream, errors downstream</td>
   *     <td><i>effect:</i> receives error</td>
   *     <td><i>cause:</i> signals error or throws</td>
   *   </tr>
   *   <tr>
   *     <td><i>effect:</i> cancels upstream, completes downstream</td>
   *     <td><i>cause:</i> cancels</td>
   *     <td><i>effect:</i> receives cancel</td>
   *   </tr>
   * </table>
   *
   * See also [[fromSinkAndSourceCoupledMat]] when access to materialized values of the parameters is needed.
   */
  def fromSinkAndSourceCoupled[I, O](
      sink: Graph[SinkShape[I], _],
      source: Graph[SourceShape[O], _]): Flow[I, O, NotUsed] =
    new Flow(scaladsl.Flow.fromSinkAndSourceCoupled(sink, source))

  /**
   * Allows coupling termination (cancellation, completion, erroring) of Sinks and Sources while creating a Flow from them.
   * Similar to [[Flow.fromSinkAndSource]] however couples the termination of these two operators.
   *
   * The resulting flow can be visualized as:
   * {{{
   *     +-----------------------------------------------------+
   *     | Resulting Flow[I, O, M]                             |
   *     |                                                     |
   *     |  +-------------+                 +---------------+  |
   *     |  |             |                 |               |  |
   * I  ~~> | Sink[I, M1] | ~~~(coupled)~~~ | Source[O, M2] | ~~> O
   *     |  |             |                 |               |  |
   *     |  +-------------+                 +---------------+  |
   *     +-----------------------------------------------------+
   * }}}
   *
   * E.g. if the emitted [[Flow]] gets a cancellation, the [[Source]] of course is cancelled,
   * however the Sink will also be completed. The table on [[Flow.fromSinkAndSourceCoupled]]
   * illustrates the effects in detail.
   *
   * The `combine` function is used to compose the materialized values of the `sink` and `source`
   * into the materialized value of the resulting [[Flow]].
   */
  def fromSinkAndSourceCoupledMat[I, O, M1, M2, M](
      sink: Graph[SinkShape[I], M1],
      source: Graph[SourceShape[O], M2],
      combine: function.Function2[M1, M2, M]): Flow[I, O, M] =
    new Flow(scaladsl.Flow.fromSinkAndSourceCoupledMat(sink, source)(combinerToScala(combine)))

  /**
   * Creates a real `Flow` upon receiving the first element. Internal `Flow` will not be created
   * if there are no elements, because of completion, cancellation, or error.
   *
   * The materialized value of the `Flow` is the value that is created by the `fallback` function.
   *
   * '''Emits when''' the internal flow is successfully created and it emits
   *
   * '''Backpressures when''' the internal flow is successfully created and it backpressures
   *
   * '''Completes when''' upstream completes and all elements have been emitted from the internal flow
   *
   * '''Cancels when''' downstream cancels
   */
  @Deprecated
  @deprecated(
    "Use lazyInitAsync instead. (lazyInitAsync returns a flow with a more useful materialized value.)",
    "2.5.12")
  def lazyInit[I, O, M](
      flowFactory: function.Function[I, CompletionStage[Flow[I, O, M]]],
      fallback: function.Creator[M]): Flow[I, O, M] = {
    import scala.compat.java8.FutureConverters._
    val sflow = scaladsl.Flow
      .fromGraph(new LazyFlow[I, O, M](t =>
        flowFactory.apply(t).toScala.map(_.asScala)(ExecutionContexts.sameThreadExecutionContext)))
      .mapMaterializedValue(_ => fallback.create())
    new Flow(sflow)
  }

  /**
   * Creates a real `Flow` upon receiving the first element. Internal `Flow` will not be created
   * if there are no elements, because of completion, cancellation, or error.
   *
   * The materialized value of the `Flow` is a `Future[Option[M]]` that is completed with `Some(mat)` when the internal
   * flow gets materialized or with `None` when there where no elements. If the flow materialization (including
   * the call of the `flowFactory`) fails then the future is completed with a failure.
   *
   * '''Emits when''' the internal flow is successfully created and it emits
   *
   * '''Backpressures when''' the internal flow is successfully created and it backpressures
   *
   * '''Completes when''' upstream completes and all elements have been emitted from the internal flow
   *
   * '''Cancels when''' downstream cancels
   */
  def lazyInitAsync[I, O, M](
      flowFactory: function.Creator[CompletionStage[Flow[I, O, M]]]): Flow[I, O, CompletionStage[Optional[M]]] = {
    import scala.compat.java8.FutureConverters._

    val sflow = scaladsl.Flow
      .lazyInitAsync(() => flowFactory.create().toScala.map(_.asScala)(ExecutionContexts.sameThreadExecutionContext))
      .mapMaterializedValue(
        fut =>
          fut
            .map(_.fold[Optional[M]](Optional.empty())(m => Optional.ofNullable(m)))(
              ExecutionContexts.sameThreadExecutionContext)
            .toJava)
    new Flow(sflow)
  }

  /**
   * Upcast a stream of elements to a stream of supertypes of that element. Useful in combination with
   * fan-in operators where you do not want to pay the cost of casting each element in a `map`.
   *
   * @tparam SuperOut a supertype to the type of element flowing out of the flow
   * @return A flow that accepts `In` and outputs elements of the super type
   */
  def upcast[In, SuperOut, Out <: SuperOut, M](flow: Flow[In, Out, M]): Flow[In, SuperOut, M] =
    flow.asInstanceOf[Flow[In, SuperOut, M]]

}

/** Create a `Flow` which can process elements of type `T`. */
final class Flow[In, Out, Mat](delegate: scaladsl.Flow[In, Out, Mat]) extends Graph[FlowShape[In, Out], Mat] {
  import akka.util.ccompat.JavaConverters._

  override def shape: FlowShape[In, Out] = delegate.shape
  override def traversalBuilder = delegate.traversalBuilder

  override def toString: String = delegate.toString

  /** Converts this Flow to its Scala DSL counterpart */
  def asScala: scaladsl.Flow[In, Out, Mat] = delegate

  /**
   * Transform only the materialized value of this Flow, leaving all other properties as they were.
   */
  def mapMaterializedValue[Mat2](f: function.Function[Mat, Mat2]): Flow[In, Out, Mat2] =
    new Flow(delegate.mapMaterializedValue(f.apply _))

  /**
   * Transform this [[Flow]] by appending the given processing steps.
   * {{{
   *     +---------------------------------+
   *     | Resulting Flow[In, T, Mat]  |
   *     |                                 |
   *     |  +------+             +------+  |
   *     |  |      |             |      |  |
   * In ~~> | this |  ~~Out~~>   | flow | ~~> T
   *     |  |   Mat|             |     M|  |
   *     |  +------+             +------+  |
   *     +---------------------------------+
   * }}}
   * The materialized value of the combined [[Flow]] will be the materialized
   * value of the current flow (ignoring the other Flow’s value), use
   * `viaMat` if a different strategy is needed.
   *
   * See also [[viaMat]] when access to materialized values of the parameter is needed.
   */
  def via[T, M](flow: Graph[FlowShape[Out, T], M]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.via(flow))

  /**
   * Transform this [[Flow]] by appending the given processing steps.
   * {{{
   *     +---------------------------------+
   *     | Resulting Flow[In, T, M2]       |
   *     |                                 |
   *     |  +------+            +------+   |
   *     |  |      |            |      |   |
   * In ~~> | this |  ~~Out~~>  | flow |  ~~> T
   *     |  |   Mat|            |     M|   |
   *     |  +------+            +------+   |
   *     +---------------------------------+
   * }}}
   * The `combine` function is used to compose the materialized values of this flow and that
   * flow into the materialized value of the resulting Flow.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def viaMat[T, M, M2](
      flow: Graph[FlowShape[Out, T], M],
      combine: function.Function2[Mat, M, M2]): javadsl.Flow[In, T, M2] =
    new Flow(delegate.viaMat(flow)(combinerToScala(combine)))

  /**
   * Connect this [[Flow]] to a [[Sink]], concatenating the processing steps of both.
   * {{{
   *     +------------------------------+
   *     | Resulting Sink[In, Mat]      |
   *     |                              |
   *     |  +------+          +------+  |
   *     |  |      |          |      |  |
   * In ~~> | flow | ~~Out~~> | sink |  |
   *     |  |   Mat|          |     M|  |
   *     |  +------+          +------+  |
   *     +------------------------------+
   * }}}
   * The materialized value of the combined [[Sink]] will be the materialized
   * value of the current flow (ignoring the given Sink’s value), use
   * `toMat` if a different strategy is needed.
   *
   * See also [[toMat]] when access to materialized values of the parameter is needed.
   */
  def to(sink: Graph[SinkShape[Out], _]): javadsl.Sink[In, Mat] =
    new Sink(delegate.to(sink))

  /**
   * Connect this [[Flow]] to a [[Sink]], concatenating the processing steps of both.
   * {{{
   *     +----------------------------+
   *     | Resulting Sink[In, M2]     |
   *     |                            |
   *     |  +------+        +------+  |
   *     |  |      |        |      |  |
   * In ~~> | flow | ~Out~> | sink |  |
   *     |  |   Mat|        |     M|  |
   *     |  +------+        +------+  |
   *     +----------------------------+
   * }}}
   * The `combine` function is used to compose the materialized values of this flow and that
   * Sink into the materialized value of the resulting Sink.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def toMat[M, M2](sink: Graph[SinkShape[Out], M], combine: function.Function2[Mat, M, M2]): javadsl.Sink[In, M2] =
    new Sink(delegate.toMat(sink)(combinerToScala(combine)))

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
   * `joinMat` if a different strategy is needed.
   *
   * See also [[joinMat]] when access to materialized values of the parameter is needed.
   */
  def join[M](flow: Graph[FlowShape[Out, In], M]): javadsl.RunnableGraph[Mat] =
    RunnableGraph.fromGraph(delegate.join(flow))

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
  def joinMat[M, M2](
      flow: Graph[FlowShape[Out, In], M],
      combine: function.Function2[Mat, M, M2]): javadsl.RunnableGraph[M2] =
    RunnableGraph.fromGraph(delegate.joinMat(flow)(combinerToScala(combine)))

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
  def join[I2, O2, Mat2](bidi: Graph[BidiShape[Out, O2, I2, In], Mat2]): Flow[I2, O2, Mat] =
    new Flow(delegate.join(bidi))

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
   *
   * See also [[viaMat]] when access to materialized values of the parameter is needed.
   */
  def joinMat[I2, O2, Mat2, M](
      bidi: Graph[BidiShape[Out, O2, I2, In], Mat2],
      combine: function.Function2[Mat, Mat2, M]): Flow[I2, O2, M] =
    new Flow(delegate.joinMat(bidi)(combinerToScala(combine)))

  /**
   * Connect the `Source` to this `Flow` and then connect it to the `Sink` and run it.
   *
   * The returned tuple contains the materialized values of the `Source` and `Sink`,
   * e.g. the `Subscriber` of a `Source.asSubscriber` and `Publisher` of a `Sink.asPublisher`.
   *
   * @tparam T materialized type of given Source
   * @tparam U materialized type of given Sink
   */
  def runWith[T, U](
      source: Graph[SourceShape[In], T],
      sink: Graph[SinkShape[Out], U],
      materializer: Materializer): akka.japi.Pair[T, U] = {
    val (som, sim) = delegate.runWith(source, sink)(materializer)
    akka.japi.Pair(som, sim)
  }

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def map[T](f: function.Function[Out, T]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.map(f.apply))

  /**
   * This is a simplified version of `wireTap(Sink)` that takes only a simple procedure.
   * Elements will be passed into this "side channel" function, and any of its results will be ignored.
   *
   * If the wire-tap operation is slow (it backpressures), elements that would've been sent to it will be dropped instead.
   * It is similar to [[#alsoTo]] but will not affect (i.e. backpressure) the flow tapped into.
   *
   * This operation is useful for inspecting the passed through element, usually by means of side-effecting
   * operations (such as `println`, or emitting metrics), for each element without having to modify it.
   *
   * For logging signals (elements, completion, error) consider using the [[log]] operator instead,
   * along with appropriate `ActorAttributes.logLevels`.
   *
   * '''Emits when''' upstream emits an element; the same element will be passed to the attached function,
   *                  as well as to the downstream operator
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def wireTap(f: function.Procedure[Out]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.wireTap(f(_)))

  /**
   * Transform each input element into an `Iterable` of output elements that is
   * then flattened into the output stream.
   *
   * Make sure that the `Iterable` is immutable or at least not modified after
   * being used as an output sequence. Otherwise the stream may fail with
   * `ConcurrentModificationException` or other more subtle errors may occur.
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
   */
  def mapConcat[T](f: function.Function[Out, java.lang.Iterable[T]]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.mapConcat { elem =>
      Util.immutableSeq(f(elem))
    })

  /**
   * Transform each input element into an `Iterable` of output elements that is
   * then flattened into the output stream. The transformation is meant to be stateful,
   * which is enabled by creating the transformation function anew for every materialization —
   * the returned function will typically close over mutable objects to store state between
   * invocations. For the stateless variant see [[#mapConcat]].
   *
   * Make sure that the `Iterable` is immutable or at least not modified after
   * being used as an output sequence. Otherwise the stream may fail with
   * `ConcurrentModificationException` or other more subtle errors may occur.
   *
   * The returned `Iterable` MUST NOT contain `null` values,
   * as they are illegal as stream elements - according to the Reactive Streams specification.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
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
   */
  def statefulMapConcat[T](
      f: function.Creator[function.Function[Out, java.lang.Iterable[T]]]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.statefulMapConcat { () =>
      val fun = f.create()
      elem => Util.immutableSeq(fun(elem))
    })

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `CompletionStage` and the
   * value of that future will be emitted downstream. The number of CompletionStages
   * that shall run in parallel is given as the first argument to ``mapAsync``.
   * These CompletionStages may complete in any order, but the elements that
   * are emitted downstream are in the same order as received from upstream.
   *
   * If the function `f` throws an exception or if the `CompletionStage` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision#stop]]
   * the stream will be completed with failure.
   *
   * If the function `f` throws an exception or if the `CompletionStage` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision#resume]] or
   * [[akka.stream.Supervision#restart]] the element is dropped and the stream continues.
   *
   * The function `f` is always invoked on the elements in the order they arrive.
   *
   * '''Emits when''' the CompletionStage returned by the provided function finishes for the next element in sequence
   *
   * '''Backpressures when''' the number of CompletionStages reaches the configured parallelism and the downstream
   * backpressures or the first future is not completed
   *
   * '''Completes when''' upstream completes and all CompletionStages have been completed and all elements have been emitted
   *
   * '''Cancels when''' downstream cancels
   *
   * @see [[#mapAsyncUnordered]]
   */
  def mapAsync[T](parallelism: Int, f: function.Function[Out, CompletionStage[T]]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.mapAsync(parallelism)(x => f(x).toScala))

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `CompletionStage` and the
   * value of that future will be emitted downstream. The number of CompletionStages
   * that shall run in parallel is given as the first argument to ``mapAsyncUnordered``.
   * Each processed element will be emitted downstream as soon as it is ready, i.e. it is possible
   * that the elements are not emitted downstream in the same order as received from upstream.
   *
   * If the function `f` throws an exception or if the `CompletionStage` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision#stop]]
   * the stream will be completed with failure.
   *
   * If the function `f` throws an exception or if the `CompletionStage` is completed
   * with failure and the supervision decision is [[akka.stream.Supervision#resume]] or
   * [[akka.stream.Supervision#restart]] the element is dropped and the stream continues.
   *
   * The function `f` is always invoked on the elements in the order they arrive (even though the result of the futures
   * returned by `f` might be emitted in a different order).
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' any of the CompletionStages returned by the provided function complete
   *
   * '''Backpressures when''' the number of CompletionStages reaches the configured parallelism and the downstream backpressures
   *
   * '''Completes when''' upstream completes and all CompletionStages have been completed and all elements have been emitted
   *
   * '''Cancels when''' downstream cancels
   *
   * @see [[#mapAsync]]
   */
  def mapAsyncUnordered[T](parallelism: Int, f: function.Function[Out, CompletionStage[T]]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.mapAsyncUnordered(parallelism)(x => f(x).toScala))

  /**
   * Use the `ask` pattern to send a request-reply message to the target `ref` actor.
   * If any of the asks times out it will fail the stream with a [[akka.pattern.AskTimeoutException]].
   *
   * The `mapTo` class parameter is used to cast the incoming responses to the expected response type.
   *
   * Similar to the plain ask pattern, the target actor is allowed to reply with `akka.util.Status`.
   * An `akka.util.Status#Failure` will cause the operator to fail with the cause carried in the `Failure` message.
   *
   * Defaults to parallelism of 2 messages in flight, since while one ask message may be being worked on, the second one
   * still be in the mailbox, so defaulting to sending the second one a bit earlier than when first ask has replied maintains
   * a slightly healthier throughput.
   *
   * The operator fails with an [[akka.stream.WatchedActorTerminatedException]] if the target actor is terminated.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' any of the CompletionStages returned by the provided function complete
   *
   * '''Backpressures when''' the number of futures reaches the configured parallelism and the downstream backpressures
   *
   * '''Completes when''' upstream completes and all futures have been completed and all elements have been emitted
   *
   * '''Fails when''' the passed in actor terminates, or a timeout is exceeded in any of the asks performed
   *
   * '''Cancels when''' downstream cancels
   */
  def ask[S](ref: ActorRef, mapTo: Class[S], timeout: Timeout): javadsl.Flow[In, S, Mat] =
    ask(2, ref, mapTo, timeout)

  /**
   * Use the `ask` pattern to send a request-reply message to the target `ref` actor.
   * If any of the asks times out it will fail the stream with a [[akka.pattern.AskTimeoutException]].
   *
   * The `mapTo` class parameter is used to cast the incoming responses to the expected response type.
   *
   * Similar to the plain ask pattern, the target actor is allowed to reply with `akka.util.Status`.
   * An `akka.util.Status#Failure` will cause the operator to fail with the cause carried in the `Failure` message.
   *
   * Parallelism limits the number of how many asks can be "in flight" at the same time.
   * Please note that the elements emitted by this operator are in-order with regards to the asks being issued
   * (i.e. same behaviour as mapAsync).
   *
   * The operator fails with an [[akka.stream.WatchedActorTerminatedException]] if the target actor is terminated.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' any of the CompletionStages returned by the provided function complete
   *
   * '''Backpressures when''' the number of futures reaches the configured parallelism and the downstream backpressures
   *
   * '''Completes when''' upstream completes and all futures have been completed and all elements have been emitted
   *
   * '''Fails when''' the passed in actor terminates, or a timeout is exceeded in any of the asks performed
   *
   * '''Cancels when''' downstream cancels
   */
  def ask[S](parallelism: Int, ref: ActorRef, mapTo: Class[S], timeout: Timeout): javadsl.Flow[In, S, Mat] =
    new Flow(delegate.ask[S](parallelism)(ref)(timeout, ClassTag(mapTo)))

  /**
   * The operator fails with an [[akka.stream.WatchedActorTerminatedException]] if the target actor is terminated.
   *
   * '''Emits when''' upstream emits
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Fails when''' the watched actor terminates
   *
   * '''Cancels when''' downstream cancels
   */
  def watch(ref: ActorRef): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.watch(ref))

  /**
   * Only pass on those elements that satisfy the given predicate.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the given predicate returns true for the element
   *
   * '''Backpressures when''' the given predicate returns true for the element and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def filter(p: function.Predicate[Out]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.filter(p.test))

  /**
   * Only pass on those elements that NOT satisfy the given predicate.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the given predicate returns false for the element
   *
   * '''Backpressures when''' the given predicate returns false for the element and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def filterNot(p: function.Predicate[Out]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.filterNot(p.test))

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step.
   * Non-matching elements are filtered out.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the provided partial function is defined for the element
   *
   * '''Backpressures when''' the partial function is defined for the element and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def collect[T](pf: PartialFunction[Out, T]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.collect(pf))

  /**
   * Transform this stream by testing the type of each of the elements
   * on which the element is an instance of the provided type as they pass through this processing step.
   * Non-matching elements are filtered out.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the element is an instance of the provided type
   *
   * '''Backpressures when''' the element is an instance of the provided type and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def collectType[T](clazz: Class[T]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.collectType[T](ClassTag[T](clazz)))

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * `n` must be positive, otherwise IllegalArgumentException is thrown.
   *
   * '''Emits when''' the specified number of elements has been accumulated or upstream completed
   *
   * '''Backpressures when''' a group has been assembled and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def grouped(n: Int): javadsl.Flow[In, java.util.List[Out], Mat] =
    new Flow(delegate.grouped(n).map(_.asJava)) // TODO optimize to one step

  /**
   * Ensure stream boundedness by limiting the number of elements from upstream.
   * If the number of incoming elements exceeds max, it will signal
   * upstream failure `StreamLimitException` downstream.
   *
   * Due to input buffering some elements may have been
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
   * '''Errors when''' the total number of incoming element exceeds max
   *
   * '''Cancels when''' the defined number of elements has been taken or downstream cancels
   *
   * See also [[Flow.take]], [[Flow.takeWithin]], [[Flow.takeWhile]]
   */
  def limit(n: Long): javadsl.Flow[In, Out, Mat] = new Flow(delegate.limit(n))

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
   * The stream will be completed without producing any elements if `n` is zero
   * or negative.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the specified number of elements to take has not yet been reached
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' the defined number of elements has been taken or upstream completes
   *
   * '''Errors when''' when the accumulated cost exceeds max
   *
   * '''Cancels when''' the defined number of elements has been taken or downstream cancels
   *
   * See also [[Flow.take]], [[Flow.takeWithin]], [[Flow.takeWhile]]
   */
  def limitWeighted(n: Long)(costFn: function.Function[Out, java.lang.Long]): javadsl.Flow[In, Out, Mat] = {
    new Flow(delegate.limitWeighted(n)(costFn.apply))
  }

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
  def sliding(n: Int, step: Int = 1): javadsl.Flow[In, java.util.List[Out], Mat] =
    new Flow(delegate.sliding(n, step).map(_.asJava)) // TODO optimize to one step

  /**
   * Similar to `fold` but is not a terminal operation,
   * emits its current value which starts at `zero` and then
   * applies the current and next value to the given function `f`,
   * emitting the next current value.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision#restart]] current value starts at `zero` again
   * the stream will continue.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * Note that the `zero` value must be immutable.
   *
   * '''Emits when''' the function scanning the element returns a new element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def scan[T](zero: T)(f: function.Function2[T, Out, T]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.scan(zero)(f.apply))

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
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * Note that the `zero` value must be immutable.
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
  def scanAsync[T](zero: T)(f: function.Function2[T, Out, CompletionStage[T]]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.scanAsync(zero) { (out, in) =>
      f(out, in).toScala
    })

  /**
   * Similar to `scan` but only emits its result when the upstream completes,
   * after which it also completes. Applies the given function `f` towards its current and next value,
   * yielding the next current value.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision#restart]] current value starts at `zero` again
   * the stream will continue.
   *
   * Note that the `zero` value must be immutable.
   *
   * '''Emits when''' upstream completes
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def fold[T](zero: T)(f: function.Function2[T, Out, T]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.fold(zero)(f.apply))

  /**
   * Similar to `fold` but with an asynchronous function.
   * Applies the given function towards its current and next value,
   * yielding the next current value.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * If the function `f` returns a failure and the supervision decision is
   * [[akka.stream.Supervision.Restart]] current value starts at `zero` again
   * the stream will continue.
   *
   * Note that the `zero` value must be immutable.
   *
   * '''Emits when''' upstream completes
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def foldAsync[T](zero: T)(f: function.Function2[T, Out, CompletionStage[T]]): javadsl.Flow[In, T, Mat] =
    new Flow(delegate.foldAsync(zero) { (out, in) =>
      f(out, in).toScala
    })

  /**
   * Similar to `fold` but uses first element as zero element.
   * Applies the given function towards its current and next value,
   * yielding the next current value.
   *
   * If the stream is empty (i.e. completes before signalling any elements),
   * the reduce operator will fail its downstream with a [[NoSuchElementException]],
   * which is semantically in-line with that Scala's standard library collections
   * do in such situations.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' upstream completes
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def reduce(f: function.Function2[Out, Out, Out]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.reduce(f.apply))

  /**
   * Intersperses stream with provided element, similar to how [[scala.collection.immutable.List.mkString]]
   * injects a separator between a List's elements.
   *
   * Additionally can inject start and end marker elements to stream.
   *
   * Examples:
   *
   * {{{
   * Source<Integer, ?> nums = Source.from(Arrays.asList(0, 1, 2, 3));
   * nums.intersperse(",");            //   1 , 2 , 3
   * nums.intersperse("[", ",", "]");  // [ 1 , 2 , 3 ]
   * }}}
   *
   * In case you want to only prepend or only append an element (yet still use the `intercept` feature
   * to inject a separator between elements, you may want to use the following pattern instead of the 3-argument
   * version of intersperse (See [[Source.concat]] for semantics details):
   *
   * {{{
   * Source.single(">> ").concat(flow.intersperse(","))
   * flow.intersperse(",").concat(Source.single("END"))
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
  def intersperse(start: Out, inject: Out, end: Out): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.intersperse(start, inject, end))

  /**
   * Intersperses stream with provided element, similar to how [[scala.collection.immutable.List.mkString]]
   * injects a separator between a List's elements.
   *
   * Additionally can inject start and end marker elements to stream.
   *
   * Examples:
   *
   * {{{
   * Source<Integer, ?> nums = Source.from(Arrays.asList(0, 1, 2, 3));
   * nums.intersperse(",");            //   1 , 2 , 3
   * nums.intersperse("[", ",", "]");  // [ 1 , 2 , 3 ]
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
  def intersperse(inject: Out): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.intersperse(inject))

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the given number of elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
   *
   * '''Emits when''' the configured time elapses since the last group has been emitted or `n` elements is buffered
   *
   * '''Backpressures when''' downstream backpressures, and there are `n+1` buffered elements
   *
   * '''Completes when''' upstream completes (emits last group)
   *
   * '''Cancels when''' downstream completes
   *
   * `n` must be positive, and `d` must be greater than 0 seconds, otherwise
   * IllegalArgumentException is thrown.
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def groupedWithin(n: Int, d: FiniteDuration): javadsl.Flow[In, java.util.List[Out], Mat] =
    new Flow(delegate.groupedWithin(n, d).map(_.asJava)) // TODO optimize to one step

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the given number of elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
   *
   * '''Emits when''' the configured time elapses since the last group has been emitted or `n` elements is buffered
   *
   * '''Backpressures when''' downstream backpressures, and there are `n+1` buffered elements
   *
   * '''Completes when''' upstream completes (emits last group)
   *
   * '''Cancels when''' downstream completes
   *
   * `n` must be positive, and `d` must be greater than 0 seconds, otherwise
   * IllegalArgumentException is thrown.
   */
  @silent
  def groupedWithin(n: Int, d: java.time.Duration): javadsl.Flow[In, java.util.List[Out], Mat] =
    groupedWithin(n, d.asScala)

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the weight of the elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
   *
   * '''Emits when''' the configured time elapses since the last group has been emitted or weight limit reached
   *
   * '''Backpressures when''' downstream backpressures, and buffered group (+ pending element) weighs more than `maxWeight`
   *
   * '''Completes when''' upstream completes (emits last group)
   *
   * '''Cancels when''' downstream completes
   *
   * `maxWeight` must be positive, and `d` must be greater than 0 seconds, otherwise
   * IllegalArgumentException is thrown.
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def groupedWeightedWithin(
      maxWeight: Long,
      costFn: function.Function[Out, java.lang.Long],
      d: FiniteDuration): javadsl.Flow[In, java.util.List[Out], Mat] =
    new Flow(delegate.groupedWeightedWithin(maxWeight, d)(costFn.apply).map(_.asJava))

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the weight of the elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
   *
   * '''Emits when''' the configured time elapses since the last group has been emitted or weight limit reached
   *
   * '''Backpressures when''' downstream backpressures, and buffered group (+ pending element) weighs more than `maxWeight`
   *
   * '''Completes when''' upstream completes (emits last group)
   *
   * '''Cancels when''' downstream completes
   *
   * `maxWeight` must be positive, and `d` must be greater than 0 seconds, otherwise
   * IllegalArgumentException is thrown.
   */
  @silent
  def groupedWeightedWithin(
      maxWeight: Long,
      costFn: function.Function[Out, java.lang.Long],
      d: java.time.Duration): javadsl.Flow[In, java.util.List[Out], Mat] =
    groupedWeightedWithin(maxWeight, costFn, d.asScala)

  /**
   * Shifts elements emission in time by a specified amount. It allows to store elements
   * in internal buffer while waiting for next element to be emitted. Depending on the defined
   * [[akka.stream.DelayOverflowStrategy]] it might drop elements or backpressure the upstream if
   * there is no space available in the buffer.
   *
   * Delay precision is 10ms to avoid unnecessary timer scheduling cycles
   *
   * Internal buffer has default capacity 16. You can set buffer size by calling `addAttributes(inputBuffer)`
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
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def delay(of: FiniteDuration, strategy: DelayOverflowStrategy): Flow[In, Out, Mat] =
    new Flow(delegate.delay(of, strategy))

  /**
   * Shifts elements emission in time by a specified amount. It allows to store elements
   * in internal buffer while waiting for next element to be emitted. Depending on the defined
   * [[akka.stream.DelayOverflowStrategy]] it might drop elements or backpressure the upstream if
   * there is no space available in the buffer.
   *
   * Delay precision is 10ms to avoid unnecessary timer scheduling cycles
   *
   * Internal buffer has default capacity 16. You can set buffer size by calling `addAttributes(inputBuffer)`
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
  @silent
  def delay(of: java.time.Duration, strategy: DelayOverflowStrategy): Flow[In, Out, Mat] =
    delay(of.asScala, strategy)

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
  def drop(n: Long): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.drop(n))

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
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def dropWithin(d: FiniteDuration): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.dropWithin(d))

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
  @silent
  def dropWithin(d: java.time.Duration): javadsl.Flow[In, Out, Mat] =
    dropWithin(d.asScala)

  /**
   * Terminate processing (and cancel the upstream publisher) after predicate
   * returns false for the first time. When inclusive is `true`, include the element
   * for which the predicate returned `false`.
   * Due to input buffering some elements may have been requested from upstream publishers
   * that will then not be processed downstream of this step.
   *
   * The stream will be completed without producing any elements if predicate is false for
   * the first stream element.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the predicate is true
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' predicate returned false (or 1 after predicate returns false if `inclusive` or upstream completes
   *
   * '''Cancels when''' predicate returned false or downstream cancels
   *
   * See also [[Flow.limit]], [[Flow.limitWeighted]]
   */
  def takeWhile(p: function.Predicate[Out], inclusive: Boolean): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.takeWhile(p.test, inclusive))

  /**
   * Terminate processing (and cancel the upstream publisher) after predicate
   * returns false for the first time. When inclusive is `true`, include the element
   * for which the predicate returned `false`.
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
   * See also [[Flow.limit]], [[Flow.limitWeighted]]
   */
  def takeWhile(p: function.Predicate[Out]): javadsl.Flow[In, Out, Mat] = takeWhile(p, false)

  /**
   * Discard elements at the beginning of the stream while predicate is true.
   * All elements will be taken after predicate returns false first time.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' predicate returned false and for all following stream elements
   *
   * '''Backpressures when''' predicate returned false and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def dropWhile(p: function.Predicate[Out]): javadsl.Flow[In, Out, Mat] = new Flow(delegate.dropWhile(p.test))

  /**
   * Recover allows to send last element on failure and gracefully complete the stream
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
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
   */
  @deprecated("Use recoverWithRetries instead.", "2.4.4")
  def recover(pf: PartialFunction[Throwable, Out]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.recover(pf))

  /**
   * Recover allows to send last element on failure and gracefully complete the stream
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
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
   */
  @deprecated("Use recoverWithRetries instead.", "2.4.4")
  def recover(clazz: Class[_ <: Throwable], supplier: Supplier[Out]): javadsl.Flow[In, Out, Mat] =
    recover {
      case elem if clazz.isInstance(elem) => supplier.get()
    }

  /**
   * While similar to [[recover]] this operator can be used to transform an error signal to a different one *without* logging
   * it as an error in the process. So in that sense it is NOT exactly equivalent to `recover(t => throw t2)` since recover
   * would log the `t2` error.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * Similarly to [[recover]] throwing an exception inside `mapError` _will_ be logged.
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
  def mapError(pf: PartialFunction[Throwable, Throwable]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.mapError(pf))

  /**
   * RecoverWith allows to switch to alternative Source on flow failure. It will stay in effect after
   * a failure has been recovered so that each time there is a failure it is fed into the `pf` and a new
   * Source may be materialized.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
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
  @silent
  def recoverWith(pf: PartialFunction[Throwable, _ <: Graph[SourceShape[Out], NotUsed]]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.recoverWith(pf))

  /**
   * RecoverWith allows to switch to alternative Source on flow failure. It will stay in effect after
   * a failure has been recovered so that each time there is a failure it is fed into the `pf` and a new
   * Source may be materialized.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
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
  def recoverWith(
      clazz: Class[_ <: Throwable],
      supplier: Supplier[Graph[SourceShape[Out], NotUsed]]): javadsl.Flow[In, Out, Mat] =
    recoverWith {
      case elem if clazz.isInstance(elem) => supplier.get()
    }

  /**
   * RecoverWithRetries allows to switch to alternative Source on flow failure. It will stay in effect after
   * a failure has been recovered up to `attempts` number of times so that each time there is a failure
   * it is fed into the `pf` and a new Source may be materialized. Note that if you pass in 0, this won't
   * attempt to recover at all.
   *
   * A negative `attempts` number is interpreted as "infinite", which results in the exact same behavior as `recoverWith`.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
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
   */
  def recoverWithRetries(
      attempts: Int,
      pf: PartialFunction[Throwable, Graph[SourceShape[Out], NotUsed]]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.recoverWithRetries(attempts, pf))

  /**
   * RecoverWithRetries allows to switch to alternative Source on flow failure. It will stay in effect after
   * a failure has been recovered up to `attempts` number of times so that each time there is a failure
   * it is fed into the `pf` and a new Source may be materialized. Note that if you pass in 0, this won't
   * attempt to recover at all.
   *
   * A negative `attempts` number is interpreted as "infinite", which results in the exact same behavior as `recoverWith`.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
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
   * @param clazz the class object of the failure cause
   * @param supplier supply the new Source to be materialized
   */
  def recoverWithRetries(
      attempts: Int,
      clazz: Class[_ <: Throwable],
      supplier: Supplier[Graph[SourceShape[Out], NotUsed]]): javadsl.Flow[In, Out, Mat] =
    recoverWithRetries(attempts, {
      case elem if clazz.isInstance(elem) => supplier.get()
    })

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
   * See also [[Flow.limit]], [[Flow.limitWeighted]]
   */
  def take(n: Long): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.take(n))

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
   *
   * See also [[Flow.limit]], [[Flow.limitWeighted]]
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def takeWithin(d: FiniteDuration): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.takeWithin(d))

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
   *
   * See also [[Flow.limit]], [[Flow.limitWeighted]]
   */
  @silent
  def takeWithin(d: java.time.Duration): javadsl.Flow[In, Out, Mat] =
    takeWithin(d.asScala)

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by conflating elements into a summary
   * until the subscriber is ready to accept them. For example a conflate step might average incoming numbers if the
   * upstream publisher is faster.
   *
   * This version of conflate allows to derive a seed from the first element and change the aggregated type to be
   * different than the input type. See [[Flow.conflate]] for a simpler version that does not change types.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' downstream stops backpressuring and there is a conflated element available
   *
   * '''Backpressures when''' never
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * see also [[Flow.conflate]] [[Flow.batch]] [[Flow.batchWeighted]]
   *
   * @param seed Provides the first state for a conflated value using the first unconsumed element as a start
   * @param aggregate Takes the currently aggregated value and the current pending element to produce a new aggregate
   *
   */
  def conflateWithSeed[S](
      seed: function.Function[Out, S],
      aggregate: function.Function2[S, Out, S]): javadsl.Flow[In, S, Mat] =
    new Flow(delegate.conflateWithSeed(seed.apply)(aggregate.apply))

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by conflating elements into a summary
   * until the subscriber is ready to accept them. For example a conflate step might average incoming numbers if the
   * upstream publisher is faster.
   *
   * This version of conflate does not change the output type of the stream. See [[Flow.conflateWithSeed]] for a
   * more flexible version that can take a seed function and transform elements while rolling up.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' downstream stops backpressuring and there is a conflated element available
   *
   * '''Backpressures when''' never
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * see also [[Flow.conflateWithSeed]] [[Flow.batch]] [[Flow.batchWeighted]]
   *
   * @param aggregate Takes the currently aggregated value and the current pending element to produce a new aggregate
   *
   */
  def conflate(aggregate: function.Function2[Out, Out, Out]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.conflate(aggregate.apply))

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by aggregating elements into batches
   * until the subscriber is ready to accept them. For example a batch step might store received elements in
   * an array up to the allowed max limit if the upstream publisher is faster.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' downstream stops backpressuring and there is an aggregated element available
   *
   * '''Backpressures when''' there are `max` batched elements and 1 pending element and downstream backpressures
   *
   * '''Completes when''' upstream completes and there is no batched/pending element waiting
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[Flow.conflate]], [[Flow.batchWeighted]]
   *
   * @param max maximum number of elements to batch before backpressuring upstream (must be positive non-zero)
   * @param seed Provides the first state for a batched value using the first unconsumed element as a start
   * @param aggregate Takes the currently batched value and the current pending element to produce a new aggregate
   */
  def batch[S](
      max: Long,
      seed: function.Function[Out, S],
      aggregate: function.Function2[S, Out, S]): javadsl.Flow[In, S, Mat] =
    new Flow(delegate.batch(max, seed.apply)(aggregate.apply))

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
   * See also [[Flow.conflate]], [[Flow.batch]]
   *
   * @param max maximum weight of elements to batch before backpressuring upstream (must be positive non-zero)
   * @param costFn a function to compute a single element weight
   * @param seed Provides the first state for a batched value using the first unconsumed element as a start
   * @param aggregate Takes the currently batched value and the current pending element to produce a new batch
   */
  def batchWeighted[S](
      max: Long,
      costFn: function.Function[Out, java.lang.Long],
      seed: function.Function[Out, S],
      aggregate: function.Function2[S, Out, S]): javadsl.Flow[In, S, Mat] =
    new Flow(delegate.batchWeighted(max, costFn.apply, seed.apply)(aggregate.apply))

  /**
   * Allows a faster downstream to progress independently of a slower upstream by extrapolating elements from an older
   * element until new element comes from the upstream. For example an expand step might repeat the last element for
   * the subscriber until it receives an update from upstream.
   *
   * This element will never "drop" upstream elements as all elements go through at least one extrapolation step.
   * This means that if the upstream is actually faster than the upstream it will be backpressured by the downstream
   * subscriber.
   *
   * Expand does not support [[akka.stream.Supervision#restart]] and [[akka.stream.Supervision#resume]].
   * Exceptions from the `expander` function will complete the stream with failure.
   *
   * See also [[#extrapolate]] for a version that always preserves the original element and allows for an initial "startup" element.
   *
   * '''Emits when''' downstream stops backpressuring
   *
   * '''Backpressures when''' downstream backpressures or iterator runs empty
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * @param expander       Takes the current extrapolation state to produce an output element and the next extrapolation
   *                       state.
   * @see [[#extrapolate]]
   */
  def expand[U](expander: function.Function[Out, java.util.Iterator[U]]): javadsl.Flow[In, U, Mat] =
    new Flow(delegate.expand(in => expander(in).asScala))

  /**
   * Allows a faster downstream to progress independent of a slower upstream.
   *
   * This is achieved by introducing "extrapolated" elements - based on those from upstream - whenever downstream
   * signals demand.
   *
   * Extrapolate does not support [[akka.stream.Supervision#restart]] and [[akka.stream.Supervision#resume]].
   * Exceptions from the `extrapolate` function will complete the stream with failure.
   *
   * See also [[#expand]] for a version that can overwrite the original element.
   *
   * '''Emits when''' downstream stops backpressuring, AND EITHER upstream emits OR initial element is present OR
   * `extrapolate` is non-empty and applicable
   *
   * '''Backpressures when''' downstream backpressures or current `extrapolate` runs empty
   *
   * '''Completes when''' upstream completes and current `extrapolate` runs empty
   *
   * '''Cancels when''' downstream cancels
   *
   * @param extrapolator Takes the current upstream element and provides a sequence of "extrapolated" elements based
   *                     on the original, to be emitted in case downstream signals demand.
   * @see [[#expand]]
   */
  def extrapolate(extrapolator: function.Function[Out @uncheckedVariance, java.util.Iterator[Out @uncheckedVariance]])
      : javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.extrapolate(in => extrapolator(in).asScala))

  /**
   * Allows a faster downstream to progress independent of a slower upstream.
   *
   * This is achieved by introducing "extrapolated" elements - based on those from upstream - whenever downstream
   * signals demand.
   *
   * Extrapolate does not support [[akka.stream.Supervision#restart]] and [[akka.stream.Supervision#resume]].
   * Exceptions from the `extrapolate` function will complete the stream with failure.
   *
   * See also [[#expand]] for a version that can overwrite the original element.
   *
   * '''Emits when''' downstream stops backpressuring, AND EITHER upstream emits OR initial element is present OR
   * `extrapolate` is non-empty and applicable
   *
   * '''Backpressures when''' downstream backpressures or current `extrapolate` runs empty
   *
   * '''Completes when''' upstream completes and current `extrapolate` runs empty
   *
   * '''Cancels when''' downstream cancels
   *
   * @param extrapolator Takes the current upstream element and provides a sequence of "extrapolated" elements based
   *                     on the original, to be emitted in case downstream signals demand.
   * @param initial      The initial element to be emitted, in case upstream is able to stall the entire stream.
   * @see [[#expand]]
   */
  def extrapolate(
      extrapolator: function.Function[Out @uncheckedVariance, java.util.Iterator[Out @uncheckedVariance]],
      initial: Out @uncheckedVariance): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.extrapolate(in => extrapolator(in).asScala, Some(initial)))

  /**
   * Adds a fixed size buffer in the flow that allows to store elements from a faster upstream until it becomes full.
   * Depending on the defined [[akka.stream.OverflowStrategy]] it might drop elements or backpressure the upstream if
   * there is no space available
   *
   * '''Emits when''' downstream stops backpressuring and there is a pending element in the buffer
   *
   * '''Backpressures when''' downstream backpressures or depending on OverflowStrategy:
   *  <ul>
   *    <li>Backpressure - backpressures when buffer is full</li>
   *    <li>DropHead, DropTail, DropBuffer - never backpressures</li>
   *    <li>Fail - fails the stream if buffer gets full</li>
   *  </ul>
   *
   * '''Completes when''' upstream completes and buffered elements have been drained
   *
   * '''Cancels when''' downstream cancels
   *
   * @param size The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def buffer(size: Int, overflowStrategy: OverflowStrategy): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.buffer(size, overflowStrategy))

  /**
   * Takes up to `n` elements from the stream (less than `n` if the upstream completes before emitting `n` elements)
   * and returns a pair containing a strict sequence of the taken element
   * and a stream representing the remaining elements. If ''n'' is zero or negative, then this will return a pair
   * of an empty collection and a stream containing the whole upstream unchanged.
   *
   * In case of an upstream error, depending on the current state
   *  - the master stream signals the error if less than `n` elements have been seen, and therefore the substream
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
  def prefixAndTail(n: Int): javadsl.Flow[In, akka.japi.Pair[java.util.List[Out], javadsl.Source[Out, NotUsed]], Mat] =
    new Flow(delegate.prefixAndTail(n).map { case (taken, tail) => akka.japi.Pair(taken.asJava, tail.asJava) })

  /**
   * This operation demultiplexes the incoming stream into separate output
   * streams, one for each element key. The key is computed for each element
   * using the given function. When a new key is encountered for the first time
   * a new substream is opened and subsequently fed with all elements belonging to
   * that key.
   *
   * WARNING: If `allowClosedSubstreamRecreation` is set to `false` (default behavior) the operator
   * keeps track of all keys of streams that have already been closed. If you expect an infinite
   * number of keys this can cause memory issues. Elements belonging to those keys are drained
   * directly and not send to the substream.
   *
   * Note: If `allowClosedSubstreamRecreation` is set to `true` substream completion and incoming
   * elements are subject to race-conditions. If elements arrive for a stream that is in the process
   * of closing these elements might get lost.
   *
   * The object returned from this method is not a normal [[Flow]],
   * it is a [[SubFlow]]. This means that after this operator all transformations
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
   * is [[akka.stream.Supervision#stop]] the stream and substreams will be completed
   * with failure.
   *
   * If the group by function `f` throws an exception and the supervision decision
   * is [[akka.stream.Supervision#resume]] or [[akka.stream.Supervision#restart]]
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
   * @param f computes the key for each element
   * @param allowClosedSubstreamRecreation enables recreation of already closed substreams if elements with their
   *        corresponding keys arrive after completion
   */
  def groupBy[K](
      maxSubstreams: Int,
      f: function.Function[Out, K],
      allowClosedSubstreamRecreation: Boolean): SubFlow[In, Out, Mat] =
    new SubFlow(delegate.groupBy(maxSubstreams, f.apply, allowClosedSubstreamRecreation))

  /**
   * This operation demultiplexes the incoming stream into separate output
   * streams, one for each element key. The key is computed for each element
   * using the given function. When a new key is encountered for the first time
   * a new substream is opened and subsequently fed with all elements belonging to
   * that key.
   *
   * WARNING: The operator keeps track of all keys of streams that have already been closed.
   * If you expect an infinite number of keys this can cause memory issues. Elements belonging
   * to those keys are drained directly and not send to the substream.
   *
   * @see [[#groupBy]]
   */
  def groupBy[K](maxSubstreams: Int, f: function.Function[Out, K]): SubFlow[In, Out, Mat] =
    new SubFlow(delegate.groupBy(maxSubstreams, f.apply, false))

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
   * The object returned from this method is not a normal [[Flow]],
   * it is a [[SubFlow]]. This means that after this operator all transformations
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
   * is [[akka.stream.Supervision#stop]] the stream and substreams will be completed
   * with failure.
   *
   * If the split predicate `p` throws an exception and the supervision decision
   * is [[akka.stream.Supervision#resume]] or [[akka.stream.Supervision#restart]]
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
   * '''Cancels when''' downstream cancels and substreams cancel on `SubstreamCancelStrategy.drain()`, downstream
   * cancels or any substream cancels on `SubstreamCancelStrategy.propagate()`
   *
   * See also [[Flow.splitAfter]].
   */
  def splitWhen(p: function.Predicate[Out]): SubFlow[In, Out, Mat] =
    new SubFlow(delegate.splitWhen(p.test))

  /**
   * This operation applies the given predicate to all incoming elements and
   * emits them to a stream of output streams, always beginning a new one with
   * the current element if the given predicate returns true for it.
   *
   * @see [[#splitWhen]]
   */
  def splitWhen(substreamCancelStrategy: SubstreamCancelStrategy)(p: function.Predicate[Out]): SubFlow[In, Out, Mat] =
    new SubFlow(delegate.splitWhen(substreamCancelStrategy)(p.test))

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
   * The object returned from this method is not a normal [[Flow]],
   * it is a [[SubFlow]]. This means that after this operator all transformations
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
   * See also [[Flow.splitWhen]].
   */
  def splitAfter(p: function.Predicate[Out]): SubFlow[In, Out, Mat] =
    new SubFlow(delegate.splitAfter(p.test))

  /**
   * This operation applies the given predicate to all incoming elements and
   * emits them to a stream of output streams. It *ends* the current substream when the
   * predicate is true.
   *
   * @see [[#splitAfter]]
   */
  def splitAfter(substreamCancelStrategy: SubstreamCancelStrategy)(p: function.Predicate[Out]): SubFlow[In, Out, Mat] =
    new SubFlow(delegate.splitAfter(substreamCancelStrategy)(p.test))

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
  def flatMapConcat[T, M](f: function.Function[Out, _ <: Graph[SourceShape[T], M]]): Flow[In, T, Mat] =
    new Flow(delegate.flatMapConcat[T, M](x => f(x)))

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
  def flatMapMerge[T, M](breadth: Int, f: function.Function[Out, _ <: Graph[SourceShape[T], M]]): Flow[In, T, Mat] =
    new Flow(delegate.flatMapMerge(breadth, o => f(o)))

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
  def concat[M](that: Graph[SourceShape[Out], M]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.concat(that))

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
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#concat]]
   */
  def concatMat[M, M2](
      that: Graph[SourceShape[Out], M],
      matF: function.Function2[Mat, M, M2]): javadsl.Flow[In, Out, M2] =
    new Flow(delegate.concatMat(that)(combinerToScala(matF)))

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
  def prepend[M](that: Graph[SourceShape[Out], M]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.prepend(that))

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
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#prepend]]
   */
  def prependMat[M, M2](
      that: Graph[SourceShape[Out], M],
      matF: function.Function2[Mat, M, M2]): javadsl.Flow[In, Out, M2] =
    new Flow(delegate.prependMat(that)(combinerToScala(matF)))

  /**
   * Provides a secondary source that will be consumed if this source completes without any
   * elements passing by. As soon as the first element comes through this stream, the alternative
   * will be cancelled.
   *
   * Note that this Flow will be materialized together with the [[Source]] and just kept
   * from producing elements by asserting back-pressure until its time comes or it gets
   * cancelled.
   *
   * On errors the operator is failed regardless of source of the error.
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
  def orElse[M](secondary: Graph[SourceShape[Out], M]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.orElse(secondary))

  /**
   * Provides a secondary source that will be consumed if this source completes without any
   * elements passing by. As soon as the first element comes through this stream, the alternative
   * will be cancelled.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#orElse]]
   */
  def orElseMat[M2, M3](
      secondary: Graph[SourceShape[Out], M2],
      matF: function.Function2[Mat, M2, M3]): javadsl.Flow[In, Out, M3] =
    new Flow(delegate.orElseMat(secondary)(combinerToScala(matF)))

  /**
   * Attaches the given [[Sink]] to this [[Flow]], meaning that elements that passes
   * through will also be sent to the [[Sink]].
   *
   * It is similar to [[#wireTap]] but will backpressure instead of dropping elements when the given [[Sink]] is not ready.
   *
   * '''Emits when''' element is available and demand exists both from the Sink and the downstream.
   *
   * '''Backpressures when''' downstream or Sink backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream or Sink cancels
   */
  def alsoTo(that: Graph[SinkShape[Out], _]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.alsoTo(that))

  /**
   * Attaches the given [[Sink]] to this [[Flow]], meaning that elements that passes
   * through will also be sent to the [[Sink]].
   *
   * It is similar to [[#wireTapMat]] but will backpressure instead of dropping elements when the given [[Sink]] is not ready.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#alsoTo]]
   */
  def alsoToMat[M2, M3](
      that: Graph[SinkShape[Out], M2],
      matF: function.Function2[Mat, M2, M3]): javadsl.Flow[In, Out, M3] =
    new Flow(delegate.alsoToMat(that)(combinerToScala(matF)))

  /**
   * Attaches the given [[Sink]] to this [[Flow]], meaning that elements will be sent to the [[Sink]]
   * instead of being passed through if the predicate `when` returns `true`.
   *
   * '''Emits when''' emits when an element is available from the input and the chosen output has demand
   *
   * '''Backpressures when''' the currently chosen output back-pressures
   *
   * '''Completes when''' upstream completes and no output is pending
   *
   * '''Cancels when''' any of the downstreams cancel
   */
  def divertTo(that: Graph[SinkShape[Out], _], when: function.Predicate[Out]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.divertTo(that, when.test))

  /**
   * Attaches the given [[Sink]] to this [[Flow]], meaning that elements will be sent to the [[Sink]]
   * instead of being passed through if the predicate `when` returns `true`.
   *
   * @see [[#divertTo]]
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   */
  def divertToMat[M2, M3](
      that: Graph[SinkShape[Out], M2],
      when: function.Predicate[Out],
      matF: function.Function2[Mat, M2, M3]): javadsl.Flow[In, Out, M3] =
    new Flow(delegate.divertToMat(that, when.test)(combinerToScala(matF)))

  /**
   * Attaches the given [[Sink]] to this [[Flow]] as a wire tap, meaning that elements that pass
   * through will also be sent to the wire-tap Sink, without the latter affecting the mainline flow.
   * If the wire-tap Sink backpressures, elements that would've been sent to it will be dropped instead.
   *
   * It is similar to [[#alsoTo]] which does backpressure instead of dropping elements.
   *
   * '''Emits when''' element is available and demand exists from the downstream; the element will
   * also be sent to the wire-tap Sink if there is demand.
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def wireTap(that: Graph[SinkShape[Out], _]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.wireTap(that))

  /**
   * Attaches the given [[Sink]] to this [[Flow]] as a wire tap, meaning that elements that pass
   * through will also be sent to the wire-tap Sink, without the latter affecting the mainline flow.
   * If the wire-tap Sink backpressures, elements that would've been sent to it will be dropped instead.
   *
   * It is similar to [[#alsoToMat]] which does backpressure instead of dropping elements.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#wireTap]]
   */
  def wireTapMat[M2, M3](
      that: Graph[SinkShape[Out], M2],
      matF: function.Function2[Mat, M2, M3]): javadsl.Flow[In, Out, M3] =
    new Flow(delegate.wireTapMat(that)(combinerToScala(matF)))

  /**
   * Interleave is a deterministic merge of the given [[Source]] with elements of this [[Flow]].
   * It first emits `segmentSize` number of elements from this flow to downstream, then - same amount for `that` source,
   * then repeat process.
   *
   * Example:
   * {{{
   * Source<Integer, ?> src = Source.from(Arrays.asList(1, 2, 3))
   * Flow<Integer, Integer, ?> flow = flow.interleave(Source.from(Arrays.asList(4, 5, 6, 7)), 2)
   * src.via(flow) // 1, 2, 4, 5, 3, 6, 7
   * }}}
   *
   * After one of upstreams is complete than all the rest elements will be emitted from the second one
   *
   * If this [[Flow]] or [[Source]] gets upstream error - stream completes with failure.
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
  def interleave(that: Graph[SourceShape[Out], _], segmentSize: Int): javadsl.Flow[In, Out, Mat] =
    interleave(that, segmentSize, eagerClose = false)

  /**
   * Interleave is a deterministic merge of the given [[Source]] with elements of this [[Flow]].
   * It first emits `segmentSize` number of elements from this flow to downstream, then - same amount for `that` source,
   * then repeat process.
   *
   * If eagerClose is false and one of the upstreams complete the elements from the other upstream will continue passing
   * through the interleave operator. If eagerClose is true and one of the upstream complete interleave will cancel the
   * other upstream and complete itself.
   *
   * If this [[Flow]] or [[Source]] gets upstream error - stream completes with failure.
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
  def interleave(that: Graph[SourceShape[Out], _], segmentSize: Int, eagerClose: Boolean): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.interleave(that, segmentSize, eagerClose))

  /**
   * Interleave is a deterministic merge of the given [[Source]] with elements of this [[Flow]].
   * It first emits `segmentSize` number of elements from this flow to downstream, then - same amount for `that` source,
   * then repeat process.
   *
   * After one of upstreams is complete than all the rest elements will be emitted from the second one
   *
   * If this [[Flow]] or [[Source]] gets upstream error - stream completes with failure.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#interleave]]
   */
  def interleaveMat[M, M2](
      that: Graph[SourceShape[Out], M],
      segmentSize: Int,
      matF: function.Function2[Mat, M, M2]): javadsl.Flow[In, Out, M2] =
    interleaveMat(that, segmentSize, eagerClose = false, matF)

  /**
   * Interleave is a deterministic merge of the given [[Source]] with elements of this [[Flow]].
   * It first emits `segmentSize` number of elements from this flow to downstream, then - same amount for `that` source,
   * then repeat process.
   *
   * If eagerClose is false and one of the upstreams complete the elements from the other upstream will continue passing
   * through the interleave operator. If eagerClose is true and one of the upstream complete interleave will cancel the
   * other upstream and complete itself.
   *
   * If this [[Flow]] or [[Source]] gets upstream error - stream completes with failure.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#interleave]]
   */
  def interleaveMat[M, M2](
      that: Graph[SourceShape[Out], M],
      segmentSize: Int,
      eagerClose: Boolean,
      matF: function.Function2[Mat, M, M2]): javadsl.Flow[In, Out, M2] =
    new Flow(delegate.interleaveMat(that, segmentSize, eagerClose)(combinerToScala(matF)))

  /**
   * Merge the given [[Source]] to this [[Flow]], taking elements as they arrive from input streams,
   * picking randomly when several elements ready.
   *
   * '''Emits when''' one of the inputs has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' all upstreams complete
   *
   * '''Cancels when''' downstream cancels
   */
  def merge(that: Graph[SourceShape[Out], _]): javadsl.Flow[In, Out, Mat] =
    merge(that, eagerComplete = false)

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
  def merge(that: Graph[SourceShape[Out], _], eagerComplete: Boolean): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.merge(that, eagerComplete))

  /**
   * Merge the given [[Source]] to this [[Flow]], taking elements as they arrive from input streams,
   * picking randomly when several elements ready.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#merge]]
   */
  def mergeMat[M, M2](
      that: Graph[SourceShape[Out], M],
      matF: function.Function2[Mat, M, M2]): javadsl.Flow[In, Out, M2] =
    mergeMat(that, matF, eagerComplete = false)

  /**
   * Merge the given [[Source]] to this [[Flow]], taking elements as they arrive from input streams,
   * picking randomly when several elements ready.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#merge]]
   */
  def mergeMat[M, M2](
      that: Graph[SourceShape[Out], M],
      matF: function.Function2[Mat, M, M2],
      eagerComplete: Boolean): javadsl.Flow[In, Out, M2] =
    new Flow(delegate.mergeMat(that, eagerComplete)(combinerToScala(matF)))

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
  def mergeSorted[M](that: Graph[SourceShape[Out], M], comp: Comparator[Out]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.mergeSorted(that)(Ordering.comparatorToOrdering(comp)))

  /**
   * Merge the given [[Source]] to this [[Flow]], taking elements as they arrive from input streams,
   * picking always the smallest of the available elements (waiting for one element from each side
   * to be available). This means that possible contiguity of the input streams is not exploited to avoid
   * waiting for elements, this merge will block when one of the inputs does not have more elements (and
   * does not complete).
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#mergeSorted]].
   */
  def mergeSortedMat[Mat2, Mat3](
      that: Graph[SourceShape[Out], Mat2],
      comp: Comparator[Out],
      matF: function.Function2[Mat, Mat2, Mat3]): javadsl.Flow[In, Out, Mat3] =
    new Flow(delegate.mergeSortedMat(that)(combinerToScala(matF))(Ordering.comparatorToOrdering(comp)))

  /**
   * Combine the elements of current [[Flow]] and the given [[Source]] into a stream of tuples.
   *
   * '''Emits when''' all of the inputs have an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' any upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zip[T](source: Graph[SourceShape[T], _]): javadsl.Flow[In, Out Pair T, Mat] =
    zipMat(source, Keep.left)

  /**
   * Combine the elements of current [[Flow]] and the given [[Source]] into a stream of tuples.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#zip]]
   */
  def zipMat[T, M, M2](
      that: Graph[SourceShape[T], M],
      matF: function.Function2[Mat, M, M2]): javadsl.Flow[In, Out Pair T, M2] =
    this.viaMat(
      Flow.fromGraph(
        GraphDSL.create(that, new function.Function2[GraphDSL.Builder[M], SourceShape[T], FlowShape[Out, Out Pair T]] {
          def apply(b: GraphDSL.Builder[M], s: SourceShape[T]): FlowShape[Out, Out Pair T] = {
            val zip: FanInShape2[Out, T, Out Pair T] = b.add(Zip.create[Out, T])
            b.from(s).toInlet(zip.in1)
            FlowShape(zip.in0, zip.out)
          }
        })),
      matF)

  /**
   * Combine the elements of 2 streams into a stream of tuples, picking always the latest element of each.
   *
   * A `ZipLatest` has a `left` and a `right` input port and one `out` port.
   *
   * No element is emitted until at least one element from each Source becomes available.
   *
   * '''Emits when''' all of the inputs have at least an element available, and then each time an element becomes
   * *   available on either of the inputs
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' any upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zipLatest[T](source: Graph[SourceShape[T], _]): javadsl.Flow[In, Out Pair T, Mat] =
    zipLatestMat(source, Keep.left)

  /**
   * Combine the elements of current [[Flow]] and the given [[Source]] into a stream of tuples, picking always the latest element of each.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#zipLatest]]
   */
  def zipLatestMat[T, M, M2](
      that: Graph[SourceShape[T], M],
      matF: function.Function2[Mat, M, M2]): javadsl.Flow[In, Out Pair T, M2] =
    this.viaMat(
      Flow.fromGraph(
        GraphDSL.create(that, new function.Function2[GraphDSL.Builder[M], SourceShape[T], FlowShape[Out, Out Pair T]] {
          def apply(b: GraphDSL.Builder[M], s: SourceShape[T]): FlowShape[Out, Out Pair T] = {
            val zip: FanInShape2[Out, T, Out Pair T] = b.add(ZipLatest.create[Out, T])
            b.from(s).toInlet(zip.in1)
            FlowShape(zip.in0, zip.out)
          }
        })),
      matF)

  /**
   * Put together the elements of current [[Flow]] and the given [[Source]]
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
  def zipWith[Out2, Out3](
      that: Graph[SourceShape[Out2], _],
      combine: function.Function2[Out, Out2, Out3]): javadsl.Flow[In, Out3, Mat] =
    new Flow(delegate.zipWith[Out2, Out3](that)(combinerToScala(combine)))

  /**
   * Put together the elements of current [[Flow]] and the given [[Source]]
   * into a stream of combined elements using a combiner function.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#zipWith]]
   */
  def zipWithMat[Out2, Out3, M, M2](
      that: Graph[SourceShape[Out2], M],
      combine: function.Function2[Out, Out2, Out3],
      matF: function.Function2[Mat, M, M2]): javadsl.Flow[In, Out3, M2] =
    new Flow(delegate.zipWithMat[Out2, Out3, M, M2](that)(combinerToScala(combine))(combinerToScala(matF)))

  /**
   * Combine the elements of multiple streams into a stream of combined elements using a combiner function,
   * picking always the latest of the elements of each source.
   *
   * No element is emitted until at least one element from each Source becomes available. Whenever a new
   * element appears, the zipping function is invoked with a tuple containing the new element
   * and the other last seen elements.
   *
   *   '''Emits when''' all of the inputs have at least an element available, and then each time an element becomes
   *   available on either of the inputs
   *
   *   '''Backpressures when''' downstream backpressures
   *
   *   '''Completes when''' any of the upstreams completes
   *
   *   '''Cancels when''' downstream cancels
   */
  def zipLatestWith[Out2, Out3](
      that: Graph[SourceShape[Out2], _],
      combine: function.Function2[Out, Out2, Out3]): javadsl.Flow[In, Out3, Mat] =
    new Flow(delegate.zipLatestWith[Out2, Out3](that)(combinerToScala(combine)))

  /**
   * Put together the elements of current [[Flow]] and the given [[Source]]
   * into a stream of combined elements using a combiner function, picking always the latest element of each.
   *
   * It is recommended to use the internally optimized `Keep.left` and `Keep.right` combiners
   * where appropriate instead of manually writing functions that pass through one of the values.
   *
   * @see [[#zipLatestWith]]
   */
  def zipLatestWithMat[Out2, Out3, M, M2](
      that: Graph[SourceShape[Out2], M],
      combine: function.Function2[Out, Out2, Out3],
      matF: function.Function2[Mat, M, M2]): javadsl.Flow[In, Out3, M2] =
    new Flow(delegate.zipLatestWithMat[Out2, Out3, M, M2](that)(combinerToScala(combine))(combinerToScala(matF)))

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
  def zipWithIndex: Flow[In, Pair[Out, java.lang.Long], Mat] =
    new Flow(delegate.zipWithIndex.map { case (elem, index) => Pair[Out, java.lang.Long](elem, index) })

  /**
   * If the first element has not passed through this operator before the provided timeout, the stream is failed
   * with a [[java.util.concurrent.TimeoutException]].
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses before first element arrives
   *
   * '''Cancels when''' downstream cancels
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def initialTimeout(timeout: FiniteDuration): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.initialTimeout(timeout))

  /**
   * If the first element has not passed through this operator before the provided timeout, the stream is failed
   * with a [[java.util.concurrent.TimeoutException]].
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses before first element arrives
   *
   * '''Cancels when''' downstream cancels
   */
  @silent
  def initialTimeout(timeout: java.time.Duration): javadsl.Flow[In, Out, Mat] =
    initialTimeout(timeout.asScala)

  /**
   * If the completion of the stream does not happen until the provided timeout, the stream is failed
   * with a [[java.util.concurrent.TimeoutException]].
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses before upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def completionTimeout(timeout: FiniteDuration): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.completionTimeout(timeout))

  /**
   * If the completion of the stream does not happen until the provided timeout, the stream is failed
   * with a [[java.util.concurrent.TimeoutException]].
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses before upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  @silent
  def completionTimeout(timeout: java.time.Duration): javadsl.Flow[In, Out, Mat] =
    completionTimeout(timeout.asScala)

  /**
   * If the time between two processed elements exceeds the provided timeout, the stream is failed
   * with a [[java.util.concurrent.TimeoutException]]. The timeout is checked periodically,
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
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def idleTimeout(timeout: FiniteDuration): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.idleTimeout(timeout))

  /**
   * If the time between two processed elements exceeds the provided timeout, the stream is failed
   * with a [[java.util.concurrent.TimeoutException]]. The timeout is checked periodically,
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
  @silent
  def idleTimeout(timeout: java.time.Duration): javadsl.Flow[In, Out, Mat] =
    idleTimeout(timeout.asScala)

  /**
   * If the time between the emission of an element and the following downstream demand exceeds the provided timeout,
   * the stream is failed with a [[java.util.concurrent.TimeoutException]]. The timeout is checked periodically,
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
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def backpressureTimeout(timeout: FiniteDuration): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.backpressureTimeout(timeout))

  /**
   * If the time between the emission of an element and the following downstream demand exceeds the provided timeout,
   * the stream is failed with a [[java.util.concurrent.TimeoutException]]. The timeout is checked periodically,
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
  @silent
  def backpressureTimeout(timeout: java.time.Duration): javadsl.Flow[In, Out, Mat] =
    backpressureTimeout(timeout.asScala)

  /**
   * Injects additional elements if upstream does not emit for a configured amount of time. In other words, this
   * operator attempts to maintains a base rate of emitted elements towards the downstream.
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
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def keepAlive(maxIdle: FiniteDuration, injectedElem: function.Creator[Out]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.keepAlive(maxIdle, () => injectedElem.create()))

  /**
   * Injects additional elements if upstream does not emit for a configured amount of time. In other words, this
   * operator attempts to maintains a base rate of emitted elements towards the downstream.
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
  @silent
  def keepAlive(maxIdle: java.time.Duration, injectedElem: function.Creator[Out]): javadsl.Flow[In, Out, Mat] =
    keepAlive(maxIdle.asScala, injectedElem)

  /**
   * Sends elements downstream with speed limited to `elements/per`. In other words, this operator set the maximum rate
   * for emitting messages. This operator works for streams where all elements have the same cost or length.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as element costs. If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Elements that costs more than the allowed burst will be delayed proportionally
   * to their cost minus available tokens, meeting the target rate. Bucket is full when stream just materialized and
   * started.
   *
   * The burst size is calculated based on the given rate (`cost/per`) as 0.1 * rate, for example:
   * - rate < 20/second => burst size 1
   * - rate 20/second => burst size 2
   * - rate 100/second => burst size 10
   * - rate 200/second => burst size 20
   *
   * The throttle `mode` is [[akka.stream.ThrottleMode.Shaping]], which makes pauses before emitting messages to
   * meet throttle rate.
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures or the incoming rate is higher than the speed limit
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def throttle(elements: Int, per: java.time.Duration): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.throttle(elements, per.asScala))

  /**
   * Sends elements downstream with speed limited to `elements/per`. In other words, this operator set the maximum rate
   * for emitting messages. This operator works for streams where all elements have the same cost or length.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size or maximumBurst).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as element costs. If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Elements that costs more than the allowed burst will be delayed proportionally
   * to their cost minus available tokens, meeting the target rate. Bucket is full when stream just materialized and started.
   *
   * Parameter `mode` manages behavior when upstream is faster than throttle rate:
   *  - [[akka.stream.ThrottleMode.Shaping]] makes pauses before emitting messages to meet throttle rate
   *  - [[akka.stream.ThrottleMode.Enforcing]] fails with exception when upstream is faster than throttle rate
   *
   * It is recommended to use non-zero burst sizes as they improve both performance and throttling precision by allowing
   * the implementation to avoid using the scheduler when input rates fall below the enforced limit and to reduce
   * most of the inaccuracy caused by the scheduler resolution (which is in the range of milliseconds).
   *
   *  WARNING: Be aware that throttle is using scheduler to slow down the stream. This scheduler has minimal time of triggering
   *  next push. Consequently it will slow down the stream as it has minimal pause for emitting. This can happen in
   *  case burst is 0 and speed is higher than 30 events per second. You need to increase the `maximumBurst`  if
   *  elements arrive with small interval (30 milliseconds or less). Use the overloaded `throttle` method without
   *  `maximumBurst` parameter to automatically calculate the `maximumBurst` based on the given rate (`cost/per`).
   *  In other words the throttler always enforces the rate limit when `maximumBurst` parameter is given, but in
   *  certain cases (mostly due to limited scheduler resolution) it enforces a tighter bound than what was prescribed.
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures or the incoming rate is higher than the speed limit
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def throttle(elements: Int, per: FiniteDuration, maximumBurst: Int, mode: ThrottleMode): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.throttle(elements, per, maximumBurst, mode))

  /**
   * Sends elements downstream with speed limited to `elements/per`. In other words, this operator set the maximum rate
   * for emitting messages. This operator works for streams where all elements have the same cost or length.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size or maximumBurst).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as element costs. If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Elements that costs more than the allowed burst will be delayed proportionally
   * to their cost minus available tokens, meeting the target rate. Bucket is full when stream just materialized and started.
   *
   * Parameter `mode` manages behavior when upstream is faster than throttle rate:
   *  - [[akka.stream.ThrottleMode.Shaping]] makes pauses before emitting messages to meet throttle rate
   *  - [[akka.stream.ThrottleMode.Enforcing]] fails with exception when upstream is faster than throttle rate
   *
   * It is recommended to use non-zero burst sizes as they improve both performance and throttling precision by allowing
   * the implementation to avoid using the scheduler when input rates fall below the enforced limit and to reduce
   * most of the inaccuracy caused by the scheduler resolution (which is in the range of milliseconds).
   *
   *  WARNING: Be aware that throttle is using scheduler to slow down the stream. This scheduler has minimal time of triggering
   *  next push. Consequently it will slow down the stream as it has minimal pause for emitting. This can happen in
   *  case burst is 0 and speed is higher than 30 events per second. You need to increase the `maximumBurst`  if
   *  elements arrive with small interval (30 milliseconds or less). Use the overloaded `throttle` method without
   *  `maximumBurst` parameter to automatically calculate the `maximumBurst` based on the given rate (`cost/per`).
   *  In other words the throttler always enforces the rate limit when `maximumBurst` parameter is given, but in
   *  certain cases (mostly due to limited scheduler resolution) it enforces a tighter bound than what was prescribed.
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures or the incoming rate is higher than the speed limit
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def throttle(
      elements: Int,
      per: java.time.Duration,
      maximumBurst: Int,
      mode: ThrottleMode): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.throttle(elements, per.asScala, maximumBurst, mode))

  /**
   * Sends elements downstream with speed limited to `cost/per`. Cost is
   * calculating for each element individually by calling `calculateCost` function.
   * This operator works for streams when elements have different cost(length).
   * Streams of `ByteString` for example.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size or maximumBurst).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as element costs. If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Elements that costs more than the allowed burst will be delayed proportionally
   * to their cost minus available tokens, meeting the target rate. Bucket is full when stream just materialized and started.
   *
   * Parameter `mode` manages behavior when upstream is faster than throttle rate:
   *  - [[akka.stream.ThrottleMode.Shaping]] makes pauses before emitting messages to meet throttle rate
   *  - [[akka.stream.ThrottleMode.Enforcing]] fails with exception when upstream is faster than throttle rate. Enforcing
   *  cannot emit elements that cost more than the maximumBurst
   *
   * It is recommended to use non-zero burst sizes as they improve both performance and throttling precision by allowing
   * the implementation to avoid using the scheduler when input rates fall below the enforced limit and to reduce
   * most of the inaccuracy caused by the scheduler resolution (which is in the range of milliseconds).
   *
   *  WARNING: Be aware that throttle is using scheduler to slow down the stream. This scheduler has minimal time of triggering
   *  next push. Consequently it will slow down the stream as it has minimal pause for emitting. This can happen in
   *  case burst is 0 and speed is higher than 30 events per second. You need to increase the `maximumBurst`  if
   *  elements arrive with small interval (30 milliseconds or less). Use the overloaded `throttle` method without
   *  `maximumBurst` parameter to automatically calculate the `maximumBurst` based on the given rate (`cost/per`).
   *  In other words the throttler always enforces the rate limit when `maximumBurst` parameter is given, but in
   *  certain cases (mostly due to limited scheduler resolution) it enforces a tighter bound than what was prescribed.
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures or the incoming rate is higher than the speed limit
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def throttle(
      cost: Int,
      per: FiniteDuration,
      maximumBurst: Int,
      costCalculation: function.Function[Out, Integer],
      mode: ThrottleMode): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.throttle(cost, per, maximumBurst, costCalculation.apply, mode))

  /**
   * Sends elements downstream with speed limited to `cost/per`. Cost is
   * calculating for each element individually by calling `calculateCost` function.
   * This operator works for streams when elements have different cost(length).
   * Streams of `ByteString` for example.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as element costs. If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Elements that costs more than the allowed burst will be delayed proportionally
   * to their cost minus available tokens, meeting the target rate. Bucket is full when stream just materialized and
   * started.
   *
   * The burst size is calculated based on the given rate (`cost/per`) as 0.1 * rate, for example:
   * - rate < 20/second => burst size 1
   * - rate 20/second => burst size 2
   * - rate 100/second => burst size 10
   * - rate 200/second => burst size 20
   *
   * The throttle `mode` is [[akka.stream.ThrottleMode.Shaping]], which makes pauses before emitting messages to
   * meet throttle rate.
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures or the incoming rate is higher than the speed limit
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def throttle(
      cost: Int,
      per: java.time.Duration,
      costCalculation: function.Function[Out, Integer]): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.throttle(cost, per.asScala, costCalculation.apply))

  /**
   * Sends elements downstream with speed limited to `cost/per`. Cost is
   * calculating for each element individually by calling `calculateCost` function.
   * This operator works for streams when elements have different cost(length).
   * Streams of `ByteString` for example.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size or maximumBurst).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as element costs. If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Elements that costs more than the allowed burst will be delayed proportionally
   * to their cost minus available tokens, meeting the target rate. Bucket is full when stream just materialized and started.
   *
   * Parameter `mode` manages behavior when upstream is faster than throttle rate:
   *  - [[akka.stream.ThrottleMode.Shaping]] makes pauses before emitting messages to meet throttle rate
   *  - [[akka.stream.ThrottleMode.Enforcing]] fails with exception when upstream is faster than throttle rate. Enforcing
   *  cannot emit elements that cost more than the maximumBurst
   *
   * It is recommended to use non-zero burst sizes as they improve both performance and throttling precision by allowing
   * the implementation to avoid using the scheduler when input rates fall below the enforced limit and to reduce
   * most of the inaccuracy caused by the scheduler resolution (which is in the range of milliseconds).
   *
   *  WARNING: Be aware that throttle is using scheduler to slow down the stream. This scheduler has minimal time of triggering
   *  next push. Consequently it will slow down the stream as it has minimal pause for emitting. This can happen in
   *  case burst is 0 and speed is higher than 30 events per second. You need to increase the `maximumBurst`  if
   *  elements arrive with small interval (30 milliseconds or less). Use the overloaded `throttle` method without
   *  `maximumBurst` parameter to automatically calculate the `maximumBurst` based on the given rate (`cost/per`).
   *  In other words the throttler always enforces the rate limit when `maximumBurst` parameter is given, but in
   *  certain cases (mostly due to limited scheduler resolution) it enforces a tighter bound than what was prescribed.
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures or the incoming rate is higher than the speed limit
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   */
  def throttle(
      cost: Int,
      per: java.time.Duration,
      maximumBurst: Int,
      costCalculation: function.Function[Out, Integer],
      mode: ThrottleMode): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.throttle(cost, per.asScala, maximumBurst, costCalculation.apply, mode))

  /**
   * This is a simplified version of throttle that spreads events evenly across the given time interval.
   *
   * Use this operator when you need just slow down a stream without worrying about exact amount
   * of time between events.
   *
   * If you want to be sure that no time interval has no more than specified number of events you need to use
   * [[throttle()]] with maximumBurst attribute.
   * @see [[#throttle]]
   */
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def throttleEven(elements: Int, per: FiniteDuration, mode: ThrottleMode): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.throttleEven(elements, per, mode))

  /**
   * This is a simplified version of throttle that spreads events evenly across the given time interval.
   *
   * Use this operator when you need just slow down a stream without worrying about exact amount
   * of time between events.
   *
   * If you want to be sure that no time interval has no more than specified number of events you need to use
   * [[throttle()]] with maximumBurst attribute.
   * @see [[#throttle]]
   */
  @Deprecated
  @deprecated("Use throttle without `maximumBurst` parameter instead.", "2.5.12")
  def throttleEven(elements: Int, per: java.time.Duration, mode: ThrottleMode): javadsl.Flow[In, Out, Mat] =
    throttleEven(elements, per.asScala, mode)

  /**
   * This is a simplified version of throttle that spreads events evenly across the given time interval.
   *
   * Use this operator when you need just slow down a stream without worrying about exact amount
   * of time between events.
   *
   * If you want to be sure that no time interval has no more than specified number of events you need to use
   * [[throttle()]] with maximumBurst attribute.
   * @see [[#throttle]]
   */
  @Deprecated
  @deprecated("Use throttle without `maximumBurst` parameter instead.", "2.5.12")
  def throttleEven(
      cost: Int,
      per: FiniteDuration,
      costCalculation: function.Function[Out, Integer],
      mode: ThrottleMode): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.throttleEven(cost, per, costCalculation.apply, mode))

  /**
   * This is a simplified version of throttle that spreads events evenly across the given time interval.
   *
   * Use this operator when you need just slow down a stream without worrying about exact amount
   * of time between events.
   *
   * If you want to be sure that no time interval has no more than specified number of events you need to use
   * [[throttle()]] with maximumBurst attribute.
   * @see [[#throttle]]
   */
  @Deprecated
  @deprecated("Use throttle without `maximumBurst` parameter instead.", "2.5.12")
  def throttleEven(
      cost: Int,
      per: java.time.Duration,
      costCalculation: function.Function[Out, Integer],
      mode: ThrottleMode): javadsl.Flow[In, Out, Mat] =
    throttleEven(cost, per.asScala, costCalculation, mode)

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
  def detach: javadsl.Flow[In, Out, Mat] = new Flow(delegate.detach)

  /**
   * Materializes to `CompletionStage<Done>` that completes on getting termination message.
   * The future completes with success when received complete message from upstream or cancel
   * from downstream. It fails with the same error when received error message from
   * downstream.
   */
  def watchTermination[M]()(matF: function.Function2[Mat, CompletionStage[Done], M]): javadsl.Flow[In, Out, M] =
    new Flow(delegate.watchTermination()((left, right) => matF(left, right.toJava)))

  /**
   * Materializes to `FlowMonitor[Out]` that allows monitoring of the current flow. All events are propagated
   * by the monitor unchanged. Note that the monitor inserts a memory barrier every time it processes an
   * event, and may therefor affect performance.
   *
   * The `combine` function is used to combine the `FlowMonitor` with this flow's materialized value.
   */
  @Deprecated
  @deprecated("Use monitor() or monitorMat(combine) instead", "2.5.17")
  def monitor[M]()(combine: function.Function2[Mat, FlowMonitor[Out], M]): javadsl.Flow[In, Out, M] =
    new Flow(delegate.monitorMat(combinerToScala(combine)))

  /**
   * Materializes to `FlowMonitor[Out]` that allows monitoring of the current flow. All events are propagated
   * by the monitor unchanged. Note that the monitor inserts a memory barrier every time it processes an
   * event, and may therefor affect performance.
   *
   * The `combine` function is used to combine the `FlowMonitor` with this flow's materialized value.
   */
  def monitorMat[M](combine: function.Function2[Mat, FlowMonitor[Out], M]): javadsl.Flow[In, Out, M] =
    new Flow(delegate.monitorMat(combinerToScala(combine)))

  /**
   * Materializes to `Pair<Mat, FlowMonitor<<Out>>`, which is unlike most other operators (!),
   * in which usually the default materialized value keeping semantics is to keep the left value
   * (by passing `Keep.left()` to a `*Mat` version of a method). This operator is an exception from
   * that rule and keeps both values since dropping its sole purpose is to introduce that materialized value.
   *
   * The `FlowMonitor[Out]` allows monitoring of the current flow. All events are propagated
   * by the monitor unchanged. Note that the monitor inserts a memory barrier every time it processes an
   * event, and may therefor affect performance.
   */
  def monitor(): Flow[In, Out, Pair[Mat, FlowMonitor[Out]]] =
    monitorMat(Keep.both)

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
  @Deprecated
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "2.5.12")
  def initialDelay(delay: FiniteDuration): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.initialDelay(delay))

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
  @silent
  def initialDelay(delay: java.time.Duration): javadsl.Flow[In, Out, Mat] =
    initialDelay(delay.asScala)

  /**
   * Replace the attributes of this [[Flow]] with the given ones. If this Flow is a composite
   * of multiple graphs, new attributes on the composite will be less specific than attributes
   * set directly on the individual graphs of the composite.
   *
   * Note that this operation has no effect on an empty Flow (because the attributes apply
   * only to the contained processing operators).
   */
  override def withAttributes(attr: Attributes): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.withAttributes(attr))

  /**
   * Add the given attributes to this [[Flow]]. If the specific attribute was already present
   * on this graph this means the added attribute will be more specific than the existing one.
   * If this Flow is a composite of multiple graphs, new attributes on the composite will be
   * less specific than attributes set directly on the individual graphs of the composite.
   */
  override def addAttributes(attr: Attributes): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.addAttributes(attr))

  /**
   * Add a ``name`` attribute to this Flow.
   */
  override def named(name: String): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.named(name))

  /**
   * Put an asynchronous boundary around this `Flow`
   */
  override def async: javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.async)

  /**
   * Put an asynchronous boundary around this `Flow`
   *
   * @param dispatcher Run the graph on this dispatcher
   */
  override def async(dispatcher: String): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.async(dispatcher))

  /**
   * Put an asynchronous boundary around this `Flow`
   *
   * @param dispatcher      Run the graph on this dispatcher
   * @param inputBufferSize Set the input buffer to this size for the graph
   */
  override def async(dispatcher: String, inputBufferSize: Int): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.async(dispatcher, inputBufferSize))

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
   *
   * The `extract` function will be applied to each element before logging, so it is possible to log only those fields
   * of a complex object flowing through this element.
   *
   * Uses the given [[LoggingAdapter]] for logging.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def log(name: String, extract: function.Function[Out, Any], log: LoggingAdapter): javadsl.Flow[In, Out, Mat] =
    new Flow(delegate.log(name, e => extract.apply(e))(log))

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
   *
   * The `extract` function will be applied to each element before logging, so it is possible to log only those fields
   * of a complex object flowing through this element.
   *
   * Uses an internally created [[LoggingAdapter]] which uses `akka.stream.Log` as it's source (use this class to configure slf4j loggers).
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def log(name: String, extract: function.Function[Out, Any]): javadsl.Flow[In, Out, Mat] =
    this.log(name, extract, null)

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
   *
   * Uses the given [[LoggingAdapter]] for logging.
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def log(name: String, log: LoggingAdapter): javadsl.Flow[In, Out, Mat] =
    this.log(name, ConstantFun.javaIdentityFunction[Out], log)

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow.
   *
   * Uses an internally created [[LoggingAdapter]] which uses `akka.stream.Log` as it's source (use this class to configure slf4j loggers).
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def log(name: String): javadsl.Flow[In, Out, Mat] =
    this.log(name, ConstantFun.javaIdentityFunction[Out], null)

  /**
   * Converts this Flow to a [[RunnableGraph]] that materializes to a Reactive Streams [[org.reactivestreams.Processor]]
   * which implements the operations encapsulated by this Flow. Every materialization results in a new Processor
   * instance, i.e. the returned [[RunnableGraph]] is reusable.
   *
   * @return A [[RunnableGraph]] that materializes to a Processor when run() is called on it.
   */
  def toProcessor: RunnableGraph[Processor[In, Out]] = {
    RunnableGraph.fromGraph(delegate.toProcessor)
  }

  /**
   * Turns a Flow into a FlowWithContext which manages a context per element along a stream.
   *
   * @param collapseContext turn each incoming pair of element and context value into an element of this Flow
   * @param extractContext turn each outgoing element of this Flow into an outgoing context value
   *
   * API MAY CHANGE
   */
  @ApiMayChange
  def asFlowWithContext[U, CtxU, CtxOut](
      collapseContext: function.Function2[U, CtxU, In],
      extractContext: function.Function[Out, CtxOut]): FlowWithContext[U, CtxU, Out, CtxOut, Mat] =
    this.asScala.asFlowWithContext((x: U, c: CtxU) => collapseContext.apply(x, c))(x => extractContext.apply(x)).asJava

}

object RunnableGraph {

  /**
   * A graph with a closed shape is logically a runnable graph, this method makes
   * it so also in type.
   */
  def fromGraph[Mat](graph: Graph[ClosedShape, Mat]): RunnableGraph[Mat] =
    graph match {
      case r: RunnableGraph[Mat] => r
      case _                     => new RunnableGraphAdapter[Mat](scaladsl.RunnableGraph.fromGraph(graph))
    }

  /** INTERNAL API */
  private final class RunnableGraphAdapter[Mat](runnable: scaladsl.RunnableGraph[Mat]) extends RunnableGraph[Mat] {
    override def shape = ClosedShape
    override def traversalBuilder = runnable.traversalBuilder

    override def toString: String = runnable.toString

    override def mapMaterializedValue[Mat2](f: function.Function[Mat, Mat2]): RunnableGraphAdapter[Mat2] =
      new RunnableGraphAdapter(runnable.mapMaterializedValue(f.apply _))

    override def run(materializer: Materializer): Mat = runnable.run()(materializer)

    override def withAttributes(attr: Attributes): RunnableGraphAdapter[Mat] = {
      val newRunnable = runnable.withAttributes(attr)
      if (newRunnable eq runnable) this
      else new RunnableGraphAdapter(newRunnable)
    }

    override def asScala: scaladsl.RunnableGraph[Mat] = runnable
  }
}

/**
 * Java API
 *
 * Flow with attached input and output, can be executed.
 */
abstract class RunnableGraph[+Mat] extends Graph[ClosedShape, Mat] {

  /**
   * Run this flow and return the materialized values of the flow.
   */
  def run(materializer: Materializer): Mat

  /**
   * Transform only the materialized value of this RunnableGraph, leaving all other properties as they were.
   */
  def mapMaterializedValue[Mat2](f: function.Function[Mat, Mat2]): RunnableGraph[Mat2]

  override def withAttributes(attr: Attributes): RunnableGraph[Mat]

  override def addAttributes(attr: Attributes): RunnableGraph[Mat] =
    withAttributes(traversalBuilder.attributes and attr)

  override def named(name: String): RunnableGraph[Mat] =
    withAttributes(Attributes.name(name))

  /**
   * Converts this Java DSL element to its Scala DSL counterpart.
   */
  def asScala: scaladsl.RunnableGraph[Mat]
}
