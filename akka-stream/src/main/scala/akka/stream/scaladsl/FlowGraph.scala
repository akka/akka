/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.impl.Ast.FanInAstNode
import akka.stream.impl.Ast
import java.util

import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber

import scala.language.existentials
import scalax.collection.edge.LkBase
import scalax.collection.edge.LkDiEdge
import scalax.collection.immutable.{ Graph ⇒ ImmutableGraph }
import org.reactivestreams.Subscriber
import org.reactivestreams.Publisher
import akka.stream.FlowMaterializer
import scalax.collection.mutable.Graph

/**
 * Fan-in and fan-out vertices in the [[FlowGraph]] implements
 * this marker interface. Edges may end at a `JunctionInPort`.
 */
trait JunctionInPort[-T] {
  private[akka] def port: Int = FlowGraphInternal.UnlabeledPort
  private[akka] def vertex: FlowGraphInternal.Vertex
  type NextT
  private[akka] def next: JunctionOutPort[NextT]
}

/**
 * Fan-in and fan-out vertices in the [[FlowGraph]] implements
 * this marker interface. Edges may start at a `JunctionOutPort`.
 */
trait JunctionOutPort[T] {
  private[akka] def port: Int = FlowGraphInternal.UnlabeledPort
  private[akka] def vertex: FlowGraphInternal.Vertex
}

/**
 * INTERNAL API
 */
private[akka] object NoNext extends JunctionOutPort[Nothing] {
  override private[akka] def vertex: FlowGraphInternal.Vertex =
    throw new UnsupportedOperationException
}

/**
 * INTERNAL API
 *
 * Fan-in and fan-out vertices in the [[FlowGraph]] implements
 * this marker interface.
 */
private[akka] sealed trait Junction[T] extends JunctionInPort[T] with JunctionOutPort[T] {
  override private[akka] def port: Int = FlowGraphInternal.UnlabeledPort
  override private[akka] def vertex: FlowGraphInternal.Vertex
  override type NextT = T
  override private[akka] def next = this
}

object Merge {
  /**
   * Create a new anonymous `Merge` vertex with the specified output type.
   * Note that a `Merge` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def apply[T]: Merge[T] = new Merge[T](None)
  /**
   * Create a named `Merge` vertex with the specified output type.
   * Note that a `Merge` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def apply[T](name: String): Merge[T] = new Merge[T](Some(name))
}

/**
 * Merge several streams, taking elements as they arrive from input streams
 * (picking randomly when several have elements ready).
 *
 * When building the [[FlowGraph]] you must connect one or more input sources
 * and one output sink to the `Merge` vertex.
 */
final class Merge[T](override val name: Option[String]) extends FlowGraphInternal.InternalVertex with Junction[T] {
  override private[akka] val vertex = this
  override val minimumInputCount: Int = 2
  override val maximumInputCount: Int = Int.MaxValue
  override val minimumOutputCount: Int = 1
  override val maximumOutputCount: Int = 1

  override private[akka] def astNode = Ast.Merge

  final override private[scaladsl] def newInstance() = new Merge[T](None)
}

object MergePreferred {
  /**
   * Port number to use for a preferred input.
   */
  val PreferredPort = Int.MinValue

  /**
   * Create a new anonymous `MergePreferred` vertex with the specified output type.
   * Note that a `MergePreferred` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def apply[T]: MergePreferred[T] = new MergePreferred[T](None)
  /**
   * Create a named `MergePreferred` vertex with the specified output type.
   * Note that a `MergePreferred` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def apply[T](name: String): MergePreferred[T] = new MergePreferred[T](Some(name))

  class Preferred[A] private[akka] (private[akka] val vertex: MergePreferred[A]) extends JunctionInPort[A] {
    override private[akka] def port = PreferredPort
    type NextT = A
    override private[akka] def next = vertex
  }
}
/**
 * Merge several streams, taking elements as they arrive from input streams
 * (picking from preferred when several have elements ready).
 *
 * When building the [[FlowGraph]] you must connect one or more input sources
 * and one output sink to the `Merge` vertex.
 */
final class MergePreferred[T](override val name: Option[String]) extends FlowGraphInternal.InternalVertex with Junction[T] {

  val preferred = new MergePreferred.Preferred(this)

  override private[akka] val vertex = this
  override val minimumInputCount: Int = 2
  override val maximumInputCount: Int = Int.MaxValue
  override val minimumOutputCount: Int = 1
  override val maximumOutputCount: Int = 1

  override private[akka] def astNode = Ast.MergePreferred

  final override private[scaladsl] def newInstance() = new MergePreferred[T](None)
}

object Broadcast {
  /**
   * Create a new anonymous `Broadcast` vertex with the specified input type.
   * Note that a `Broadcast` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def apply[T]: Broadcast[T] = new Broadcast[T](None)
  /**
   * Create a named `Broadcast` vertex with the specified input type.
   * Note that a `Broadcast` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def apply[T](name: String): Broadcast[T] = new Broadcast[T](Some(name))
}

/**
 * Fan-out the stream to several streams. Each element is produced to
 * the other streams. It will not shutdown until the subscriptions for at least
 * two downstream subscribers have been established.
 */
final class Broadcast[T](override val name: Option[String]) extends FlowGraphInternal.InternalVertex with Junction[T] {
  override private[akka] def vertex = this
  override def minimumInputCount: Int = 1
  override def maximumInputCount: Int = 1
  override def minimumOutputCount: Int = 2
  override def maximumOutputCount: Int = Int.MaxValue

  override private[akka] def astNode = Ast.Broadcast

  final override private[scaladsl] def newInstance() = new Broadcast[T](None)
}

object Balance {
  /**
   * Create a new anonymous `Balance` vertex with the specified input type.
   * Note that a `Balance` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def apply[T]: Balance[T] = new Balance[T](None, waitForAllDownstreams = false)
  /**
   * Create a named `Balance` vertex with the specified input type.
   * Note that a `Balance` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   *
   * If you use `waitForAllDownstreams = true` it will not start emitting
   * elements to downstream outputs until all of them have requested at least one element.
   */
  def apply[T](name: String, waitForAllDownstreams: Boolean = false): Balance[T] = new Balance[T](Some(name), waitForAllDownstreams)

  /**
   * Create a new anonymous `Balance` vertex with the specified input type.
   * Note that a `Balance` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   *
   * If you use `waitForAllDownstreams = true` it will not start emitting
   * elements to downstream outputs until all of them have requested at least one element.
   */
  def apply[T](waitForAllDownstreams: Boolean): Balance[T] = new Balance[T](None, waitForAllDownstreams)
}

/**
 * Fan-out the stream to several streams. Each element is produced to
 * one of the other streams. It will not shutdown until the subscriptions for at least
 * two downstream subscribers have been established.
 */
final class Balance[T](override val name: Option[String], val waitForAllDownstreams: Boolean) extends FlowGraphInternal.InternalVertex with Junction[T] {
  override private[akka] def vertex = this
  override def minimumInputCount: Int = 1
  override def maximumInputCount: Int = 1
  override def minimumOutputCount: Int = 2
  override def maximumOutputCount: Int = Int.MaxValue

  override private[akka] val astNode = Ast.Balance(waitForAllDownstreams)

  final override private[scaladsl] def newInstance() = new Balance[T](None, waitForAllDownstreams)
}

object Zip {
  /**
   * Create a new anonymous `Zip` vertex with the specified input types.
   * Note that a `Zip` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.*
   */
  def apply[A, B]: Zip[A, B] = new Zip[A, B](None)

  /**
   * Create a named `Zip` vertex with the specified input types.
   * Note that a `Zip` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.*
   */
  def apply[A, B](name: String): Zip[A, B] = new Zip[A, B](Some(name))

