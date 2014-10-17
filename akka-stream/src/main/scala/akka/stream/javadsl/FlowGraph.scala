/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream.javadsl
import akka.stream.scaladsl2

import akka.stream._

// elements //

trait JunctionInPort[-T] {
  /** Convert this element to it's `scaladsl2` equivalent. */
  def asScala: scaladsl2.JunctionInPort[T]
}
trait JunctionOutPort[T] {
  /** Convert this element to it's `scaladsl2` equivalent. */
  def asScala: scaladsl2.JunctionOutPort[T]
}
abstract class Junction[T] extends JunctionInPort[T] with JunctionOutPort[T] {
  /** Convert this element to it's `scaladsl2` equivalent. */
  def asScala: scaladsl2.Junction[T]
}

/** INTERNAL API */
private object JunctionPortAdapter {
  def apply[T](delegate: scaladsl2.JunctionInPort[T]): javadsl.JunctionInPort[T] =
    new JunctionInPort[T] { override def asScala: scaladsl2.JunctionInPort[T] = delegate }

  def apply[T](delegate: scaladsl2.JunctionOutPort[T]): javadsl.JunctionOutPort[T] =
    new JunctionOutPort[T] { override def asScala: scaladsl2.JunctionOutPort[T] = delegate }
}

object Merge {
  /**
   * Create a new anonymous `Merge` vertex with the specified output type.
   * Note that a `Merge` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](): Merge[T] = new Merge(new scaladsl2.Merge[T](None))

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
  def create[T](name: String): Merge[T] = new Merge(new scaladsl2.Merge[T](Some(name)))

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
class Merge[T] private (delegate: scaladsl2.Merge[T]) extends javadsl.Junction[T] {
  override def asScala: scaladsl2.Merge[T] = delegate
}

object MergePreferred {
  /**
   * Create a new anonymous `MergePreferred` vertex with the specified output type.
   * Note that a `MergePreferred` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](): MergePreferred[T] = new MergePreferred(new scaladsl2.MergePreferred[T](None))

  /**
   * Create a new anonymous `MergePreferred` vertex with the specified output type.
   * Note that a `MergePreferred` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](clazz: Class[T]): MergePreferred[T] = new MergePreferred(new scaladsl2.MergePreferred[T](None))

  /**
   * Create a named `MergePreferred` vertex with the specified output type.
   * Note that a `MergePreferred` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def create[T](name: String): MergePreferred[T] = new MergePreferred(new scaladsl2.MergePreferred[T](Some(name)))

  /**
   * Create a named `MergePreferred` vertex with the specified output type.
   * Note that a `MergePreferred` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def create[T](clazz: Class[T], name: String): MergePreferred[T] = new MergePreferred(new scaladsl2.MergePreferred[T](Some(name)))
}

/**
 * Merge several streams, taking elements as they arrive from input streams
 * (picking from preferred when several have elements ready).
 *
 * When building the [[FlowGraph]] you must connect one or more input streams
 * and one output sink to the `Merge` vertex.
 */
class MergePreferred[T](delegate: scaladsl2.MergePreferred[T]) extends javadsl.Junction[T] {
  override def asScala: scaladsl2.MergePreferred[T] = delegate
}

object Broadcast {
  /**
   * Create a new anonymous `Broadcast` vertex with the specified input type.
   * Note that a `Broadcast` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](): Broadcast[T] = new Broadcast(new scaladsl2.Broadcast(None))

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
  def create[T](name: String): Broadcast[T] = new Broadcast(new scaladsl2.Broadcast(Some(name)))

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
class Broadcast[T](delegate: scaladsl2.Broadcast[T]) extends javadsl.Junction[T] {
  /** Convert this element to it's `scaladsl2` equivalent. */
  def asScala: scaladsl2.Broadcast[T] = delegate
}

object Balance {
  /**
   * Create a new anonymous `Balance` vertex with the specified input type.
   * Note that a `Balance` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](): Balance[T] = new Balance(new scaladsl2.Balance(None))

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
  def create[T](name: String): Balance[T] = new Balance(new scaladsl2.Balance(Some(name)))

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
class Balance[T](delegate: scaladsl2.Balance[T]) extends javadsl.Junction[T] {
  override def asScala: scaladsl2.Balance[T] = delegate
}

// TODO implement: Concat, Zip, Unzip and friends

// undefined elements //

object UndefinedSource {
  /**
   * Create a new anonymous `Undefinedsource` vertex with the specified input type.
   * Note that a `Undefinedsource` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](): UndefinedSource[T] = new UndefinedSource[T](new scaladsl2.UndefinedSource[T](None))

  /**
   * Create a new anonymous `Undefinedsource` vertex with the specified input type.
   * Note that a `Undefinedsource` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](clazz: Class[T]): UndefinedSource[T] = new UndefinedSource[T](new scaladsl2.UndefinedSource[T](None))

  /**
   * Create a named `Undefinedsource` vertex with the specified input type.
   * Note that a `Undefinedsource` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def create[T](name: String): UndefinedSource[T] = new UndefinedSource[T](new scaladsl2.UndefinedSource[T](Some(name)))

  /**
   * Create a named `Undefinedsource` vertex with the specified input type.
   * Note that a `Undefinedsource` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def create[T](clazz: Class[T], name: String): UndefinedSource[T] = new UndefinedSource[T](new scaladsl2.UndefinedSource[T](Some(name)))
}

/**
 * It is possible to define a [[akka.stream.javadsl.PartialFlowGraph]] with input pipes that are not connected
 * yet by using this placeholder instead of the real [[Source]]. Later the placeholder can
 * be replaced with [[akka.stream.javadsl.FlowGraphBuilder#attachSource]].
 */
final class UndefinedSource[+T](delegate: scaladsl2.UndefinedSource[T]) {
  def asScala: scaladsl2.UndefinedSource[T] = delegate
}

object UndefinedSink {
  /**
   * Create a new anonymous `Undefinedsink` vertex with the specified input type.
   * Note that a `Undefinedsink` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](): UndefinedSink[T] = new UndefinedSink[T](new scaladsl2.UndefinedSink[T](None))

  /**
   * Create a new anonymous `Undefinedsink` vertex with the specified input type.
   * Note that a `Undefinedsink` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def create[T](clazz: Class[T]): UndefinedSink[T] = new UndefinedSink[T](new scaladsl2.UndefinedSink[T](None))

  /**
   * Create a named `Undefinedsink` vertex with the specified input type.
   * Note that a `Undefinedsink` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def create[T](name: String): UndefinedSink[T] = new UndefinedSink[T](new scaladsl2.UndefinedSink[T](Some(name)))

  /**
   * Create a named `Undefinedsink` vertex with the specified input type.
   * Note that a `Undefinedsink` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def create[T](clazz: Class[T], name: String): UndefinedSink[T] = new UndefinedSink[T](new scaladsl2.UndefinedSink[T](Some(name)))
}

/**
 * It is possible to define a [[akka.stream.javadsl.PartialFlowGraph]] with input pipes that are not connected
 * yet by using this placeholder instead of the real [[Sink]]. Later the placeholder can
 * be replaced with [[akka.stream.javadsl.FlowGraphBuilder#attachSink]].
 */
final class UndefinedSink[-T](delegate: scaladsl2.UndefinedSink[T]) {
  def asScala: scaladsl2.UndefinedSink[T] = delegate
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
class FlowGraphBuilder(b: scaladsl2.FlowGraphBuilder) {
  import akka.stream.scaladsl2.JavaConverters._

  def this() {
    this(new scaladsl2.FlowGraphBuilder())
  }

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
   * Import all edges from another [[akka.stream.scaladsl2.PartialFlowGraph]] to this builder.
   * After importing you can [[#connect]] undefined sources and sinks in
   * two different `PartialFlowGraph` instances.
   */
  def importPartialFlowGraph(partialFlowGraph: scaladsl2.PartialFlowGraph): FlowGraphBuilder = {
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
  def run(materializer: scaladsl2.FlowMaterializer): javadsl.MaterializedMap =
    new MaterializedMapAdapter(b.build().run()(materializer))

}

object PartialFlowGraphBuilder extends FlowGraphBuilder

class PartialFlowGraph(delegate: scaladsl2.PartialFlowGraph) {
  import collection.JavaConverters._
  import akka.stream.scaladsl2.JavaConverters._

  def asScala: scaladsl2.PartialFlowGraph = delegate

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

class FlowGraph(delegate: scaladsl2.FlowGraph) extends RunnableFlow {

  def asScala: scaladsl2.FlowGraph = delegate

  // TODO IMPLEMENT

  override def run(materializer: scaladsl2.FlowMaterializer): javadsl.MaterializedMap =
    new MaterializedMapAdapter(delegate.run()(materializer))

}

