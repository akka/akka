/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream._
import akka.japi.Pair

/**
 * Merge several streams, taking elements as they arrive from input streams
 * (picking randomly when several have elements ready).
 *
 * When building the [[FlowGraph]] you must connect one or more input sources
 * and one output sink to the `Merge` vertex.
 *
 * Note that a junction instance describes exactly one place (vertex) in the `FlowGraph`
 * that multiple flows can be attached to; if you want to have multiple independent
 * junctions within the same `FlowGraph` then you will have to create multiple such
 * instances.
 */
object Merge {

  /**
   * Create a new `Merge` vertex with the specified output type.
   */
  def create[T](outputCount: Int): Graph[UniformFanInShape[T, T], Unit] =
    scaladsl.Merge(outputCount)

  /**
   * Create a new `Merge` vertex with the specified output type.
   */
  def create[T](clazz: Class[T], outputCount: Int): Graph[UniformFanInShape[T, T], Unit] = create(outputCount)

}

/**
 * Merge several streams, taking elements as they arrive from input streams
 * (picking from preferred when several have elements ready).
 *
 * When building the [[FlowGraph]] you must connect one or more input streams
 * and one output sink to the `Merge` vertex.
 *
 * Note that a junction instance describes exactly one place (vertex) in the `FlowGraph`
 * that multiple flows can be attached to; if you want to have multiple independent
 * junctions within the same `FlowGraph` then you will have to create multiple such
 * instances.
 */
object MergePreferred {
  /**
   * Create a new `MergePreferred` vertex with the specified output type.
   */
  def create[T](outputCount: Int): Graph[scaladsl.MergePreferred.MergePreferredShape[T], Unit] =
    scaladsl.MergePreferred(outputCount)

  /**
   * Create a new `MergePreferred` vertex with the specified output type.
   */
  def create[T](clazz: Class[T], outputCount: Int): Graph[scaladsl.MergePreferred.MergePreferredShape[T], Unit] = create(outputCount)

}

/**
 * Fan-out the stream to several streams. Each element is produced to
 * the other streams. It will not shutdown until the subscriptions for at least
 * two downstream subscribers have been established.
 *
 * Note that a junction instance describes exactly one place (vertex) in the `FlowGraph`
 * that multiple flows can be attached to; if you want to have multiple independent
 * junctions within the same `FlowGraph` then you will have to create multiple such
 * instances.
 */
object Broadcast {
  /**
   * Create a new `Broadcast` vertex with the specified input type.
   */
  def create[T](outputCount: Int): Graph[UniformFanOutShape[T, T], Unit] =
    scaladsl.Broadcast(outputCount)

  /**
   * Create a new `Broadcast` vertex with the specified input type.
   */
  def create[T](clazz: Class[T], outputCount: Int): Graph[UniformFanOutShape[T, T], Unit] = create(outputCount)

}

/**
 * Fan-out the stream to several streams. Each element is produced to
 * one of the other streams. It will not shutdown until the subscriptions for at least
 * two downstream subscribers have been established.
 *
 * Note that a junction instance describes exactly one place (vertex) in the `FlowGraph`
 * that multiple flows can be attached to; if you want to have multiple independent
 * junctions within the same `FlowGraph` then you will have to create multiple such
 * instances.
 */
object Balance {
  /**
   * Create a new `Balance` vertex with the specified input type.
   *
   * @param waitForAllDownstreams if `true` it will not start emitting
   *   elements to downstream outputs until all of them have requested at least one element
   */
  def create[T](outputCount: Int, waitForAllDownstreams: Boolean): Graph[UniformFanOutShape[T, T], Unit] =
    scaladsl.Balance(outputCount, waitForAllDownstreams)

  /**
   * Create a new `Balance` vertex with the specified input type.
   */
  def create[T](outputCount: Int): Graph[UniformFanOutShape[T, T], Unit] = create(outputCount, false)

  /**
   * Create a new `Balance` vertex with the specified input type.
   */
  def create[T](clazz: Class[T], outputCount: Int): Graph[UniformFanOutShape[T, T], Unit] = create(outputCount)