  class Left[A, B] private[akka] (private[akka] val vertex: Zip[A, B]) extends JunctionInPort[A] {
    override private[akka] def port = 0
    type NextT = (A, B)
    override private[akka] def next = vertex.out
  }
  class Right[A, B] private[akka] (private[akka] val vertex: Zip[A, B]) extends JunctionInPort[B] {
    override private[akka] def port = 1
    type NextT = (A, B)
    override private[akka] def next = vertex.out
  }
  class Out[A, B] private[akka] (private[akka] val vertex: Zip[A, B]) extends JunctionOutPort[(A, B)]
}

/**
 * Takes two streams and outputs an output stream formed from the two input streams
 * by combining corresponding elements in pairs. If one of the two streams is
 * longer than the other, its remaining elements are ignored.
 */
private[akka] class Zip[A, B](override val name: Option[String]) extends FlowGraphInternal.InternalVertex {
  import akka.stream.impl.Zip.AsScalaTuple2

  val left = new Zip.Left(this)
  val right = new Zip.Right(this)
  val out = new Zip.Out(this)

  override def minimumInputCount: Int = 2
  override def maximumInputCount: Int = 2
  override def minimumOutputCount: Int = 1
  override def maximumOutputCount: Int = 1

  override private[akka] def astNode: FanInAstNode = Ast.Zip(AsScalaTuple2)

  final override private[scaladsl] def newInstance() = new Zip[A, B](name = None)
}

object Unzip {
  /**
   * Create a new anonymous `Unzip` vertex with the specified output types.
   * Note that a `Unzip` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.*
   */
  def apply[A, B]: Unzip[A, B] = new Unzip[A, B](None)

  /**
   * Create a named `Unzip` vertex with the specified output types.
   * Note that a `Unzip` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.*
   */
  def apply[A, B](name: String): Unzip[A, B] = new Unzip[A, B](Some(name))

  class In[A, B] private[akka] (private[akka] val vertex: Unzip[A, B]) extends JunctionInPort[(A, B)] {
    override type NextT = Nothing
    override private[akka] def next = NoNext
  }

  class Left[A, B] private[akka] (private[akka] val vertex: Unzip[A, B]) extends JunctionOutPort[A] {
    override private[akka] def port = 0
  }
  class Right[A, B] private[akka] (private[akka] val vertex: Unzip[A, B]) extends JunctionOutPort[B] {
    override private[akka] def port = 1
  }
}

/**
 * Takes a stream of pair elements and splits each pair to two output streams.
 */
final class Unzip[A, B](override val name: Option[String]) extends FlowGraphInternal.InternalVertex {
  val in = new Unzip.In(this)
  val left = new Unzip.Left(this)
  val right = new Unzip.Right(this)

  override def minimumInputCount: Int = 1
  override def maximumInputCount: Int = 1
  override def minimumOutputCount: Int = 2
  override def maximumOutputCount: Int = 2

  override private[akka] def astNode = Ast.Unzip

  final override private[scaladsl] def newInstance() = new Unzip[A, B](name = None)
}

object Concat {
  /**
   * Create a new anonymous `Concat` vertex with the specified input types.
   * Note that a `Concat` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.*
   */
  def apply[T]: Concat[T] = new Concat[T](None)

  /**
   * Create a named `Concat` vertex with the specified input types.
   * Note that a `Concat` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.*
   */
  def apply[T](name: String): Concat[T] = new Concat[T](Some(name))

  class First[T] private[akka] (val vertex: Concat[T]) extends JunctionInPort[T] {
    override val port = 0
    type NextT = T
    override def next = vertex.out
  }
  class Second[T] private[akka] (val vertex: Concat[T]) extends JunctionInPort[T] {
    override val port = 1
    type NextT = T
    override def next = vertex.out
  }
  class Out[T] private[akka] (val vertex: Concat[T]) extends JunctionOutPort[T]
}

/**
 * Takes two streams and outputs an output stream formed from the two input streams
 * by consuming one stream first emitting all of its elements, then consuming the
 * second stream emitting all of its elements.
 */
final class Concat[T](override val name: Option[String]) extends FlowGraphInternal.InternalVertex {
  val first = new Concat.First(this)
  val second = new Concat.Second(this)
  val out = new Concat.Out(this)

  override def minimumInputCount: Int = 2
  override def maximumInputCount: Int = 2
  override def minimumOutputCount: Int = 1
  override def maximumOutputCount: Int = 1

  override private[akka] def astNode = Ast.Concat

  final override private[scaladsl] def newInstance() = new Concat[T](name = None)
}

object UndefinedSink {
  /**
   * Create a new anonymous `UndefinedSink` vertex with the specified input type.
   * Note that a `UndefinedSink` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def apply[T]: UndefinedSink[T] = new UndefinedSink[T](None)
  /**
   * Create a named `UndefinedSink` vertex with the specified input type.
   * Note that a `UndefinedSink` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def apply[T](name: String): UndefinedSink[T] = new UndefinedSink[T](Some(name))
}
/**
 * It is possible to define a [[PartialFlowGraph]] with output pipes that are not connected
 * yet by using this placeholder instead of the real [[Sink]]. Later the placeholder can
 * be replaced with [[FlowGraphBuilder#attachSink]].
 */
final class UndefinedSink[-T](override val name: Option[String]) extends FlowGraphInternal.InternalVertex {

  override def minimumInputCount: Int = 1
  override def maximumInputCount: Int = 1
  override def minimumOutputCount: Int = 0
  override def maximumOutputCount: Int = 0

  override private[akka] def astNode = throw new UnsupportedOperationException("Undefined sinks cannot be materialized")

  final override private[scaladsl] def newInstance() = new UndefinedSink[T](name = None)
}

object UndefinedSource {
  /**
   * Create a new anonymous `UndefinedSource` vertex with the specified input type.
   * Note that a `UndefinedSource` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def apply[T]: UndefinedSource[T] = new UndefinedSource[T](None)
  /**
   * Create a named `UndefinedSource` vertex with the specified output type.
   * Note that a `UndefinedSource` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def apply[T](name: String): UndefinedSource[T] = new UndefinedSource[T](Some(name))
}
/**
 * It is possible to define a [[PartialFlowGraph]] with input pipes that are not connected
 * yet by using this placeholder instead of the real [[Source]]. Later the placeholder can
 * be replaced with [[FlowGraphBuilder#attachSource]].
 */
final class UndefinedSource[+T](override val name: Option[String]) extends FlowGraphInternal.InternalVertex {
  override def minimumInputCount: Int = 0
  override def maximumInputCount: Int = 0
  override def minimumOutputCount: Int = 1
  override def maximumOutputCount: Int = 1

  override private[akka] def astNode = throw new UnsupportedOperationException("Undefined sources cannot be materialized")

