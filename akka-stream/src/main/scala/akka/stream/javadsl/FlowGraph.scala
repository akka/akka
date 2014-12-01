/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream._
import akka.stream.scaladsl
import akka.stream.impl.Ast

import akka.stream._

// elements //

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
   * Create a new anonymous `Merge` vertex with the specified output type.
   * Note that a `Merge` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](): Merge[T] = create(name = null)

  /**
   * Create a new anonymous `Merge` vertex with the specified output type.
   * Note that a `Merge` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](clazz: Class[T]): Merge[T] = create[T]()

  /**
   * Create a named `Merge` vertex with the specified output type.
   * Note that a `Merge` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def create[T](name: String): Merge[T] = new Merge(new scaladsl.Merge[T](OperationAttributes.name(name).asScala))

  /**
   * Create a named `Merge` vertex with the specified output type.
   * Note that a `Merge` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def create[T](clazz: Class[T], name: String): Merge[T] = create[T](name)
}

/**
 * Merge several streams, taking elements as they arrive from input streams
 * (picking randomly when several have elements ready).
 *
 * When building the [[FlowGraph]] you must connect one or more input sources
 * and one output sink to the `Merge` vertex.
 */
class Merge[T] private (delegate: scaladsl.Merge[T]) extends javadsl.Junction[T] {
  override def asScala: scaladsl.Merge[T] = delegate
}

object MergePreferred {
  /**
   * Create a new anonymous `MergePreferred` vertex with the specified output type.
   * Note that a `MergePreferred` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](): MergePreferred[T] = create(name = null)

  /**
   * Create a new anonymous `MergePreferred` vertex with the specified output type.
   * Note that a `MergePreferred` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](clazz: Class[T]): MergePreferred[T] = create[T]()

  /**
   * Create a named `MergePreferred` vertex with the specified output type.
   * Note that a `MergePreferred` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def create[T](name: String): MergePreferred[T] = new MergePreferred(new scaladsl.MergePreferred[T](OperationAttributes.name(name).asScala))

  /**
   * Create a named `MergePreferred` vertex with the specified output type.
   * Note that a `MergePreferred` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def create[T](clazz: Class[T], name: String): MergePreferred[T] = create[T](name)
}

/**
 * Merge several streams, taking elements as they arrive from input streams
 * (picking from preferred when several have elements ready).
 *
 * When building the [[FlowGraph]] you must connect one or more input streams
 * and one output sink to the `Merge` vertex.
 */
class MergePreferred[T](delegate: scaladsl.MergePreferred[T]) extends javadsl.Junction[T] {
  override def asScala: scaladsl.MergePreferred[T] = delegate
}

object Broadcast {
  /**
   * Create a new anonymous `Broadcast` vertex with the specified input type.
   * Note that a `Broadcast` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](): Broadcast[T] = create(name = null)

  /**
   * Create a new anonymous `Broadcast` vertex with the specified input type.
   * Note that a `Broadcast` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](clazz: Class[T]): Broadcast[T] = create[T]()

  /**
   * Create a named `Broadcast` vertex with the specified input type.
   * Note that a `Broadcast` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def create[T](name: String): Broadcast[T] = new Broadcast(new scaladsl.Broadcast(OperationAttributes.name(name).asScala))

  /**
   * Create a named `Broadcast` vertex with the specified input type.
   * Note that a `Broadcast` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def create[T](clazz: Class[T], name: String): Broadcast[T] = create[T](name)
}

/**
 * Fan-out the stream to several streams. Each element is produced to
 * the other streams. It will not shutdown until the subscriptions for at least
 * two downstream subscribers have been established.
 */
class Broadcast[T](delegate: scaladsl.Broadcast[T]) extends javadsl.Junction[T] {
  override def asScala: scaladsl.Broadcast[T] = delegate
}

object Balance {
  /**
   * Create a new anonymous `Balance` vertex with the specified input type.
   * Note that a `Balance` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](): Balance[T] = create(name = null)

  /**
   * Create a new anonymous `Balance` vertex with the specified input type.
   * Note that a `Balance` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](clazz: Class[T]): Balance[T] = create[T]()

  /**
   * Create a named `Balance` vertex with the specified input type.
   * Note that a `Balance` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def create[T](name: String): Balance[T] =
    new Balance(new scaladsl.Balance(waitForAllDownstreams = false, OperationAttributes.name(name).asScala))

  /**
   * Create a named `Balance` vertex with the specified input type.
   * Note that a `Balance` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def create[T](clazz: Class[T], name: String): Balance[T] = create[T](name)
}

/**
 * Fan-out the stream to several streams. Each element is produced to
 * one of the other streams. It will not shutdown until the subscriptions for at least
 * two downstream subscribers have been established.
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

  /**
   * Create a new anonymous `Zip` vertex with the specified input types.
   * Note that a `Zip` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.*
   */
  def create[A, B](): Zip[A, B] = create(name = null)

