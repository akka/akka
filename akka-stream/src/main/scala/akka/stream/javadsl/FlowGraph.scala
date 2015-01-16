/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream._
import akka.stream.scaladsl

trait JunctionInPort[-T] {
  /** Convert this element to it's `scaladsl` equivalent. */
  def asScala: scaladsl.JunctionInPort[T]
}
trait JunctionOutPort[T] {
  /** Convert this element to it's `scaladsl` equivalent. */
  def asScala: scaladsl.JunctionOutPort[T]
}
abstract class Junction[T] extends JunctionInPort[T] with JunctionOutPort[T] {
  /** Convert this element to it's `scaladsl` equivalent. */
  def asScala: scaladsl.Junction[T]
}

/** INTERNAL API */
private object JunctionPortAdapter {
  def apply[T](delegate: scaladsl.JunctionInPort[T]): javadsl.JunctionInPort[T] =
    new JunctionInPort[T] { override def asScala: scaladsl.JunctionInPort[T] = delegate }

  def apply[T](delegate: scaladsl.JunctionOutPort[T]): javadsl.JunctionOutPort[T] =
    new JunctionOutPort[T] { override def asScala: scaladsl.JunctionOutPort[T] = delegate }
}

object Merge {

  /**
   * Create a new `Merge` vertex with the specified output type and attributes.
   *
   * @param attributes optional attributes for this vertex
   */
  def create[T](attributes: OperationAttributes): Merge[T] =
    new Merge(new scaladsl.Merge[T](attributes.asScala))

  /**
   * Create a new `Merge` vertex with the specified output type.
   */
  def create[T](): Merge[T] = create(OperationAttributes.none)

  /**
   * Create a new `Merge` vertex with the specified output type.
   */
  def create[T](clazz: Class[T]): Merge[T] = create()

  /**
   * Create a new `Merge` vertex with the specified output type and attributes.
   *
   * @param attributes optional attributes for this vertex
   */
  def create[T](clazz: Class[T], attributes: OperationAttributes): Merge[T] = create(attributes)

}

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
class Merge[T] private (delegate: scaladsl.Merge[T]) extends javadsl.Junction[T] {
  override def asScala: scaladsl.Merge[T] = delegate
}

object MergePreferred {
  /**
   * Create a new `MergePreferred` vertex with the specified output type and attributes.
   *
   * @param attributes optional attributes for this vertex
   */
  def create[T](attributes: OperationAttributes): MergePreferred[T] =
    new MergePreferred(new scaladsl.MergePreferred[T](attributes.asScala))

  /**
   * Create a new `MergePreferred` vertex with the specified output type.
   */
  def create[T](): MergePreferred[T] = create(OperationAttributes.none)

  /**
   * Create a new `MergePreferred` vertex with the specified output type.
   */
  def create[T](clazz: Class[T]): MergePreferred[T] = create()

  /**
   * Create a new `MergePreferred` vertex with the specified output type and attributes.
   *
   * @param attributes optional attributes for this vertex
   */
  def create[T](clazz: Class[T], attributes: OperationAttributes): MergePreferred[T] =
    create(attributes)

  class Preferred[T] private[akka] (delegate: scaladsl.MergePreferred.Preferred[T]) extends JunctionInPort[T] {
    override def asScala: scaladsl.JunctionInPort[T] = delegate
  }
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
class MergePreferred[T](delegate: scaladsl.MergePreferred[T]) extends javadsl.Junction[T] {
  override def asScala: scaladsl.MergePreferred[T] = delegate

  val preferred = new MergePreferred.Preferred[T](delegate.preferred)
}

object Broadcast {
  /**
   * Create a new `Broadcast` vertex with the specified input type and attributes.
   *
   * @param attributes optional attributes for this vertex
   */
  def create[T](attributes: OperationAttributes): Broadcast[T] =
    new Broadcast(new scaladsl.Broadcast(attributes.asScala))