  final override private[scaladsl] def newInstance() = new UndefinedSource[T](name = None)
}

/**
 * INTERNAL API
 */
private[akka] object FlowGraphInternal {

  /**
   * INTERNAL API
   * Workaround for issue #16109. Reflection, used by scalax.graph, is not thread
   * safe. Initialize one graph before it is used for real.
   */
  private[akka] val reflectionIssueWorkaround = {
    val graph = ImmutableGraph.empty[FlowGraphInternal.Vertex, FlowGraphInternal.EdgeType]
    val builder = new FlowGraphBuilder(graph)
    val merge = Merge[String]
    builder.
      addEdge(Source.empty[String], merge).
      addEdge(Source.empty[String], merge).
      addEdge(merge, Sink.ignore)
    builder.build()
  }

  def throwUnsupportedValue(x: Any): Nothing =
    throw new IllegalArgumentException(s"Unsupported value [$x] of type [${x.getClass.getName}]. Only Pipes and Graphs are supported!")

  def UnlabeledPort = -1

  sealed trait Vertex {
    // must return a new instance that is uniquely identifiable (i.e. no name for hashCode or equality)
    private[scaladsl] def newInstance(): Vertex
  }

  case class SourceVertex(source: Source[_]) extends Vertex {
    override def toString = source.toString

    /**
     * These are unique keys, case class equality would break them.
     * In the case of KeyedSources we MUST compare by object equality, in order to avoid ambigiousities in materialization.
     */
    final override def equals(other: Any): Boolean = other match {
      case v: SourceVertex ⇒ (source, v.source) match {
        case (k1: KeyedSource[_], k2: KeyedSource[_]) ⇒ k1 == k2
        case _                                        ⇒ super.equals(other)
      }
      case _ ⇒ false
    }
    final override def hashCode: Int = source match {
      case k: KeyedSource[_] ⇒ k.hashCode
      case _                 ⇒ super.hashCode
    }

    final override private[scaladsl] def newInstance() = this.copy()
  }

  case class SinkVertex(sink: Sink[_]) extends Vertex {
    override def toString = sink.toString

    /**
     * These are unique keys, case class equality would break them.
     * In the case of KeyedSources we MUST compare by object equality, in order to avoid ambigiousities in materialization.
     */
    final override def equals(other: Any): Boolean = other match {
      case v: SinkVertex ⇒ (sink, v.sink) match {
        case (k1: KeyedSink[_], k2: KeyedSink[_]) ⇒ k1 == k2
        case _                                    ⇒ super.equals(other)
      }
      case _ ⇒ false
    }
    final override def hashCode: Int = sink match {
      case k: KeyedSink[_] ⇒ k.hashCode
      case _               ⇒ super.hashCode
    }

    final override private[scaladsl] def newInstance() = this.copy()
  }

  trait InternalVertex extends Vertex {
    def name: Option[String]

    def minimumInputCount: Int
    def maximumInputCount: Int
    def minimumOutputCount: Int
    def maximumOutputCount: Int

    private[akka] def astNode: Ast.JunctionAstNode

    final override def equals(obj: Any): Boolean =
      obj match {
        case other: InternalVertex ⇒
          if (name.isDefined) (getClass == other.getClass && name == other.name) else (this eq other)
        case _ ⇒ false
      }

    final override def hashCode: Int = name match {
      case Some(n) ⇒ n.hashCode()
      case None    ⇒ super.hashCode()
    }

    override def toString = name match {
      case Some(n) ⇒ n
      case None    ⇒ getClass.getSimpleName + "@" + Integer.toHexString(super.hashCode())
    }
  }

  // flow not part of equals/hashCode
  case class EdgeLabel(qualifier: Int)(
    val pipe: Pipe[Any, Nothing],
    val inputPort: Int,
    val outputPort: Int) {

    override def toString: String = pipe.toString

  }

  type EdgeType[T] = LkDiEdge[T] { type L1 = EdgeLabel }

  def edges(graph: scalax.collection.Graph[Vertex, EdgeType]): Iterable[EdgeType[Vertex]] =
    graph.edges.map(e ⇒ LkDiEdge(e.from.value, e.to.value)(e.label))

}

object FlowGraphBuilder {
  private[scaladsl] def apply[T](partialFlowGraph: PartialFlowGraph)(block: FlowGraphBuilder ⇒ T): T = {
    val builder = new FlowGraphBuilder(partialFlowGraph.graph)
    block(builder)
  }
}

/**
 * Builder of [[FlowGraph]] and [[PartialFlowGraph]].
 * Syntactic sugar is provided by [[FlowGraphImplicits]].
 */
class FlowGraphBuilder private (graph: Graph[FlowGraphInternal.Vertex, FlowGraphInternal.EdgeType]) {
  import FlowGraphInternal._

  private[akka] def this() = this(Graph.empty[FlowGraphInternal.Vertex, FlowGraphInternal.EdgeType])

  private[akka] def this(immutableGraph: ImmutableGraph[FlowGraphInternal.Vertex, FlowGraphInternal.EdgeType]) =
    this(Graph.from(edges = FlowGraphInternal.edges(immutableGraph)))

  private implicit val edgeFactory = scalax.collection.edge.LkDiEdge.asInstanceOf[LkBase.LkEdgeCompanion[EdgeType]]

  var edgeQualifier = graph.edges.size

  private var cyclesAllowed = false

  private def addSourceToPipeEdge[In, Out](source: Source[In], pipe: Pipe[In, Out], junctionIn: JunctionInPort[Out]): this.type = {
    val sourceVertex = SourceVertex(source)
    checkJunctionInPortPrecondition(junctionIn)
    addGraphEdge(sourceVertex, junctionIn.vertex, pipe, inputPort = junctionIn.port, outputPort = UnlabeledPort)
    this
  }

  private def addPipeToSinkEdge[In, Out](junctionOut: JunctionOutPort[In], pipe: Pipe[In, Out], sink: Sink[Out]): this.type = {
    val sinkVertex = SinkVertex(sink)
    checkJunctionOutPortPrecondition(junctionOut)
    addGraphEdge(junctionOut.vertex, sinkVertex, pipe, inputPort = UnlabeledPort, outputPort = junctionOut.port)
    this
  }

  def addEdge[T](source: UndefinedSource[T], junctionIn: JunctionInPort[T]): this.type = addEdge(source, Pipe.empty[T], junctionIn)

  def addEdge[In, Out](source: UndefinedSource[In], flow: Flow[In, Out], junctionIn: JunctionInPort[Out]): this.type = {
    checkJunctionInPortPrecondition(junctionIn)
    flow match {
      case pipe: Pipe[In, Out] ⇒
        addGraphEdge(source, junctionIn.vertex, pipe, inputPort = junctionIn.port, outputPort = UnlabeledPort)
      case gflow: GraphFlow[In, _, _, Out] ⇒
        val tOut = UndefinedSink[In]
        val tIn = UndefinedSource[Out]
        addEdge(source, tOut)
        addEdge(tIn, junctionIn)
        connect(tOut, gflow, tIn)
      case x ⇒ throwUnsupportedValue(x)
    }
    this
  }

  def addEdge[T](junctionOut: JunctionOutPort[T], sink: UndefinedSink[T]): this.type =
    addEdge(junctionOut, Pipe.empty[T], sink)

  def addEdge[In, Out](junctionOut: JunctionOutPort[In], flow: Flow[In, Out], sink: UndefinedSink[Out]): this.type = {
    checkJunctionOutPortPrecondition(junctionOut)
    flow match {
      case pipe: Pipe[In, Out] ⇒
        addGraphEdge(junctionOut.vertex, sink, pipe, inputPort = UnlabeledPort, outputPort = junctionOut.port)
      case gflow: GraphFlow[In, _, _, Out] ⇒
        val tOut = UndefinedSink[In]
        val tIn = UndefinedSource[Out]
        addEdge(junctionOut, tOut)
        addEdge(tIn, sink)
        connect(tOut, gflow, tIn)
      case x ⇒ throwUnsupportedValue(x)
    }
    this
  }

  def addEdge[T](junctionOut: JunctionOutPort[T], junctionIn: JunctionInPort[T]): this.type =
    addEdge(junctionOut, Pipe.empty[T], junctionIn)

