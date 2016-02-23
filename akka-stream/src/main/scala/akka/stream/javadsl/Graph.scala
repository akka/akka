/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.javadsl

import akka.NotUsed
import akka.stream._
import akka.japi.{ Pair, function }
import scala.annotation.unchecked.uncheckedVariance
import akka.stream.impl.ConstantFun

/**
 * Merge several streams, taking elements as they arrive from input streams
 * (picking randomly when several have elements ready).
 *
 * '''Emits when''' one of the inputs has an element available
 *
 * '''Backpressures when''' downstream backpressures
 *
 * '''Completes when''' all upstreams complete (eagerComplete=false) or one upstream completes (eagerComplete=true)
 *
 * '''Cancels when''' downstream cancels
 */
object Merge {

  /**
   * Create a new `Merge` stage with the specified output type.
   */
  def create[T](inputPorts: Int): Graph[UniformFanInShape[T, T], NotUsed] =
    scaladsl.Merge(inputPorts)

  /**
   * Create a new `Merge` stage with the specified output type.
   */
  def create[T](clazz: Class[T], inputPorts: Int): Graph[UniformFanInShape[T, T], NotUsed] = create(inputPorts)

  /**
   * Create a new `Merge` stage with the specified output type.
   *
   * @param eagerComplete set to true in order to make this stage eagerly
   *                   finish as soon as one of its inputs completes
   */
  def create[T](inputPorts: Int, eagerComplete: Boolean): Graph[UniformFanInShape[T, T], NotUsed] =
    scaladsl.Merge(inputPorts, eagerComplete = eagerComplete)

  /**
   * Create a new `Merge` stage with the specified output type.
   *
   * @param eagerComplete set to true in order to make this stage eagerly
   *                   finish as soon as one of its inputs completes
   */
  def create[T](clazz: Class[T], inputPorts: Int, eagerComplete: Boolean): Graph[UniformFanInShape[T, T], NotUsed] =
    create(inputPorts, eagerComplete)
}

/**
 * Merge several streams, taking elements as they arrive from input streams
 * (picking from preferred when several have elements ready).
 *
 * '''Emits when''' one of the inputs has an element available, preferring
 * a specified input if multiple have elements available
 *
 * '''Backpressures when''' downstream backpressures
 *
 * '''Completes when''' all upstreams complete (eagerComplete=false) or one upstream completes (eagerComplete=true)
 *
 * '''Cancels when''' downstream cancels
 */
object MergePreferred {
  /**
   * Create a new `MergePreferred` stage with the specified output type.
   */
  def create[T](secondaryPorts: Int): Graph[scaladsl.MergePreferred.MergePreferredShape[T], NotUsed] =
    scaladsl.MergePreferred(secondaryPorts)

  /**
   * Create a new `MergePreferred` stage with the specified output type.
   */
  def create[T](clazz: Class[T], secondaryPorts: Int): Graph[scaladsl.MergePreferred.MergePreferredShape[T], NotUsed] = create(secondaryPorts)

  /**
   * Create a new `MergePreferred` stage with the specified output type.
   *
   * @param eagerComplete set to true in order to make this stage eagerly
   *                   finish as soon as one of its inputs completes
   */
  def create[T](secondaryPorts: Int, eagerComplete: Boolean): Graph[scaladsl.MergePreferred.MergePreferredShape[T], NotUsed] =
    scaladsl.MergePreferred(secondaryPorts, eagerComplete = eagerComplete)

  /**
   * Create a new `MergePreferred` stage with the specified output type.
   *
   * @param eagerComplete set to true in order to make this stage eagerly
   *                   finish as soon as one of its inputs completes
   */
  def create[T](clazz: Class[T], secondaryPorts: Int, eagerComplete: Boolean): Graph[scaladsl.MergePreferred.MergePreferredShape[T], NotUsed] =
    create(secondaryPorts, eagerComplete)

}