  /**
   * Create a new `Broadcast` vertex with the specified input type.
   */
  def create[T](): Broadcast[T] = create(OperationAttributes.none)

  /**
   * Create a new `Broadcast` vertex with the specified input type.
   */
  def create[T](clazz: Class[T]): Broadcast[T] = create()

  /**
   * Create a new `Broadcast` vertex with the specified input type and attributes.
   *
   * @param attributes optional attributes for this vertex
   */
  def create[T](clazz: Class[T], attributes: OperationAttributes): Broadcast[T] =
    create(attributes)
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
class Broadcast[T](delegate: scaladsl.Broadcast[T]) extends javadsl.Junction[T] {
  override def asScala: scaladsl.Broadcast[T] = delegate
}

object Balance {
  /**
   * Create a new `Balance` vertex with the specified input type and attributes.
   *
   * @param waitForAllDownstreams if `true` it will not start emitting
   *   elements to downstream outputs until all of them have requested at least one element
   * @param attributes optional attributes for this vertex
   */
  def create[T](waitForAllDownstreams: Boolean, attributes: OperationAttributes): Balance[T] =
    new Balance(new scaladsl.Balance(waitForAllDownstreams, attributes.asScala))

  /**
   * Create a new `Balance` vertex with the specified input type.
   */
  def create[T](): Balance[T] = create(false, OperationAttributes.none)

  /**
   * Create a new `Balance` vertex with the specified input type.
   */
  def create[T](attributes: OperationAttributes): Balance[T] = create(false, attributes)

  /**
   * Create a new `Balance` vertex with the specified input type.
   */
  def create[T](clazz: Class[T]): Balance[T] = create()

  /**
   * Create a new `Balance` vertex with the specified input type and attributes.
   *
   * @param attributes optional attributes for this vertex
   */
  def create[T](clazz: Class[T], attributes: OperationAttributes): Balance[T] =
    create(false, attributes)
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
class Balance[T](delegate: scaladsl.Balance[T]) extends javadsl.Junction[T] {
  override def asScala: scaladsl.Balance[T] = delegate

  /**
   * If you use `withWaitForAllDownstreams(true)` the returned `Balance` will not start emitting
   * elements to downstream outputs until all of them have requested at least one element.
   */
  def withWaitForAllDowstreams(enabled: Boolean): Balance[T] =
    new Balance(new scaladsl.Balance(delegate.waitForAllDownstreams, delegate.attributes))
}

object Zip {
  import akka.stream.javadsl.japi.Function2
  import akka.japi.Pair
  /**
   * Create a new anonymous `Zip2With` vertex with the specified input types and zipping-function
   * which creates `akka.japi.Pair`s.
   * Note that a `ZipWith` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   * @param attributes optional attributes for this vertex
   */
  def create[A, B](attributes: OperationAttributes): Zip2With[A, B, A Pair B] =
    ZipWith.create(_toPair.asInstanceOf[Function2[A, B, A Pair B]], attributes)

  /**
   * Create a new `ZipWith` vertex with the specified input types and zipping-function
   * which creates `akka.japi.Pair`s.
   */
  def create[A, B]: Zip2With[A, B, A Pair B] = create(OperationAttributes.none)

  private[this] final val _toPair: Function2[Any, Any, Any Pair Any] =
    new Function2[Any, Any, Any Pair Any] { override def apply(a: Any, b: Any): Any Pair Any = new Pair(a, b) }
}

object Unzip {

  /**
   * Creates a new `Unzip` vertex with the specified output types and attributes.
   *
   * @param attributes attributes for this vertex
   */
  def create[A, B](attributes: OperationAttributes): Unzip[A, B] =
    new Unzip[A, B](new scaladsl.Unzip[A, B](attributes.asScala))

  /**
   * Creates a new `Unzip` vertex with the specified output types and attributes.
   */
  def create[A, B](): Unzip[A, B] = create(OperationAttributes.none)