  def addEdge[In, Out](junctionOut: JunctionOutPort[In], flow: Flow[In, Out], junctionIn: JunctionInPort[Out]): this.type = {
    checkJunctionOutPortPrecondition(junctionOut)
    checkJunctionInPortPrecondition(junctionIn)
    flow match {
      case pipe: Pipe[In, Out] ⇒
        addGraphEdge(junctionOut.vertex, junctionIn.vertex, pipe, inputPort = junctionIn.port, outputPort = junctionOut.port)
      case gflow: GraphFlow[In, _, _, Out] ⇒
        val tOut = UndefinedSink[In]
        val tIn = UndefinedSource[Out]
        addEdge(junctionOut, tOut)
        addEdge(tIn, junctionIn)
        connect(tOut, gflow, tIn)
      case x ⇒ throwUnsupportedValue(x)
    }
    this
  }

  def addEdge[T](source: Source[T], junctionIn: JunctionInPort[T]): this.type = addEdge(source, Pipe.empty[T], junctionIn)

  def addEdge[In, Out](source: Source[In], flow: Flow[In, Out], junctionIn: JunctionInPort[Out]): this.type = {
    (source, flow) match {
      case (spipe: SourcePipe[In], pipe: Pipe[In, Out]) ⇒
        addSourceToPipeEdge(spipe.input, Pipe(spipe.ops).appendPipe(pipe), junctionIn)
      case (gsource: GraphSource[_, In], _) ⇒
        val tOut = UndefinedSink[In]
        val tIn = UndefinedSource[Out]
        addEdge(gsource, tOut)
        addEdge(tIn, junctionIn)
        connect(tOut, flow, tIn)
      case (source: Source[In], pipe: Pipe[In, Out]) ⇒
        addSourceToPipeEdge(source, pipe, junctionIn)
    }
    this
  }

  def addEdge[T](junctionOut: JunctionOutPort[T], sink: Sink[T]): this.type =
    addEdge(junctionOut, Pipe.empty[T], sink)

  def addEdge[In, Out](junctionOut: JunctionOutPort[In], flow: Flow[In, Out], sink: Sink[Out]): this.type = {
    (flow, sink) match {
      case (pipe: Pipe[In, Out], spipe: SinkPipe[Out]) ⇒
        addPipeToSinkEdge(junctionOut, pipe.appendPipe(Pipe(spipe.ops)), spipe.output)
      case (_, gsink: GraphSink[Out, _]) ⇒
        val tOut = UndefinedSink[In]
        val tIn = UndefinedSource[Out]
        addEdge(tIn, gsink)
        addEdge(junctionOut, tOut)
        connect(tOut, flow, tIn)
      case (pipe: Pipe[In, Out], sink: Sink[Out]) ⇒
        addPipeToSinkEdge(junctionOut, pipe, sink)
      case x ⇒ throwUnsupportedValue(x)
    }
    this
  }

  def addEdge[T](source: Source[T], sink: Sink[T]): this.type = addEdge(source, Pipe.empty[T], sink)

  def addEdge[In, Out](source: Source[In], flow: Flow[In, Out], sink: Sink[Out]): this.type = {
    (source, flow, sink) match {
      case (sourcePipe: SourcePipe[In], pipe: Pipe[In, Out], sinkPipe: SinkPipe[Out]) ⇒
        val src = sourcePipe.input
        val newPipe = Pipe(sourcePipe.ops).via(pipe).via(Pipe(sinkPipe.ops))
        val snk = sinkPipe.output
        addEdge(src, newPipe, snk) // recursive, but now it is a Source-Pipe-Sink
      case (sourcePipe: SourcePipe[In], pipe: Pipe[In, Out], sink: Sink[Out]) ⇒
        val src = sourcePipe.input
        val newPipe = Pipe(sourcePipe.ops).via(pipe)
        addEdge(src, newPipe, sink) // recursive, but now it is a Source-Pipe-Sink
      case (source: Source[In], pipe: Pipe[In, Out], sinkPipe: SinkPipe[Out]) ⇒
        val newPipe = pipe.via(Pipe(sinkPipe.ops))
        val snk = sinkPipe.output
        addEdge(source, newPipe, snk) // recursive, but now it is a Source-Pipe-Sink
      case (_, gflow: GraphFlow[In, _, _, Out], _) ⇒
        val tOut = UndefinedSink[In]
        val tIn = UndefinedSource[Out]
        addEdge(source, tOut)
        addEdge(tIn, sink)
        connect(tOut, gflow, tIn)
      case (source: Source[In], pipe: Pipe[In, Out], sink: Sink[Out]) ⇒
        addGraphEdge(SourceVertex(source), SinkVertex(sink), pipe, inputPort = UnlabeledPort, outputPort = UnlabeledPort)
      case x ⇒ throwUnsupportedValue(x)
    }

    this
  }

  def addEdge[T](source: UndefinedSource[T], sink: UndefinedSink[T]): this.type = addEdge(source, Pipe.empty[T], sink)

  def addEdge[In, Out](source: UndefinedSource[In], flow: Flow[In, Out], sink: UndefinedSink[Out]): this.type = {
    flow match {
      case pipe: Pipe[In, Out] ⇒
        addGraphEdge(source, sink, pipe, inputPort = UnlabeledPort, outputPort = UnlabeledPort)
      case gflow: GraphFlow[In, _, _, Out] ⇒
        val tOut = UndefinedSink[In]
        val tIn = UndefinedSource[Out]
        addEdge(source, tOut)
        addEdge(tIn, sink)
        connect(tOut, gflow, tIn)
      case x ⇒ throwUnsupportedValue(x)
    }
    this
  }

  def addEdge[T](source: UndefinedSource[T], sink: Sink[T]): this.type = addEdge(source, Pipe.empty[T], sink)

  def addEdge[In, Out](source: UndefinedSource[In], flow: Flow[In, Out], sink: Sink[Out]): this.type = {
    (flow, sink) match {
      case (pipe: Pipe[In, Out], spipe: SinkPipe[Out]) ⇒
        addGraphEdge(source, SinkVertex(spipe.output), pipe.appendPipe(Pipe(spipe.ops)), inputPort = UnlabeledPort, outputPort = UnlabeledPort)
      case (gflow: GraphFlow[In, _, _, Out], _) ⇒
        val tOut = UndefinedSink[In]
        val tIn = UndefinedSource[Out]
        addEdge(source, tOut)
        addEdge(tIn, sink)
        connect(tOut, gflow, tIn)
      case (_, gSink: GraphSink[Out, _]) ⇒
        val oOut = UndefinedSink[Out]
        addEdge(source, flow, oOut)
        gSink.importAndConnect(this, oOut)
      case (pipe: Pipe[In, Out], sink: Sink[Out]) ⇒
        addGraphEdge(source, SinkVertex(sink), pipe, inputPort = UnlabeledPort, outputPort = UnlabeledPort)
      case x ⇒ throwUnsupportedValue(x)
    }
    this
  }

  def addEdge[T](source: Source[T], sink: UndefinedSink[T]): this.type = addEdge(source, Pipe.empty[T], sink)

  def addEdge[In, Out](source: Source[In], flow: Flow[In, Out], sink: UndefinedSink[Out]): this.type = {
    (flow, source) match {
      case (pipe: Pipe[In, Out], spipe: SourcePipe[Out]) ⇒
        addGraphEdge(SourceVertex(spipe.input), sink, Pipe(spipe.ops).appendPipe(pipe), inputPort = UnlabeledPort, outputPort = UnlabeledPort)
      case (_, gsource: GraphSource[_, In]) ⇒
        val tOut1 = UndefinedSource[In]
        val tOut2 = UndefinedSink[In]
        val tIn = UndefinedSource[Out]
        addEdge(tOut1, tOut2)
        gsource.importAndConnect(this, tOut1)
        addEdge(tIn, sink)
        connect(tOut2, flow, tIn)
      case (pipe: Pipe[In, Out], source: Source[In]) ⇒
        addGraphEdge(SourceVertex(source), sink, pipe, inputPort = UnlabeledPort, outputPort = UnlabeledPort)
      case x ⇒ throwUnsupportedValue(x)
    }
    this
  }