  /**
   * Create a new anonymous `Zip` vertex with the specified input types.
   * Note that a `Zip` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[A, B](left: Class[A], right: Class[B]): Zip[A, B] = create[A, B]()

  /**
   * Create a named `Zip` vertex with the specified input types.
   * Note that a `Zip` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.*
   */
  def create[A, B](name: String): Zip[A, B] =
    new Zip(new scaladsl.Zip[A, B](OperationAttributes.name(name).asScala) {
      override private[akka] def astNode: Ast.FanInAstNode = Ast.Zip(impl.Zip.AsJavaPair, attributes)
    })

  /**
   * Create a named `Zip` vertex with the specified input types.
   * Note that a `Zip` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.*
   */
  def create[A, B](name: String, left: Class[A], right: Class[A]): Zip[A, B] =
    create[A, B](name)

  class Left[A, B](private val zip: Zip[A, B]) extends JunctionInPort[A] {
    override def asScala: scaladsl.JunctionInPort[A] = zip.asScala.left
  }
  class Right[A, B](private val zip: Zip[A, B]) extends JunctionInPort[B] {
    override def asScala: scaladsl.JunctionInPort[B] = zip.asScala.right
  }
  class Out[A, B](private val zip: Zip[A, B]) extends JunctionOutPort[akka.japi.Pair[A, B]] {
    // this cast is safe thanks to using `ZipAs` in the Ast element, Zip will emit the expected type (Pair)
    override def asScala: scaladsl.JunctionOutPort[akka.japi.Pair[A, B]] =
      zip.asScala.out.asInstanceOf[scaladsl.JunctionOutPort[akka.japi.Pair[A, B]]]
  }
}

/**
 * Takes two streams and outputs an output stream formed from the two input streams
 * by combining corresponding elements in pairs. If one of the two streams is
 * longer than the other, its remaining elements are ignored.
 */
final class Zip[A, B] private (delegate: scaladsl.Zip[A, B]) {

  /** Convert this element to it's `scaladsl` equivalent. */
  def asScala = delegate

  val left = new Zip.Left(this)
  val right = new Zip.Right(this)
  val out = new Zip.Out(this)
}

object Unzip {
  def create[A, B](): Unzip[A, B] = create(name = null)

  def create[A, B](name: String): Unzip[A, B] =
    new Unzip[A, B](new scaladsl.Unzip[A, B](OperationAttributes.name(name).asScala))

  def create[A, B](left: Class[A], right: Class[B]): Unzip[A, B] =
    create[A, B]()

  def create[A, B](name: String, left: Class[A], right: Class[B]): Unzip[A, B] =
    create[A, B](name)

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
  def create[T](): Concat[T] = new Concat(scaladsl.Concat[T])

  /**
   * Create a new anonymous `Concat` vertex with the specified input types.
   * Note that a `Concat` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](clazz: Class[T]): Concat[T] = create()

  /**
   * Create a named `Concat` vertex with the specified input types.
   * Note that a `Concat` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.*
   */
  def create[T](name: String): Concat[T] = new Concat(scaladsl.Concat[T](name))

  /**
   * Create a named `Concat` vertex with the specified input types.
   * Note that a `Concat` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.*
   */
  def create[T](name: String, clazz: Class[T]): Concat[T] = create(name)

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
   * Create a new anonymous `Undefinedsource` vertex with the specified input type.
   * Note that a `Undefinedsource` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](): UndefinedSource[T] = new UndefinedSource[T](new scaladsl.UndefinedSource[T](scaladsl.OperationAttributes.none))