  /**
   * Creates a new `Unzip` vertex with the specified output types.
   */
  def create[A, B](left: Class[A], right: Class[B]): Unzip[A, B] = create[A, B]()

  /**
   * Creates a new `Unzip` vertex with the specified output types and attributes.
   *
   * @param attributes optional attributes for this vertex
   */
  def create[A, B](left: Class[A], right: Class[B], attributes: OperationAttributes): Unzip[A, B] =
    create[A, B](attributes)

  class In[A, B](private val unzip: Unzip[A, B]) extends JunctionInPort[akka.japi.Pair[A, B]] {
    // this cast is safe thanks to using `ZipAs` in the Ast element, Zip will emit the expected type (Pair)
    override def asScala: scaladsl.JunctionInPort[akka.japi.Pair[A, B]] =
      unzip.asScala.in.asInstanceOf[scaladsl.JunctionInPort[akka.japi.Pair[A, B]]]
  }
  class Left[A, B](private val unzip: Unzip[A, B]) extends JunctionOutPort[A] {
    override def asScala: scaladsl.JunctionOutPort[A] =
      unzip.asScala.left
  }
  class Right[A, B](private val unzip: Unzip[A, B]) extends JunctionOutPort[B] {
    override def asScala: scaladsl.JunctionOutPort[B] =
      unzip.asScala.right
  }
}

/**
 * Note that a junction instance describes exactly one place (vertex) in the `FlowGraph`
 * that multiple flows can be attached to; if you want to have multiple independent
 * junctions within the same `FlowGraph` then you will have to create multiple such
 * instances.
 */
final class Unzip[A, B] private (delegate: scaladsl.Unzip[A, B]) {

  /** Convert this element to it's `scaladsl` equivalent. */
  def asScala = delegate

  val in = new Unzip.In(this)
  val left = new Unzip.Left(this)
  val right = new Unzip.Right(this)
}

object Concat {
  /**
   * Create a new anonymous `Concat` vertex with the specified input types.
   * Note that a `Concat` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](): Concat[T] =
    create(OperationAttributes.none)

  /**
   * Create a new anonymous `Concat` vertex with the specified input types.
   * Note that a `Concat` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](attributes: OperationAttributes): Concat[T] =
    new Concat(scaladsl.Concat[T](attributes.asScala))

  /**
   * Create a new anonymous `Concat` vertex with the specified input types.
   * Note that a `Concat` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](clazz: Class[T], attributes: OperationAttributes): Concat[T] = create(attributes)

  class First[T] private[akka] (delegate: scaladsl.Concat.First[T]) extends JunctionInPort[T] {
    override def asScala: scaladsl.JunctionInPort[T] = delegate
  }
  class Second[T] private[akka] (delegate: scaladsl.Concat.Second[T]) extends JunctionInPort[T] {
    override def asScala: scaladsl.JunctionInPort[T] = delegate
  }
  class Out[T] private[akka] (delegate: scaladsl.Concat.Out[T]) extends JunctionOutPort[T] {
    override def asScala: scaladsl.JunctionOutPort[T] = delegate
  }

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
class Concat[T] private (delegate: scaladsl.Concat[T]) {

  /** Convert this element to it's `scaladsl` equivalent. */
  def asScala = delegate

  val first = new Concat.First[T](delegate.first)
  val second = new Concat.Second[T](delegate.second)
  val out = new Concat.Out[T](delegate.out)
}

// undefined elements //

object UndefinedSource {
  /**
   * Create a new `Undefinedsource` vertex with the specified output type.
   */
  def create[T](): UndefinedSource[T] =
    new UndefinedSource[T](new scaladsl.UndefinedSource[T](scaladsl.OperationAttributes.none))