  private def uncheckedAddGraphEdge[In, Out](from: Vertex, to: Vertex, pipe: Pipe[In, Out], inputPort: Int, outputPort: Int): Unit = {
    if (edgeQualifier == Int.MaxValue) throw new IllegalArgumentException(s"Too many edges")
    val label = EdgeLabel(edgeQualifier)(pipe.asInstanceOf[Pipe[Any, Nothing]], inputPort, outputPort)
    graph.addLEdge(from, to)(label)
    edgeQualifier += 1
  }

  private def addGraphEdge[In, Out](from: Vertex, to: Vertex, pipe: Pipe[In, Out], inputPort: Int, outputPort: Int): Unit = {
    checkAddSourceSinkPrecondition(from)
    checkAddSourceSinkPrecondition(to)
    uncheckedAddGraphEdge(from, to, pipe, inputPort, outputPort)
  }

  private def addOrReplaceGraphEdge[In, Out](from: Vertex, to: Vertex, pipe: Pipe[In, Out], inputPort: Int, outputPort: Int): Unit = {
    checkAddOrReplaceSourceSinkPrecondition(from)
    checkAddOrReplaceSourceSinkPrecondition(to)
    uncheckedAddGraphEdge(from, to, pipe, inputPort, outputPort)
  }

  def attachSink[Out](token: UndefinedSink[Out], sink: Sink[Out]): this.type = {
    graph.find(token) match {
      case Some(existing) ⇒
        val edge = existing.incoming.head
        graph.remove(existing)
        sink match {
          case spipe: SinkPipe[Out] ⇒
            val pipe = edge.label.pipe.appendPipe(Pipe(spipe.ops))
            addGraphEdge(edge.from.value, SinkVertex(spipe.output), pipe, edge.label.inputPort, edge.label.outputPort)
          case gsink: GraphSink[Out, _] ⇒
            gsink.importAndConnect(this, token)
          case sink: Sink[Out] ⇒
            addGraphEdge(edge.from.value, SinkVertex(sink), edge.label.pipe, edge.label.inputPort, edge.label.outputPort)
        }

      case None ⇒ throw new IllegalArgumentException(s"No matching UndefinedSink [${token}]")
    }
    this
  }

  def attachSource[In](token: UndefinedSource[In], source: Source[In]): this.type = {
    graph.find(token) match {
      case Some(existing) ⇒
        val edge = existing.outgoing.head
        graph.remove(existing)
        source match {
          case spipe: SourcePipe[In] ⇒
            val pipe = Pipe(spipe.ops).appendPipe(edge.label.pipe)
            addGraphEdge(SourceVertex(spipe.input), edge.to.value, pipe, edge.label.inputPort, edge.label.outputPort)
          case gsource: GraphSource[_, In] ⇒
            gsource.importAndConnect(this, token)
          case source: Source[In] ⇒
            addGraphEdge(SourceVertex(source), edge.to.value, edge.label.pipe, edge.label.inputPort, edge.label.outputPort)
          case x ⇒ throwUnsupportedValue(x)
        }

      case None ⇒ throw new IllegalArgumentException(s"No matching UndefinedSource [${token}]")
    }
    this
  }

  /**
   * Attach the undefined `out` to the undefined `in` with a flow in-between.
   * Note that one [[PartialFlowGraph]] can be connected to another `PartialFlowGraph`
   * by first importing the other `PartialFlowGraph` with [[#importPartialFlowGraph]]
   * and then connect them with this method.
   */
  def connect[A, B](out: UndefinedSink[A], flow: Flow[A, B], in: UndefinedSource[B]): this.type = {
    require(graph.contains(out), s"Couldn't connect from [$out], no matching UndefinedSink")
    require(graph.contains(in), s"Couldn't connect to [$in], no matching UndefinedSource")

    val outEdge = graph.get(out).incoming.head
    val inEdge = graph.get(in).outgoing.head
    flow match {
      case pipe: Pipe[A, B] ⇒
        val newPipe = outEdge.label.pipe.appendPipe(pipe.asInstanceOf[Pipe[Any, Nothing]]).appendPipe(inEdge.label.pipe)
        graph.remove(out)
        graph.remove(in)
        addOrReplaceGraphEdge(outEdge.from.value, inEdge.to.value, newPipe, inEdge.label.inputPort, outEdge.label.outputPort)
      case gflow: GraphFlow[A, _, _, B] ⇒
        gflow.importAndConnect(this, out, in)
      case x ⇒ throwUnsupportedValue(x)
    }

    this
  }

  /**
   * Import all edges from another [[FlowGraph]] to this builder.
   */
  def importFlowGraph(flowGraph: FlowGraph): this.type = {
    importGraph(flowGraph.graph)
    this
  }

  /**
   * Import all edges from another [[PartialFlowGraph]] to this builder.
   * After importing you can [[#connect]] undefined sources and sinks in
   * two different `PartialFlowGraph` instances.
   */
  def importPartialFlowGraph(partialFlowGraph: PartialFlowGraph): this.type = {
    importGraph(partialFlowGraph.graph)
    this
  }

  private def importGraph(immutableGraph: ImmutableGraph[Vertex, EdgeType]): Unit =
    immutableGraph.edges foreach { edge ⇒
      addGraphEdge(edge.from.value, edge.to.value, edge.label.pipe, edge.label.inputPort, edge.label.outputPort)
    }

  private[scaladsl] def remapPartialFlowGraph(partialFlowGraph: PartialFlowGraph, vertexMapping: Map[Vertex, Vertex]): this.type = {
    val mapping = collection.mutable.Map.empty[Vertex, Vertex] ++ vertexMapping
    def get(vertex: Vertex): Vertex = mapping.getOrElseUpdate(vertex, vertex.newInstance())

    partialFlowGraph.graph.edges.foreach { edge ⇒
      addGraphEdge(get(edge.from.value), get(edge.to.value), edge.label.pipe, edge.label.inputPort, edge.label.outputPort)
    }

    this
  }

  /**
   * Flow graphs with cycles are in general dangerous as it can result in deadlocks.
   * Therefore, cycles in the graph are by default disallowed. `IllegalArgumentException` will
   * be throw when cycles are detected. Sometimes cycles are needed and then
   * you can allow them with this method.
   */
  def allowCycles(): Unit = {
    cyclesAllowed = true
  }

  private def checkAddSourceSinkPrecondition(vertex: Vertex): Unit = {
    checkAmbigiousKeyedElement(vertex)

    vertex match {
      case node @ (_: UndefinedSource[_] | _: UndefinedSink[_]) ⇒
        require(!graph.contains(node), s"[$node] instance is already used in this flow graph")
      case _ ⇒ // ok
    }
  }

  private def checkAmbigiousKeyedElement(vertex: Vertex): Unit = {
    def warningMessage(el: Any): String =
      s"An `${el}` instance MUST NOT be used more than once in a `FlowGraph` to avoid ambiguity. " +
        s"Use individual instances instead the same one multiple times instead. Nodes are: ${graph.nodes}"

    vertex match {
      case v: SourceVertex if v.source.isInstanceOf[KeyedSource[_]] ⇒ require(!graph.contains(v), warningMessage(v.source))
      case v: SinkVertex if v.sink.isInstanceOf[KeyedSink[_]] ⇒ require(!graph.contains(v), warningMessage(v.sink))
      case _ ⇒ // ok
    }
  }