  /**
   * Create a new anonymous `Undefinedsource` vertex with the specified input type.
   * Note that a `Undefinedsource` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](clazz: Class[T]): UndefinedSource[T] = create[T]()

  /**
   * Create a named `Undefinedsource` vertex with the specified input type.
   * Note that a `Undefinedsource` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def create[T](name: String): UndefinedSource[T] = new UndefinedSource[T](new scaladsl.UndefinedSource[T](OperationAttributes.name(name).asScala))

  /**
   * Create a named `Undefinedsource` vertex with the specified input type.
   * Note that a `Undefinedsource` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def create[T](clazz: Class[T], name: String): UndefinedSource[T] = create[T](name)
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
   * Create a new anonymous `Undefinedsink` vertex with the specified input type.
   * Note that a `Undefinedsink` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](): UndefinedSink[T] = create(name = null)

  /**
   * Create a new anonymous `Undefinedsink` vertex with the specified input type.
   * Note that a `Undefinedsink` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](clazz: Class[T]): UndefinedSink[T] = create[T]()

  /**
   * Create a named `Undefinedsink` vertex with the specified input type.
   * Note that a `Undefinedsink` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def create[T](name: String): UndefinedSink[T] = new UndefinedSink[T](new scaladsl.UndefinedSink[T](OperationAttributes.name(name).asScala))

  /**
   * Create a named `Undefinedsink` vertex with the specified input type.
   * Note that a `Undefinedsink` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def create[T](clazz: Class[T], name: String): UndefinedSink[T] = create[T](name)
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
   * Start building a [[FlowGraph]].
   *
   * The [[FlowGraphBuilder]] is mutable and not thread-safe,
   * thus you should construct your Graph and then share the constructed immutable [[FlowGraph]].
   */
  def builder(): FlowGraphBuilder =
    new FlowGraphBuilder()

}

/**
 * Java API
 * Builder of [[FlowGraph]] and [[PartialFlowGraph]].
 */
class FlowGraphBuilder(b: scaladsl.FlowGraphBuilder) {
  import akka.stream.scaladsl.JavaConverters._

  def this() {
    this(new scaladsl.FlowGraphBuilder())
  }

  /** Converts this Java DSL element to it's Scala DSL counterpart. */
  def asScala: scaladsl.FlowGraphBuilder = b

  def addEdge[In, Out](source: javadsl.UndefinedSource[In], flow: javadsl.Flow[In, Out], junctionIn: javadsl.JunctionInPort[Out]) = {
    b.addEdge(source.asScala, flow.asScala, junctionIn.asScala)
    this
  }

  def addEdge[In, Out](junctionOut: javadsl.JunctionOutPort[In], flow: javadsl.Flow[In, Out], sink: javadsl.UndefinedSink[Out]): FlowGraphBuilder = {
    b.addEdge(junctionOut.asScala, flow.asScala, sink.asScala)
    this
  }

  def addEdge[In, Out](junctionOut: javadsl.JunctionOutPort[In], flow: javadsl.Flow[In, Out], junctionIn: javadsl.JunctionInPort[Out]): FlowGraphBuilder = {
    b.addEdge(junctionOut.asScala, flow.asScala, junctionIn.asScala)
    this
  }

  def addEdge[In, Out](source: javadsl.Source[In], flow: javadsl.Flow[In, Out], junctionIn: javadsl.JunctionInPort[Out]): FlowGraphBuilder = {
    b.addEdge(source.asScala, flow.asScala, junctionIn.asScala)
    this
  }

  def addEdge[In](source: javadsl.Source[In], junctionIn: javadsl.JunctionInPort[In]): FlowGraphBuilder = {
    b.addEdge(source.asScala, junctionIn.asScala)
    this
  }

  def addEdge[In, Out](junctionOut: javadsl.JunctionOutPort[In], sink: Sink[In]): FlowGraphBuilder = {
    b.addEdge(junctionOut.asScala, sink.asScala)
    this
  }

  def addEdge[In, Out](junctionOut: javadsl.JunctionOutPort[In], flow: javadsl.Flow[In, Out], sink: Sink[Out]): FlowGraphBuilder = {
    b.addEdge(junctionOut.asScala, flow.asScala, sink.asScala)
    this
  }

  def addEdge[In, Out](source: javadsl.Source[In], flow: javadsl.Flow[In, Out], sink: Sink[Out]): FlowGraphBuilder = {
    b.addEdge(source.asScala, flow.asScala, sink.asScala)
    this
  }

  def addEdge[In, Out](source: javadsl.UndefinedSource[In], flow: javadsl.Flow[In, Out], sink: javadsl.UndefinedSink[Out]): FlowGraphBuilder = {
    b.addEdge(source.asScala, flow.asScala, sink.asScala)
    this
  }

  def addEdge[In, Out](source: javadsl.UndefinedSource[In], flow: javadsl.Flow[In, Out], sink: javadsl.Sink[Out]): FlowGraphBuilder = {
    b.addEdge(source.asScala, flow.asScala, sink.asScala)
    this
  }

  def addEdge[In, Out](source: javadsl.Source[In], flow: javadsl.Flow[In, Out], sink: javadsl.UndefinedSink[Out]): FlowGraphBuilder = {
    b.addEdge(source.asScala, flow.asScala, sink.asScala)
    this
  }

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
  def importPartialFlowGraph(partialFlowGraph: scaladsl.PartialFlowGraph): FlowGraphBuilder = {
    b.importPartialFlowGraph(partialFlowGraph)
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

object PartialFlowGraphBuilder extends FlowGraphBuilder

class PartialFlowGraph(delegate: scaladsl.PartialFlowGraph) {
  import collection.JavaConverters._
  import akka.stream.scaladsl.JavaConverters._

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

}