/**
 * Fan-out the stream to several streams. emitting each incoming upstream element to all downstream consumers.
 * It will not shutdown until the subscriptions for at least
 * two downstream subscribers have been established.
 *
 * '''Emits when''' all of the outputs stops backpressuring and there is an input element available
 *
 * '''Backpressures when''' any of the outputs backpressure
 *
 * '''Completes when''' upstream completes
 *
 * '''Cancels when'''
 *   If eagerCancel is enabled: when any downstream cancels; otherwise: when all downstreams cancel
 */
object Broadcast {
  /**
   * Create a new `Broadcast` stage with the specified input type.
   *
   * @param outputCount number of output ports
   * @param eagerCancel if true, broadcast cancels upstream if any of its downstreams cancel.
   */
  def create[T](outputCount: Int, eagerCancel: Boolean): Graph[UniformFanOutShape[T, T], NotUsed] =
    scaladsl.Broadcast(outputCount, eagerCancel = eagerCancel)

  /**
   * Create a new `Broadcast` stage with the specified input type.
   *
   * @param outputCount number of output ports
   */
  def create[T](outputCount: Int): Graph[UniformFanOutShape[T, T], NotUsed] = create(outputCount, eagerCancel = false)

  /**
   * Create a new `Broadcast` stage with the specified input type.
   */
  def create[T](clazz: Class[T], outputCount: Int): Graph[UniformFanOutShape[T, T], NotUsed] = create(outputCount)

}

/**
 * Fan-out the stream to several streams. emitting an incoming upstream element to one downstream consumer according
 * to the partitioner function applied to the element
 *
 * '''Emits when''' all of the outputs stops backpressuring and there is an input element available
 *
 * '''Backpressures when''' one of the outputs backpressure
 *
 * '''Completes when''' upstream completes
 *
 * '''Cancels when'''
 *   when one of the downstreams cancel
 */
object Partition {
  /**
   * Create a new `Partition` stage with the specified input type.
   *
   * @param outputCount number of output ports
   * @param partitioner function deciding which output each element will be targeted
   */
  def create[T](outputCount: Int, partitioner: function.Function[T, Int]): Graph[UniformFanOutShape[T, T], NotUsed] =
    scaladsl.Partition(outputCount, partitioner = (t: T) ⇒ partitioner.apply(t))

  /**
   * Create a new `Partition` stage with the specified input type.
   *
   * @param outputCount number of output ports
   * @param partitioner function deciding which output each element will be targeted
   */
  def create[T](clazz: Class[T], outputCount: Int, partitioner: function.Function[T, Int]): Graph[UniformFanOutShape[T, T], NotUsed] =
    create(outputCount, partitioner)

}

/**
 * Fan-out the stream to several streams. Each upstream element is emitted to the first available downstream consumer.
 * It will not shutdown until the subscriptions for at least
 * two downstream subscribers have been established.
 *
 * '''Emits when''' any of the outputs stops backpressuring; emits the element to the first available output
 *
 * '''Backpressures when''' all of the outputs backpressure
 *
 * '''Completes when''' upstream completes
 *
 * '''Cancels when''' all downstreams cancel
 */
object Balance {
  /**
   * Create a new `Balance` stage with the specified input type.
   *
   * @param waitForAllDownstreams if `true` it will not start emitting
   *   elements to downstream outputs until all of them have requested at least one element
   */
  def create[T](outputCount: Int, waitForAllDownstreams: Boolean): Graph[UniformFanOutShape[T, T], NotUsed] =
    scaladsl.Balance(outputCount, waitForAllDownstreams)

  /**
   * Create a new `Balance` stage with the specified input type.
   */
  def create[T](outputCount: Int): Graph[UniformFanOutShape[T, T], NotUsed] =
    create(outputCount, waitForAllDownstreams = false)

  /**
   * Create a new `Balance` stage with the specified input type.
   */
  def create[T](clazz: Class[T], outputCount: Int): Graph[UniformFanOutShape[T, T], NotUsed] =
    create(outputCount)