  /**
   * Create a new `Undefinedsource` vertex with the specified output type.
   */
  def create[T](clazz: Class[T]): UndefinedSource[T] = create()

}

/**
 * It is possible to define a [[akka.stream.javadsl.PartialFlowGraph]] with input pipes that are not connected
 * yet by using this placeholder instead of the real [[Source]]. Later the placeholder can
 * be replaced with [[akka.stream.javadsl.FlowGraphBuilder#attachSource]].
 */
final class UndefinedSource[+T](delegate: scaladsl.UndefinedSource[T]) {
  def asScala: scaladsl.UndefinedSource[T] = delegate
}

object UndefinedSink {
  /**
   * Create a new `Undefinedsink` vertex with the specified input type.
   */
  def create[T](): UndefinedSink[T] =
    new UndefinedSink[T](new scaladsl.UndefinedSink[T](OperationAttributes.none.asScala))

  /**
   * Create a new `Undefinedsource` vertex with the specified output type.
   */
  def create[T](clazz: Class[T]): UndefinedSink[T] = create()
}

/**
 * It is possible to define a [[akka.stream.javadsl.PartialFlowGraph]] with input pipes that are not connected
 * yet by using this placeholder instead of the real [[Sink]]. Later the placeholder can
 * be replaced with [[akka.stream.javadsl.FlowGraphBuilder#attachSink]].
 */
final class UndefinedSink[-T](delegate: scaladsl.UndefinedSink[T]) {
  def asScala: scaladsl.UndefinedSink[T] = delegate
}

// flow graph //

object FlowGraph {

  /**
   * Start building a [[FlowGraph]] or [[PartialFlowGraph]].
   *
   * The [[FlowGraphBuilder]] is mutable and not thread-safe,
   * thus you should construct your Graph and then share the constructed immutable [[FlowGraph]].
   */
  def builder(): FlowGraphBuilder = new FlowGraphBuilder()

  /**
   * Continue building a [[FlowGraph]] from an existing `PartialFlowGraph`.
   * For example you can attach undefined sources and sinks with
   * [[FlowGraphBuilder#attachSource]] and [[FlowGraphBuilder#attachSink]]
   */
  def builder(partialFlowGraph: PartialFlowGraph): FlowGraphBuilder =
    new FlowGraphBuilder(partialFlowGraph)

}

/**
 * Java API
 * Builder of [[FlowGraph]] and [[PartialFlowGraph]].
 */
class FlowGraphBuilder(b: scaladsl.FlowGraphBuilder) {

  /**
   * Continue building a [[FlowGraph]] from an existing `PartialFlowGraph`.
   * For example you can attach undefined sources and sinks with
   * [[#attachSource]] and [[#attachSink]]
   */
  def this(partialFlowGraph: PartialFlowGraph) {
    this(new scaladsl.FlowGraphBuilder(partialFlowGraph.asScala))
  }

  def this() {
    this(new scaladsl.FlowGraphBuilder())
  }

  /** Converts this Java DSL element to it's Scala DSL counterpart. */
  def asScala: scaladsl.FlowGraphBuilder = b

  def addEdge[In, Out](source: javadsl.UndefinedSource[In], flow: javadsl.Flow[In, Out], junctionIn: javadsl.JunctionInPort[Out]): FlowGraphBuilder = {
    b.addEdge(source.asScala, flow.asScala, junctionIn.asScala)
    this
  }

  def addEdge[T](source: javadsl.UndefinedSource[T], junctionIn: javadsl.JunctionInPort[T]) =
    addEdge[T, T](source, javadsl.Flow.empty[T], junctionIn);

  def addEdge[In, Out](junctionOut: javadsl.JunctionOutPort[In], flow: javadsl.Flow[In, Out], sink: javadsl.UndefinedSink[Out]): FlowGraphBuilder = {
    b.addEdge(junctionOut.asScala, flow.asScala, sink.asScala)
    this
  }

  def addEdge[T](junctionOut: javadsl.JunctionOutPort[T], sink: javadsl.UndefinedSink[T]): FlowGraphBuilder =
    addEdge[T, T](junctionOut, javadsl.Flow.empty[T], sink);