  /**
   * Create a new `Balance` vertex with the specified input type.
   *
   * @param waitForAllDownstreams if `true` it will not start emitting
   *   elements to downstream outputs until all of them have requested at least one element
   */
  def create[T](clazz: Class[T], outputCount: Int, waitForAllDownstreams: Boolean): Graph[UniformFanOutShape[T, T], Unit] =
    create(outputCount, waitForAllDownstreams)
}

object Zip {
  import akka.stream.javadsl.japi.Function2
  import akka.japi.Pair

  /**
   * Create a new `ZipWith` vertex with the specified input types and zipping-function
   * which creates `akka.japi.Pair`s.
   */
  def create[A, B]: Graph[FanInShape2[A, B, A Pair B], Unit] =
    ZipWith.create(_toPair.asInstanceOf[Function2[A, B, A Pair B]])

  private[this] final val _toPair: Function2[Any, Any, Any Pair Any] =
    new Function2[Any, Any, Any Pair Any] { override def apply(a: Any, b: Any): Any Pair Any = new Pair(a, b) }
}

/**
 * Note that a junction instance describes exactly one place (vertex) in the `FlowGraph`
 * that multiple flows can be attached to; if you want to have multiple independent
 * junctions within the same `FlowGraph` then you will have to create multiple such
 * instances.
 */
object Unzip {

  /**
   * Creates a new `Unzip` vertex with the specified output types.
   */
  def create[A, B](): Graph[FanOutShape2[A Pair B, A, B], Unit] =
    scaladsl.FlowGraph.partial() { implicit b ⇒
      val unzip = b.add(scaladsl.Unzip[A, B]())
      val tuple = b.add(scaladsl.Flow[A Pair B].map(p ⇒ (p.first, p.second)))
      b.addEdge(tuple.outlet, unzip.in)
      new FanOutShape2(FanOutShape.Ports(tuple.inlet, unzip.out0 :: unzip.out1 :: Nil))
    }

  /**
   * Creates a new `Unzip` vertex with the specified output types.
   */
  def create[A, B](left: Class[A], right: Class[B]): Graph[FanOutShape2[A Pair B, A, B], Unit] = create[A, B]()

}

/**
 * Takes two streams and outputs an output stream formed from the two input streams
 * by consuming one stream first emitting all of its elements, then consuming the
 * second stream emitting all of its elements.
 *
 * Note that a junction instance describes exactly one place (vertex) in the `FlowGraph`
 * that multiple flows can be attached to; if you want to have multiple independent
 * junctions within the same `FlowGraph` then you will have to create multiple such
 * instances.
 */
object Concat {
  /**
   * Create a new anonymous `Concat` vertex with the specified input types.
   * Note that a `Concat` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](): Graph[UniformFanInShape[T, T], Unit] = scaladsl.Concat[T]()

  /**
   * Create a new anonymous `Concat` vertex with the specified input types.
   * Note that a `Concat` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](clazz: Class[T]): Graph[UniformFanInShape[T, T], Unit] = create()

}

// flow graph //

object FlowGraph {

  val factory: GraphCreate = new GraphCreate {}

  /**
   * Start building a [[FlowGraph]].
   *
   * The [[Builder]] is mutable and not thread-safe,
   * thus you should construct your Graph and then share the constructed immutable [[FlowGraph]].
   */
  def builder[M](): Builder[M] = new Builder()(new scaladsl.FlowGraph.Builder[M])

  final class Builder[+Mat]()(private implicit val delegate: scaladsl.FlowGraph.Builder[Mat]) { self ⇒
    import akka.stream.scaladsl.FlowGraph.Implicits._

    def flow[A, B, M](from: Outlet[A], via: Flow[A, B, M], to: Inlet[B]): Unit = delegate.addEdge(from, via.asScala, to)

    def edge[T](from: Outlet[T], to: Inlet[T]): Unit = delegate.addEdge(from, to)

    /**
     * Import a graph into this module, performing a deep copy, discarding its
     * materialized value and returning the copied Ports that are now to be
     * connected.
     */
    def graph[S <: Shape](graph: Graph[S, _]): S = delegate.add(graph)