  private def checkAddOrReplaceSourceSinkPrecondition(vertex: Vertex): Unit = {
    vertex match {
      // it is ok to add or replace edges with new or existing undefined sources or sinks
      case node @ (_: UndefinedSource[_] | _: UndefinedSink[_]) ⇒
      // all other nodes must already exist in the graph
      case node ⇒ require(graph.contains(node), s"[$node] instance is not in this flow graph")
    }
  }

  private def checkJunctionInPortPrecondition(junction: JunctionInPort[_]): Unit = {
    junction.vertex match {
      case iv: InternalVertex ⇒
        graph.find(iv) match {
          case Some(node) ⇒
            require(
              (node.inDegree + 1) <= iv.maximumInputCount,
              s"${node.value} must have at most ${iv.maximumInputCount} incoming edges, has ${node.inDegree}\n${graph.edges}")
          case _ ⇒ // ok
        }
      case _ ⇒ // ok, no checks here
    }
  }

  private def checkJunctionOutPortPrecondition(junction: JunctionOutPort[_]): Unit = {
    junction.vertex match {
      case iv: InternalVertex ⇒
        graph.find(iv) match {
          case Some(node) ⇒
            require(
              (node.outDegree + 1) <= iv.maximumOutputCount,
              s"${node.value} must have at most ${iv.maximumOutputCount} outgoing edges, has ${node.outDegree}\n${graph.edges}")
          case _ ⇒ // ok
        }
      case _ ⇒ // ok, no checks here
    }
  }

  /**
   * INTERNAL API
   */
  private[akka] def build(): FlowGraph = {
    checkPartialBuildPreconditions()
    checkBuildPreconditions()
    new FlowGraph(immutableGraph())
  }

  /**
   * INTERNAL API
   */
  private[akka] def partialBuild(): PartialFlowGraph = {
    checkPartialBuildPreconditions()
    new PartialFlowGraph(immutableGraph())
  }

  //convert it to an immutable.Graph
  private def immutableGraph(): ImmutableGraph[Vertex, FlowGraphInternal.EdgeType] = {
    ImmutableGraph.from(edges = FlowGraphInternal.edges(graph))
  }

  private def checkPartialBuildPreconditions(): Unit = {
    if (!cyclesAllowed) graph.findCycle match {
      case None        ⇒
      case Some(cycle) ⇒ throw new IllegalArgumentException("Cycle detected, not supported yet. " + cycle)
    }
  }

  private def checkBuildPreconditions(): Unit = {
    val undefinedSourcesSinks = graph.nodes.filter {
      _.value match {
        case _: UndefinedSource[_] | _: UndefinedSink[_] ⇒ true
        case x ⇒ false
      }
    }
    if (undefinedSourcesSinks.nonEmpty) {
      val formatted = undefinedSourcesSinks.map(n ⇒ n.value match {
        case u: UndefinedSource[_] ⇒ s"$u -> ${n.outgoing.head.label} -> ${n.outgoing.head.to}"
        case u: UndefinedSink[_]   ⇒ s"${n.incoming.head.from} -> ${n.incoming.head.label} -> $u"
      })
      throw new IllegalArgumentException("Undefined sources or sinks: " + formatted.mkString(", "))
    }

    graph.nodes.foreach { node ⇒
      node.value match {
        case v: InternalVertex ⇒
          require(
            node.inDegree >= v.minimumInputCount,
            s"$v must have at least ${v.minimumInputCount} incoming edges")
          require(
            node.inDegree <= v.maximumInputCount,
            s"$v must have at most ${v.maximumInputCount} incoming edges")
          require(
            node.outDegree >= v.minimumOutputCount,
            s"$v must have at least ${v.minimumOutputCount} outgoing edges")
          require(
            node.outDegree <= v.maximumOutputCount,
            s"$v must have at most ${v.maximumOutputCount} outgoing edges")
          v.astNode match {
            case Ast.MergePreferred ⇒
              require(
                node.incoming.count(_.label.inputPort == MergePreferred.PreferredPort) <= 1,
                s"$v must have at most one preferred edge")
            case _ ⇒ // no Ast specific checks for other Ast nodes
          }
        case _ ⇒ // no check for other node types
      }
    }

    require(graph.nonEmpty, "Graph must not be empty")
    require(graph.exists(graph having ((node = { n ⇒ n.isLeaf && n.diSuccessors.isEmpty }))),
      "Graph must have at least one sink")
    require(graph.exists(graph having ((node = { n ⇒ n.isLeaf && n.diPredecessors.isEmpty }))),
      "Graph must have at least one source")

    require(graph.isConnected, "Graph must be connected")
  }

}

/**
 * Build a [[FlowGraph]] by starting with one of the `apply` methods.
 * Syntactic sugar is provided by [[FlowGraphImplicits]].
 *
 * `IllegalArgumentException` is throw if the built graph is invalid.
 */
object FlowGraph {
  /**
   * Build a [[FlowGraph]] from scratch.
   */
  def apply(block: FlowGraphBuilder ⇒ Unit): FlowGraph =
    apply(ImmutableGraph.empty[FlowGraphInternal.Vertex, FlowGraphInternal.EdgeType])(block)

  /**
   * Continue building a [[FlowGraph]] from an existing `PartialFlowGraph`.
   * For example you can attach undefined sources and sinks with
   * [[FlowGraphBuilder#attachSource]] and [[FlowGraphBuilder#attachSink]]
   */
  def apply(partialFlowGraph: PartialFlowGraph)(block: FlowGraphBuilder ⇒ Unit): FlowGraph =
    apply(partialFlowGraph.graph)(block)

  /**
   * Continue building a [[FlowGraph]] from an existing `FlowGraph`.
   * For example you can connect more output flows to a [[Broadcast]] vertex.
   */
  def apply(flowGraph: FlowGraph)(block: FlowGraphBuilder ⇒ Unit): FlowGraph =
    apply(flowGraph.graph)(block)

  private def apply(graph: ImmutableGraph[FlowGraphInternal.Vertex, FlowGraphInternal.EdgeType])(block: FlowGraphBuilder ⇒ Unit): FlowGraph = {
    val builder = new FlowGraphBuilder(graph)
    block(builder)
    builder.build()
  }
}

/**
 * Concrete flow graph that can be materialized with [[#run]].
 *
 * Build a `FlowGraph` by starting with one of the `apply` methods in
 * in [[FlowGraph$ companion object]]. Syntactic sugar is provided by [[FlowGraphImplicits]].
 */
class FlowGraph private[akka] (private[akka] val graph: ImmutableGraph[FlowGraphInternal.Vertex, FlowGraphInternal.EdgeType]) extends RunnableFlow {
  import FlowGraphInternal._

  /**
   * Materialize the `FlowGraph` and attach all sinks and sources.
   */
  override def run()(implicit materializer: FlowMaterializer): MaterializedMap = {
    val edges = graph.edges
    if (edges.size == 1) {
      val edge = edges.head
      (edge.from.value, edge.to.value) match {
        case (sourceVertex: SourceVertex, sinkVertex: SinkVertex) ⇒
          val pipe = edge.label.pipe
          runSimple(sourceVertex, sinkVertex, pipe)
        case _ ⇒
          runGraph()
      }
    } else
      runGraph()
  }