  /**
   * Create a new `Balance` stage with the specified input type.
   *
   * @param waitForAllDownstreams if `true` it will not start emitting
   *   elements to downstream outputs until all of them have requested at least one element
   */
  def create[T](clazz: Class[T], outputCount: Int, waitForAllDownstreams: Boolean): Graph[UniformFanOutShape[T, T], NotUsed] =
    create(outputCount, waitForAllDownstreams)
}

/**
 * Combine the elements of 2 streams into a stream of tuples.
 *
 * A `Zip` has a `left` and a `right` input port and one `out` port
 *
 * '''Emits when''' all of the inputs has an element available
 *
 * '''Backpressures when''' downstream backpressures
 *
 * '''Completes when''' any upstream completes
 *
 * '''Cancels when''' downstream cancels
 */
object Zip {
  import akka.japi.function.Function2
  import akka.japi.Pair

  /**
   * Create a new `Zip` stage with the specified input types and zipping-function
   * which creates `akka.japi.Pair`s.
   */
  def create[A, B]: Graph[FanInShape2[A, B, A Pair B], NotUsed] =
    ZipWith.create(_toPair.asInstanceOf[Function2[A, B, A Pair B]])

  private[this] final val _toPair: Function2[Any, Any, Any Pair Any] =
    new Function2[Any, Any, Any Pair Any] { override def apply(a: Any, b: Any): Any Pair Any = new Pair(a, b) }
}

/**
 * Takes a stream of pair elements and splits each pair to two output streams.
 *
 * An `Unzip` has one `in` port and one `left` and one `right` output port.
 *
 * '''Emits when''' all of the outputs stops backpressuring and there is an input element available
 *
 * '''Backpressures when''' any of the outputs backpressures
 *
 * '''Completes when''' upstream completes
 *
 * '''Cancels when''' any downstream cancels
 */
object Unzip {

  /**
   * Creates a new `Unzip` stage with the specified output types.
   */
  def create[A, B](): Graph[FanOutShape2[A Pair B, A, B], NotUsed] =
    UnzipWith.create(ConstantFun.javaIdentityFunction[Pair[A, B]])

  /**
   * Creates a new `Unzip` stage with the specified output types.
   */
  def create[A, B](left: Class[A], right: Class[B]): Graph[FanOutShape2[A Pair B, A, B], NotUsed] = create[A, B]()

}

/**
 * Takes two streams and outputs an output stream formed from the two input streams
 * by consuming one stream first emitting all of its elements, then consuming the
 * second stream emitting all of its elements.
 *
 * '''Emits when''' the current stream has an element available; if the current input completes, it tries the next one
 *
 * '''Backpressures when''' downstream backpressures
 *
 * '''Completes when''' all upstreams complete
 *
 * '''Cancels when''' downstream cancels
 */
object Concat {
  /**
   * Create a new anonymous `Concat` stage with the specified input types.
   */
  def create[T](): Graph[UniformFanInShape[T, T], NotUsed] = scaladsl.Concat[T]()

  /**
   * Create a new anonymous `Concat` stage with the specified input types.
   */
  def create[T](inputCount: Int): Graph[UniformFanInShape[T, T], NotUsed] = scaladsl.Concat[T](inputCount)

  /**
   * Create a new anonymous `Concat` stage with the specified input types.
   */
  def create[T](clazz: Class[T]): Graph[UniformFanInShape[T, T], NotUsed] = create()

}

// flow graph //

object GraphDSL extends GraphCreate {

  /**
   * Start building a [[GraphDSL]].
   *
   * The [[Builder]] is mutable and not thread-safe,
   * thus you should construct your Graph and then share the constructed immutable [[GraphDSL]].
   */
  def builder[M](): Builder[M] = new Builder()(new scaladsl.GraphDSL.Builder[M])

  final class Builder[+Mat]()(private implicit val delegate: scaladsl.GraphDSL.Builder[Mat]) { self ⇒
    import akka.stream.scaladsl.GraphDSL.Implicits._