  def addEdge[In, Out](junctionOut: javadsl.JunctionOutPort[In], flow: javadsl.Flow[In, Out], junctionIn: javadsl.JunctionInPort[Out]): FlowGraphBuilder = {
    b.addEdge(junctionOut.asScala, flow.asScala, junctionIn.asScala)
    this
  }

  def addEdge[T](junctionOut: javadsl.JunctionOutPort[T], junctionIn: javadsl.JunctionInPort[T]): FlowGraphBuilder =
    addEdge[T, T](junctionOut, javadsl.Flow.empty[T], junctionIn);

  def addEdge[In, Out](source: javadsl.Source[In], flow: javadsl.Flow[In, Out], junctionIn: javadsl.JunctionInPort[Out]): FlowGraphBuilder = {
    b.addEdge(source.asScala, flow.asScala, junctionIn.asScala)
    this
  }

  def addEdge[T](source: javadsl.Source[T], junctionIn: javadsl.JunctionInPort[T]): FlowGraphBuilder =
    addEdge[T, T](source, javadsl.Flow.empty[T], junctionIn);

  def addEdge[In, Out](junctionOut: javadsl.JunctionOutPort[In], flow: javadsl.Flow[In, Out], sink: Sink[Out]): FlowGraphBuilder = {
    b.addEdge(junctionOut.asScala, flow.asScala, sink.asScala)
    this
  }

  def addEdge[T](junctionOut: javadsl.JunctionOutPort[T], sink: Sink[T]): FlowGraphBuilder =
    addEdge[T, T](junctionOut, javadsl.Flow.empty[T], sink);

  def addEdge[In, Out](source: javadsl.Source[In], flow: javadsl.Flow[In, Out], sink: Sink[Out]): FlowGraphBuilder = {
    b.addEdge(source.asScala, flow.asScala, sink.asScala)
    this
  }

  def addEdge[T](source: javadsl.Source[T], sink: Sink[T]): FlowGraphBuilder =
    addEdge[T, T](source, javadsl.Flow.empty[T], sink);

  def addEdge[In, Out](source: javadsl.UndefinedSource[In], flow: javadsl.Flow[In, Out], sink: javadsl.UndefinedSink[Out]): FlowGraphBuilder = {
    b.addEdge(source.asScala, flow.asScala, sink.asScala)
    this
  }

  def addEdge[T](source: javadsl.UndefinedSource[T], sink: javadsl.UndefinedSink[T]): FlowGraphBuilder =
    addEdge[T, T](source, javadsl.Flow.empty[T], sink);

  def addEdge[In, Out](source: javadsl.UndefinedSource[In], flow: javadsl.Flow[In, Out], sink: javadsl.Sink[Out]): FlowGraphBuilder = {
    b.addEdge(source.asScala, flow.asScala, sink.asScala)
    this
  }

  def addEdge[T](source: javadsl.UndefinedSource[T], sink: javadsl.Sink[T]): FlowGraphBuilder =
    addEdge[T, T](source, javadsl.Flow.empty[T], sink);

  def addEdge[In, Out](source: javadsl.Source[In], flow: javadsl.Flow[In, Out], sink: javadsl.UndefinedSink[Out]): FlowGraphBuilder = {
    b.addEdge(source.asScala, flow.asScala, sink.asScala)
    this
  }

  def addEdge[T](source: javadsl.Source[T], sink: javadsl.UndefinedSink[T]): FlowGraphBuilder =
    addEdge[T, T](source, javadsl.Flow.empty[T], sink);

  def attachSink[Out](token: javadsl.UndefinedSink[Out], sink: Sink[Out]): FlowGraphBuilder = {
    b.attachSink(token.asScala, sink.asScala)
    this
  }

  def attachSource[In](token: javadsl.UndefinedSource[In], source: javadsl.Source[In]): FlowGraphBuilder = {
    b.attachSource(token.asScala, source.asScala)
    this
  }