  /**
   * Run FlowGraph that only contains one edge from a `Source` to a `Sink`.
   */
  private def runSimple(sourceVertex: SourceVertex, sinkVertex: SinkVertex, pipe: Pipe[Any, Nothing])(implicit materializer: FlowMaterializer): MaterializedMap = {
    val mf = pipe.withSource(sourceVertex.source).withSink(sinkVertex.sink).run()
    val materializedSources: Map[KeyedSource[_], Any] = sourceVertex match {
      case SourceVertex(source: KeyedSource[_]) ⇒ Map(source -> mf.get(source))
      case _                                    ⇒ Map.empty
    }
    val materializedSinks: Map[KeyedSink[_], Any] = sinkVertex match {
      case SinkVertex(sink: KeyedSink[_]) ⇒ Map(sink -> mf.get(sink))
      case _                              ⇒ Map.empty
    }
    new MaterializedFlowGraph(materializedSources, materializedSinks)
  }

  private def runGraph()(implicit materializer: FlowMaterializer): MaterializedMap = {
    import scalax.collection.GraphTraversal._

    // start with sinks
    val startingNodes = graph.nodes.filter(n ⇒ n.isLeaf && n.diSuccessors.isEmpty)

    case class Memo(visited: Set[graph.EdgeT] = Set.empty,
                    downstreamSubscriber: Map[graph.EdgeT, Subscriber[Any]] = Map.empty,
                    upstreamPublishers: Map[graph.EdgeT, Publisher[Any]] = Map.empty,
                    sources: Map[SourceVertex, SinkPipe[Any]] = Map.empty,
                    materializedSinks: Map[KeyedSink[_], Any] = Map.empty)

    val result = startingNodes.foldLeft(Memo()) {
      case (memo, start) ⇒

        val traverser = graph.innerEdgeTraverser(start, parameters = Parameters(direction = Predecessors, kind = BreadthFirst),
          ordering = graph.defaultEdgeOrdering)
        traverser.foldLeft(memo) {
          case (memo, edge) ⇒
            if (memo.visited(edge)) {
              memo
            } else {
              val pipe = edge.label.pipe

              // returns the materialized sink, if any
              def connectToDownstream(publisher: Publisher[Any]): Option[(KeyedSink[_], Any)] = {
                val f = pipe.withSource(PublisherSource(publisher))
                edge.to.value match {
                  case SinkVertex(sink: KeyedSink[_]) ⇒
                    val mf = f.withSink(sink).run()
                    Some(sink -> mf.get(sink))
                  case SinkVertex(sink) ⇒
                    f.withSink(sink).run()
                    None
                  case _ ⇒
                    f.withSink(SubscriberSink(memo.downstreamSubscriber(edge))).run()
                    None
                }
              }

              edge.from.value match {
                case source: SourceVertex ⇒
                  val f = pipe.withSink(SubscriberSink(memo.downstreamSubscriber(edge)))
                  // connect the source with the pipe later
                  memo.copy(visited = memo.visited + edge,
                    sources = memo.sources.updated(source, f))

                case v: InternalVertex ⇒
                  if (memo.upstreamPublishers.contains(edge)) {
                    // vertex already materialized
                    val materializedSink = connectToDownstream(memo.upstreamPublishers(edge))
                    memo.copy(
                      visited = memo.visited + edge,
                      materializedSinks = memo.materializedSinks ++ materializedSink)
                  } else {

                    val op = v.astNode
                    val (subscribers, publishers) =
                      materializer.materializeJunction[Any, Any](op, edge.from.inDegree, edge.from.outDegree)
                    // TODO: Check for gaps in port numbers
                    val edgeSubscribers =
                      edge.from.incoming.toSeq.sortBy(_.label.inputPort).zip(subscribers)
                    val edgePublishers =
                      edge.from.outgoing.toSeq.sortBy(_.label.outputPort).zip(publishers).toMap
                    val publisher = edgePublishers(edge)
                    val materializedSink = connectToDownstream(publisher)
                    memo.copy(
                      visited = memo.visited + edge,
                      downstreamSubscriber = memo.downstreamSubscriber ++ edgeSubscribers,
                      upstreamPublishers = memo.upstreamPublishers ++ edgePublishers,
                      materializedSinks = memo.materializedSinks ++ materializedSink)
                  }

              }
            }

        }

    }

    // connect all input sources as the last thing
    val materializedSources = result.sources.foldLeft(Map.empty[KeyedSource[_], Any]) {
      case (acc, (SourceVertex(source), pipe)) ⇒
        val mf = pipe.withSource(source).run()
        source match {
          case sourceKey: KeyedSource[_] ⇒ acc.updated(sourceKey, mf.get(sourceKey))
          case _                         ⇒ acc
        }
    }

    new MaterializedFlowGraph(materializedSources, result.materializedSinks)
  }

}

/**
 * Build a [[PartialFlowGraph]] by starting with one of the `apply` methods.
 * Syntactic sugar is provided by [[FlowGraphImplicits]].
 *
 * `IllegalArgumentException` is throw if the built graph is invalid.
 */
object PartialFlowGraph {
  /**
   * Build a [[PartialFlowGraph]] from scratch.
   */
  def apply(block: FlowGraphBuilder ⇒ Unit): PartialFlowGraph =
    apply(ImmutableGraph.empty[FlowGraphInternal.Vertex, FlowGraphInternal.EdgeType])(block)

  /**
   * Continue building a [[PartialFlowGraph]] from an existing `PartialFlowGraph`.
   */
  def apply(partialFlowGraph: PartialFlowGraph)(block: FlowGraphBuilder ⇒ Unit): PartialFlowGraph =
    apply(partialFlowGraph.graph)(block)

  /**
   * Continue building a [[PartialFlowGraph]] from an existing `PartialFlowGraph`.
   */
  def apply(flowGraph: FlowGraph)(block: FlowGraphBuilder ⇒ Unit): PartialFlowGraph =
    apply(flowGraph.graph)(block)

  private def apply(graph: ImmutableGraph[FlowGraphInternal.Vertex, FlowGraphInternal.EdgeType])(block: FlowGraphBuilder ⇒ Unit): PartialFlowGraph = {
    val builder = new FlowGraphBuilder(graph)
    block(builder)
    builder.partialBuild()
  }
}

/**
 * `PartialFlowGraph` may have sources and sinks that are not attached, and it can therefore not
 * be `run` until those are attached.
 *
 * Build a `PartialFlowGraph` by starting with one of the `apply` methods in
 * in [[FlowGraph$ companion object]]. Syntactic sugar is provided by [[FlowGraphImplicits]].
 */
class PartialFlowGraph private[akka] (private[akka] val graph: ImmutableGraph[FlowGraphInternal.Vertex, FlowGraphInternal.EdgeType]) {
  import FlowGraphInternal._

  def undefinedSources: Set[UndefinedSource[_]] =
    graph.nodes.iterator.map(_.value).collect {
      case n: UndefinedSource[_] ⇒ n
    }.toSet

  def undefinedSinks: Set[UndefinedSink[_]] =
    graph.nodes.iterator.map(_.value).collect {
      case n: UndefinedSink[_] ⇒ n
    }.toSet

  /**
   * Creates a [[Source]] from this `PartialFlowGraph`. There needs to be only one [[UndefinedSink]] and
   * no [[UndefinedSource]] in the graph, and you need to provide it as a parameter.
   */
  def toSource[O](out: UndefinedSink[O]): Source[O] = {
    require(graph.contains(out), s"Couldn't create Source with [$out], no matching UndefinedSink")
    checkUndefinedSinksAndSources(sources = Nil, sinks = List(out), description = "Source")
    GraphSource(this, out, Pipe.empty[O])
  }

  /**
   * Creates a [[Flow]] from this `PartialFlowGraph`. There needs to be only one [[UndefinedSource]] and
   * one [[UndefinedSink]] in the graph, and you need to provide them as parameters.
   */
  def toFlow[I, O](in: UndefinedSource[I], out: UndefinedSink[O]): Flow[I, O] = {
    checkUndefinedSinksAndSources(sources = List(in), sinks = List(out), description = "Flow")
    GraphFlow(Pipe.empty[I], in, this, out, Pipe.empty[O])
  }