    /**
     * Import a graph into this module, performing a deep copy, discarding its
     * materialized value and returning the copied Ports that are now to be
     * connected.
     */
    def add[S <: Shape](graph: Graph[S, _]): S = delegate.add(graph)

    /**
     * Returns an [[Outlet]] that gives access to the materialized value of this graph. Once the graph is materialized
     * this outlet will emit exactly one element which is the materialized value. It is possible to expose this
     * outlet as an externally accessible outlet of a [[Source]], [[Sink]], [[Flow]] or [[BidiFlow]].
     *
     * It is possible to call this method multiple times to get multiple [[Outlet]] instances if necessary. All of
     * the outlets will emit the materialized value.
     *
     * Be careful to not to feed the result of this outlet to a stage that produces the materialized value itself (for
     * example to a [[Sink#fold]] that contributes to the materialized value) since that might lead to an unresolvable
     * dependency cycle.
     *
     * @return The outlet that will emit the materialized value.
     */
    def materializedValue: Outlet[Mat @uncheckedVariance] = delegate.materializedValue

    def from[T](out: Outlet[T]): ForwardOps[T] = new ForwardOps(out)
    def from[T](src: SourceShape[T]): ForwardOps[T] = new ForwardOps(src.out)
    def from[I, O](f: FlowShape[I, O]): ForwardOps[O] = new ForwardOps(f.out)
    def from[I, O](j: UniformFanInShape[I, O]): ForwardOps[O] = new ForwardOps(j.out)
    def from[I, O](j: UniformFanOutShape[I, O]): ForwardOps[O] = new ForwardOps(findOut(delegate, j, 0))

    def to[T](in: Inlet[T]): ReverseOps[T] = new ReverseOps(in)
    def to[T](dst: SinkShape[T]): ReverseOps[T] = new ReverseOps(dst.in)
    def to[I, O](f: FlowShape[I, O]): ReverseOps[I] = new ReverseOps(f.in)
    def to[I, O](j: UniformFanInShape[I, O]): ReverseOps[I] = new ReverseOps(findIn(delegate, j, 0))
    def to[I, O](j: UniformFanOutShape[I, O]): ReverseOps[I] = new ReverseOps(j.in)

    final class ForwardOps[T](out: Outlet[T]) {
      def toInlet(in: Inlet[_ >: T]): Builder[Mat] = { out ~> in; self }
      def to(dst: SinkShape[_ >: T]): Builder[Mat] = { out ~> dst; self }
      def toFanIn[U](j: UniformFanInShape[_ >: T, U]): Builder[Mat] = { out ~> j; self }
      def toFanOut[U](j: UniformFanOutShape[_ >: T, U]): Builder[Mat] = { out ~> j; self }
      def via[U](f: FlowShape[_ >: T, U]): ForwardOps[U] = from((out ~> f).outlet)
      def viaFanIn[U](j: UniformFanInShape[_ >: T, U]): ForwardOps[U] = from((out ~> j).outlet)
      def viaFanOut[U](j: UniformFanOutShape[_ >: T, U]): ForwardOps[U] = from((out ~> j).outlet)
      def out(): Outlet[T] = out
    }

    final class ReverseOps[T](out: Inlet[T]) {
      def fromOutlet(dst: Outlet[_ <: T]): Builder[Mat] = { out <~ dst; self }
      def from(dst: SourceShape[_ <: T]): Builder[Mat] = { out <~ dst; self }
      def fromFanIn[U](j: UniformFanInShape[U, _ <: T]): Builder[Mat] = { out <~ j; self }
      def fromFanOut[U](j: UniformFanOutShape[U, _ <: T]): Builder[Mat] = { out <~ j; self }
      def via[U](f: FlowShape[U, _ <: T]): ReverseOps[U] = to((out <~ f).inlet)
      def viaFanIn[U](j: UniformFanInShape[U, _ <: T]): ReverseOps[U] = to((out <~ j).inlet)
      def viaFanOut[U](j: UniformFanOutShape[U, _ <: T]): ReverseOps[U] = to((out <~ j).inlet)
    }
  }
}