  def connect[A, B](out: javadsl.UndefinedSink[A], flow: javadsl.Flow[A, B], in: javadsl.UndefinedSource[B]): FlowGraphBuilder = {
    b.connect(out.asScala, flow.asScala, in.asScala)
    this
  }

  def importFlowGraph(flowGraph: javadsl.FlowGraph): FlowGraphBuilder = {
    b.importFlowGraph(flowGraph.asScala)
    this
  }

  /**
   * Import all edges from another [[akka.stream.scaladsl.PartialFlowGraph]] to this builder.
   * After importing you can [[#connect]] undefined sources and sinks in
   * two different `PartialFlowGraph` instances.
   */
  def importPartialFlowGraph(partialFlowGraph: javadsl.PartialFlowGraph): FlowGraphBuilder = {
    b.importPartialFlowGraph(partialFlowGraph.asScala)
    this
  }

  /**
   * Flow graphs with cycles are in general dangerous as it can result in deadlocks.
   * Therefore, cycles in the graph are by default disallowed. `IllegalArgumentException` will
   * be throw when cycles are detected. Sometimes cycles are needed and then
   * you can allow them with this method.
   */
  def allowCycles(): FlowGraphBuilder = {
    b.allowCycles()
    this
  }

  /** Build the [[FlowGraph]] but do not materialize it. */
  def build(): javadsl.FlowGraph =
    new javadsl.FlowGraph(b.build())

  /** Build the [[PartialFlowGraph]] but do not materialize it. */
  def buildPartial(): javadsl.PartialFlowGraph =
    new PartialFlowGraph(b.partialBuild())

  /** Build the [[FlowGraph]] and materialize it. */
  def run(materializer: FlowMaterializer): javadsl.MaterializedMap =
    new MaterializedMap(b.build().run()(materializer))

}

class PartialFlowGraph(delegate: scaladsl.PartialFlowGraph) {
  import akka.stream.scaladsl.JavaConverters._

  import collection.JavaConverters._

  def asScala: scaladsl.PartialFlowGraph = delegate

  def undefinedSources(): java.util.Set[UndefinedSource[Any]] =
    delegate.undefinedSources.map(s ⇒ s.asJava).asJava

  def undefinedSinks(): java.util.Set[UndefinedSink[_]] =
    delegate.undefinedSinks.map(s ⇒ s.asJava).asJava

  /**
   * Creates a [[Source]] from this `PartialFlowGraph`. There needs to be only one [[UndefinedSink]] and
   * no [[UndefinedSource]] in the graph, and you need to provide it as a parameter.
   */
  def toSource[O](out: javadsl.UndefinedSink[O]): javadsl.Source[O] =
    delegate.toSource(out.asScala).asJava

  /**
   * Creates a [[Flow]] from this `PartialFlowGraph`. There needs to be only one [[UndefinedSource]] and
   * one [[UndefinedSink]] in the graph, and you need to provide them as parameters.
   */
  def toFlow[I, O](in: javadsl.UndefinedSource[I], out: javadsl.UndefinedSink[O]): Flow[I, O] =
    delegate.toFlow(in.asScala, out.asScala).asJava

  /**
   * Creates a [[Sink]] from this `PartialFlowGraph`. There needs to be only one [[UndefinedSource]] and
   * no [[UndefinedSink]] in the graph, and you need to provide it as a parameter.
   */
  def toSink[I](in: UndefinedSource[I]): javadsl.Sink[I] =
    delegate.toSink(in.asScala).asJava

}

class FlowGraph(delegate: scaladsl.FlowGraph) extends RunnableFlow {

  /** Convert this element to it's `scaladsl` equivalent. */
  def asScala: scaladsl.FlowGraph = delegate

  override def run(materializer: FlowMaterializer): javadsl.MaterializedMap =
    new MaterializedMap(delegate.run()(materializer))

  def runWith[M](key: KeyedMaterializable[M], materializer: FlowMaterializer): M =
    delegate.runWith(key.asScala)(materializer)
}