  /**
   * Creates a [[Sink]] from this `PartialFlowGraph`. There needs to be only one [[UndefinedSource]] and
   * no [[UndefinedSink]] in the graph, and you need to provide it as a parameter.
   */
  def toSink[I](in: UndefinedSource[I]): Sink[I] = {
    checkUndefinedSinksAndSources(sources = List(in), sinks = Nil, description = "Sink")
    GraphSink(Pipe.empty[I], in, this)
  }

  private def checkUndefinedSinksAndSources(sources: List[UndefinedSource[_]], sinks: List[UndefinedSink[_]], description: String): Unit = {
    def expected(name: String, num: Int): String = s"Couldn't create $description, expected $num undefined $name${if (num == 1) "" else "s"}, but found"
    def checkNodes(nodes: List[Vertex], nodeDescription: String): Int = (0 /: nodes) {
      case (size, node) ⇒
        require(graph.contains(node), s"Couldn't create $description with [$node], no matching $nodeDescription")
        size + 1
    }
    val numSources = checkNodes(sources, "UndefinedSource")
    val numSinks = checkNodes(sinks, "UndefinedSink")
    val uSources = undefinedSources
    require(uSources.size == numSources, s"${expected("source", numSources)} ${uSources}")
    val uSinks = undefinedSinks
    require(uSinks.size == numSinks, s"${expected("sink", numSinks)} ${uSinks}")
  }
}

/**
 * Returned by [[FlowGraph#run]] and can be used to retrieve the materialized
 * `Source` inputs or `Sink` outputs.
 */
private[scaladsl] class MaterializedFlowGraph(materializedSources: Map[KeyedSource[_], Any], materializedSinks: Map[KeyedSink[_], Any])
  extends MaterializedMap {

  override def get(key: Source[_]): key.MaterializedType =
    key match {
      case k: KeyedSource[_] ⇒ materializedSources.get(k) match {
        case Some(matSource) ⇒ matSource.asInstanceOf[key.MaterializedType]
        case None ⇒
          throw new IllegalArgumentException(s"Source key [$key] doesn't exist in this flow graph")
      }
      case _ ⇒ ().asInstanceOf[key.MaterializedType]
    }

  def get(key: Sink[_]): key.MaterializedType =
    key match {
      case k: KeyedSink[_] ⇒ materializedSinks.get(k) match {
        case Some(matSink) ⇒ matSink.asInstanceOf[key.MaterializedType]
        case None ⇒
          throw new IllegalArgumentException(s"Sink key [$key] doesn't exist in this flow graph")
      }
      case _ ⇒ ().asInstanceOf[key.MaterializedType]
    }
}

/**
 * Implicit conversions that provides syntactic sugar for building flow graphs.
 * Every method in *Ops classes should have an implicit builder parameter to prevent
 * using conversions where builder is not available (e.g. outside FlowGraph scope).
 */
object FlowGraphImplicits {

  implicit class SourceOps[Out](val source: Source[Out]) extends AnyVal {

    def ~>[O](flow: Flow[Out, O])(implicit builder: FlowGraphBuilder): SourceNextStep[Out, O] =
      new SourceNextStep(source, flow, builder)

    def ~>(junctionIn: JunctionInPort[Out])(implicit builder: FlowGraphBuilder): JunctionOutPort[junctionIn.NextT] = {
      builder.addEdge(source, junctionIn)
      junctionIn.next
    }

    def ~>(sink: UndefinedSink[Out])(implicit builder: FlowGraphBuilder): Unit =
      builder.addEdge(source, sink)

    def ~>(sink: Sink[Out])(implicit builder: FlowGraphBuilder): Unit =
      builder.addEdge(source, sink)
  }

  class SourceNextStep[In, Out](source: Source[In], flow: Flow[In, Out], builder: FlowGraphBuilder) {
    def ~>[O](otherflow: Flow[Out, O]): SourceNextStep[In, O] =
      new SourceNextStep(source, flow.via(otherflow), builder)

    def ~>(junctionIn: JunctionInPort[Out]): JunctionOutPort[junctionIn.NextT] = {
      builder.addEdge(source, flow, junctionIn)
      junctionIn.next
    }

    def ~>(sink: UndefinedSink[Out]): Unit =
      builder.addEdge(source, flow, sink)

    def ~>(sink: Sink[Out]): Unit =
      builder.addEdge(source, flow, sink)
  }

  implicit class JunctionOps[In](val junction: JunctionOutPort[In]) extends AnyVal {
    def ~>[Out](flow: Flow[In, Out])(implicit builder: FlowGraphBuilder): JunctionNextStep[In, Out] =
      new JunctionNextStep(junction, flow, builder)

    def ~>(junctionIn: JunctionInPort[In])(implicit builder: FlowGraphBuilder): JunctionOutPort[junctionIn.NextT] = {
      builder.addEdge(junction, junctionIn)
      junctionIn.next
    }

    def ~>(sink: UndefinedSink[In])(implicit builder: FlowGraphBuilder): Unit =
      builder.addEdge(junction, Pipe.empty[In], sink)

    def ~>(sink: Sink[In])(implicit builder: FlowGraphBuilder): Unit =
      builder.addEdge(junction, sink)
  }

  class JunctionNextStep[In, Out](junction: JunctionOutPort[In], flow: Flow[In, Out], builder: FlowGraphBuilder) {
    def ~>[O](otherFlow: Flow[Out, O]): JunctionNextStep[In, O] =
      new JunctionNextStep(junction, flow.via(otherFlow), builder)

    def ~>(junctionIn: JunctionInPort[Out]): JunctionOutPort[junctionIn.NextT] = {
      builder.addEdge(junction, flow, junctionIn)
      junctionIn.next
    }

    def ~>(sink: UndefinedSink[Out]): Unit =
      builder.addEdge(junction, flow, sink)

    def ~>(sink: Sink[Out]): Unit =
      builder.addEdge(junction, flow, sink)
  }

  implicit class UndefinedSourceOps[In](val source: UndefinedSource[In]) extends AnyVal {
    def ~>[Out](flow: Flow[In, Out])(implicit builder: FlowGraphBuilder): UndefinedSourceNextStep[In, Out] =
      new UndefinedSourceNextStep(source, flow, builder)

    def ~>(junctionIn: JunctionInPort[In])(implicit builder: FlowGraphBuilder): JunctionOutPort[junctionIn.NextT] = {
      builder.addEdge(source, junctionIn)
      junctionIn.next
    }

    def ~>(sink: UndefinedSink[In])(implicit builder: FlowGraphBuilder): Unit =
      builder.addEdge(source, sink)

    def ~>(sink: Sink[In])(implicit builder: FlowGraphBuilder): Unit =
      builder.addEdge(source, sink)
  }

  class UndefinedSourceNextStep[In, Out](source: UndefinedSource[In], flow: Flow[In, Out], builder: FlowGraphBuilder) {
    def ~>[T](otherFlow: Flow[Out, T]): UndefinedSourceNextStep[In, T] =
      new UndefinedSourceNextStep(source, flow.via(otherFlow), builder)

    def ~>(junctionIn: JunctionInPort[Out]): JunctionOutPort[junctionIn.NextT] = {
      builder.addEdge(source, flow, junctionIn)
      junctionIn.next
    }

    def ~>(sink: UndefinedSink[Out]): Unit =
      builder.addEdge(source, flow, sink)

    def ~>(sink: Sink[Out]): Unit =
      builder.addEdge(source, flow, sink)
  }
}