    def source[T](source: Source[T, _]): Outlet[T] = delegate.add(source.asScala)

    def sink[T](sink: Sink[T, _]): Inlet[T] = delegate.add(sink.asScala)

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
    def matValue: Outlet[Mat] = delegate.matValue

    def run(mat: FlowMaterializer): Unit = delegate.buildRunnable().run()(mat)

    def from[T](out: Outlet[T]): ForwardOps[T] = new ForwardOps(out)
    def from[T, M](src: Source[T, M]): ForwardOps[T] = new ForwardOps(delegate.add(src.asScala))
    def from[T](src: SourceShape[T]): ForwardOps[T] = new ForwardOps(src.outlet)
    def from[I, O](f: FlowShape[I, O]): ForwardOps[O] = new ForwardOps(f.outlet)
    def from[I, O](j: UniformFanInShape[I, O]): ForwardOps[O] = new ForwardOps(j.out)
    def from[I, O](j: UniformFanOutShape[I, O]): ForwardOps[O] = new ForwardOps(findOut(delegate, j, 0))

    def to[T](in: Inlet[T]): ReverseOps[T] = new ReverseOps(in)
    def to[T, M](dst: Sink[T, M]): ReverseOps[T] = new ReverseOps(delegate.add(dst.asScala))
    def to[T](dst: SinkShape[T]): ReverseOps[T] = new ReverseOps(dst.inlet)
    def to[I, O](f: FlowShape[I, O]): ReverseOps[I] = new ReverseOps(f.inlet)
    def to[I, O](j: UniformFanInShape[I, O]): ReverseOps[I] = new ReverseOps(findIn(delegate, j, 0))
    def to[I, O](j: UniformFanOutShape[I, O]): ReverseOps[I] = new ReverseOps(j.in)

    final class ForwardOps[T](out: Outlet[T]) {
      def to(in: Inlet[T]): Builder[Mat] = { out ~> in; self }
      def to[M](dst: Sink[T, M]): Builder[Mat] = { out ~> dst.asScala; self }
      def to(dst: SinkShape[T]): Builder[Mat] = { out ~> dst; self }
      def to[U](f: FlowShape[T, U]): Builder[Mat] = { out ~> f; self }
      def to[U](j: UniformFanInShape[T, U]): Builder[Mat] = { out ~> j; self }
      def to[U](j: UniformFanOutShape[T, U]): Builder[Mat] = { out ~> j; self }
      def via[U, M](f: Flow[T, U, M]): ForwardOps[U] = from((out ~> f.asScala).outlet)
      def via[U](f: FlowShape[T, U]): ForwardOps[U] = from((out ~> f).outlet)
      def via[U](j: UniformFanInShape[T, U]): ForwardOps[U] = from((out ~> j).outlet)
      def via[U](j: UniformFanOutShape[T, U]): ForwardOps[U] = from((out ~> j).outlet)
      def out(): Outlet[T] = out
    }

    final class ReverseOps[T](out: Inlet[T]) {
      def from(dst: Outlet[T]): Builder[Mat] = { out <~ dst; self }
      def from[M](dst: Source[T, M]): Builder[Mat] = { out <~ dst.asScala; self }
      def from(dst: SourceShape[T]): Builder[Mat] = { out <~ dst; self }
      def from[U](f: FlowShape[U, T]): Builder[Mat] = { out <~ f; self }
      def from[U](j: UniformFanInShape[U, T]): Builder[Mat] = { out <~ j; self }
      def from[U](j: UniformFanOutShape[U, T]): Builder[Mat] = { out <~ j; self }
      def via[U, M](f: Flow[U, T, M]): ReverseOps[U] = to((out <~ f.asScala).inlet)
      def via[U](f: FlowShape[U, T]): ReverseOps[U] = to((out <~ f).inlet)
      def via[U](j: UniformFanInShape[U, T]): ReverseOps[U] = to((out <~ j).inlet)
      def via[U](j: UniformFanOutShape[U, T]): ReverseOps[U] = to((out <~ j).inlet)
    }
  }
}
