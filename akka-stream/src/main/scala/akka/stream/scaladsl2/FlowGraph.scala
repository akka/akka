/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scala.language.existentials
import scalax.collection.edge.{ LkBase, LkDiEdge }
import scalax.collection.mutable.Graph
import scalax.collection.immutable.{ Graph ⇒ ImmutableGraph }
import org.reactivestreams.Subscriber
import org.reactivestreams.Publisher
import akka.stream.impl2.Ast

/**
 * Fan-in and fan-out vertices in the [[FlowGraph]] implements
 * this marker interface. Edges may end at a `JunctionInPort`.
 */
sealed trait JunctionInPort[-T] {
  private[akka] def port: Int = FlowGraphInternal.UnlabeledPort
  private[akka] def vertex: FlowGraphInternal.Vertex
  type NextT
  private[akka] def next: JunctionOutPort[NextT]
}

/**
 * Fan-in and fan-out vertices in the [[FlowGraph]] implements
 * this marker interface. Edges may start at a `JunctionOutPort`.
 */
sealed trait JunctionOutPort[T] {
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
 * When building the [[FlowGraph]] you must connect one or more input pipes/taps
 * and one output pipe/sink to the `Merge` vertex.
 */
final class Merge[T](override val name: Option[String]) extends FlowGraphInternal.InternalVertex with Junction[T] {
  override private[akka] val vertex = this
  override val minimumInputCount: Int = 2
  override val maximumInputCount: Int = Int.MaxValue
  override val minimumOutputCount: Int = 1
  override val maximumOutputCount: Int = 1

  override private[akka] def astNode = Ast.Merge
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
}

object Balance {
  /**
   * Create a new anonymous `Balance` vertex with the specified input type.
   * Note that a `Balance` instance can only be used at one place (one vertex)
   * in the `FlowGraph`. This method creates a new instance every time it
   * is called and those instances are not `equal`.
   */
  def apply[T]: Balance[T] = new Balance[T](None)
  /**
   * Create a named `Balance` vertex with the specified input type.
   * Note that a `Balance` with a specific name can only be used at one place (one vertex)
   * in the `FlowGraph`. Calling this method several times with the same name
   * returns instances that are `equal`.
   */
  def apply[T](name: String): Balance[T] = new Balance[T](Some(name))
}

/**
 * Fan-out the stream to several streams. Each element is produced to
 * one of the other streams. It will not shutdown until the subscriptions for at least
 * two downstream subscribers have been established.
 */
final class Balance[T](override val name: Option[String]) extends FlowGraphInternal.InternalVertex with Junction[T] {
  override private[akka] def vertex = this
  override def minimumInputCount: Int = 1
  override def maximumInputCount: Int = 1
  override def minimumOutputCount: Int = 2
  override def maximumOutputCount: Int = Int.MaxValue

  override private[akka] def astNode = Ast.Balance
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
final class Zip[A, B](override val name: Option[String]) extends FlowGraphInternal.InternalVertex {
  val left = new Zip.Left(this)
  val right = new Zip.Right(this)
  val out = new Zip.Out(this)

  override def minimumInputCount: Int = 2
  override def maximumInputCount: Int = 2
  override def minimumOutputCount: Int = 1
  override def maximumOutputCount: Int = 1

  override private[akka] def astNode = Ast.Zip
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
 * yet by using this placeholder instead of the real [[Drain]]. Later the placeholder can
 * be replaced with [[FlowGraphBuilder#attachDrain]].
 */
final class UndefinedSink[-T](override val name: Option[String]) extends FlowGraphInternal.InternalVertex {
  override def minimumInputCount: Int = 1
  override def maximumInputCount: Int = 1
  override def minimumOutputCount: Int = 0
  override def maximumOutputCount: Int = 0

  override private[akka] def astNode = throw new UnsupportedOperationException("Undefined sinks cannot be materialized")
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
 * yet by using this placeholder instead of the real [[Tap]]. Later the placeholder can
 * be replaced with [[FlowGraphBuilder#attachTap]].
 */
final class UndefinedSource[+T](override val name: Option[String]) extends FlowGraphInternal.InternalVertex {
  override def minimumInputCount: Int = 0
  override def maximumInputCount: Int = 0
  override def minimumOutputCount: Int = 1
  override def maximumOutputCount: Int = 1

  override private[akka] def astNode = throw new UnsupportedOperationException("Undefined sources cannot be materialized")
}

/**
 * INTERNAL API
 */
private[akka] object FlowGraphInternal {
  val OnlyPipesErrorMessage = "Only pipes are supported currently!"

  def UnlabeledPort = -1

  sealed trait Vertex
  case class TapVertex(tap: Tap[_]) extends Vertex {
    override def toString = tap.toString
    // these are unique keys, case class equality would break them
    final override def equals(other: Any): Boolean = super.equals(other)
    final override def hashCode: Int = super.hashCode
  }
  case class DrainVertex(drain: Drain[_]) extends Vertex {
    override def toString = drain.toString
    // these are unique keys, case class equality would break them
    final override def equals(other: Any): Boolean = super.equals(other)
    final override def hashCode: Int = super.hashCode
  }

  sealed trait InternalVertex extends Vertex {
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

  private def addTapPipeEdge[In, Out](tap: Tap[In], pipe: Pipe[In, Out], junctionIn: JunctionInPort[Out]): this.type = {
    val tapVertex = TapVertex(tap)
    checkJunctionInPortPrecondition(junctionIn)
    addGraphEdge(tapVertex, junctionIn.vertex, pipe, inputPort = junctionIn.port, outputPort = UnlabeledPort)
    this
  }

  private def addPipeDrainEdge[In, Out](junctionOut: JunctionOutPort[In], pipe: Pipe[In, Out], drain: Drain[Out]): this.type = {
    val drainVertex = DrainVertex(drain)
    checkJunctionOutPortPrecondition(junctionOut)
    addGraphEdge(junctionOut.vertex, drainVertex, pipe, inputPort = UnlabeledPort, outputPort = junctionOut.port)
    this
  }

  def addEdge[In, Out](source: UndefinedSource[In], flow: Flow[In, Out], junctionIn: JunctionInPort[Out]): this.type = {
    checkJunctionInPortPrecondition(junctionIn)
    flow match {
      case pipe: Pipe[In, Out] ⇒
        addGraphEdge(source, junctionIn.vertex, pipe, inputPort = junctionIn.port, outputPort = UnlabeledPort)
      case _ ⇒
        throw new IllegalArgumentException(OnlyPipesErrorMessage)
    }
    this
  }

  def addEdge[In, Out](junctionOut: JunctionOutPort[In], flow: Flow[In, Out], sink: UndefinedSink[Out]): this.type = {
    checkJunctionOutPortPrecondition(junctionOut)
    flow match {
      case pipe: Pipe[In, Out] ⇒
        addGraphEdge(junctionOut.vertex, sink, pipe, inputPort = UnlabeledPort, outputPort = junctionOut.port)
      case _ ⇒
        throw new IllegalArgumentException(OnlyPipesErrorMessage)
    }
    this
  }

  def addEdge[In, Out](junctionOut: JunctionOutPort[In], flow: Flow[In, Out], junctionIn: JunctionInPort[Out]): this.type = {
    checkJunctionOutPortPrecondition(junctionOut)
    checkJunctionInPortPrecondition(junctionIn)
    flow match {
      case pipe: Pipe[In, Out] ⇒
        addGraphEdge(junctionOut.vertex, junctionIn.vertex, pipe, inputPort = junctionIn.port, outputPort = junctionOut.port)
      case _ ⇒
        throw new IllegalArgumentException(OnlyPipesErrorMessage)
    }
    this
  }

  def addEdge[In, Out](source: Source[In], flow: Flow[In, Out], junctionIn: JunctionInPort[Out]): this.type = {
    (source, flow) match {
      case (tap: Tap[In], pipe: Pipe[In, Out]) ⇒
        addTapPipeEdge(tap, pipe, junctionIn)
      case (spipe: SourcePipe[In], pipe: Pipe[In, Out]) ⇒
        addTapPipeEdge(spipe.input, Pipe(spipe.ops).appendPipe(pipe), junctionIn)
      case _ ⇒
        throw new IllegalArgumentException(OnlyPipesErrorMessage)
    }
    this
  }

  def addEdge[In, Out](junctionOut: JunctionOutPort[In], sink: Sink[In]): this.type = {
    sink match {
      case drain: Drain[In]   ⇒ addPipeDrainEdge(junctionOut, Pipe.empty[In], drain)
      case pipe: SinkPipe[In] ⇒ addPipeDrainEdge(junctionOut, Pipe(pipe.ops), pipe.output)
      case _                  ⇒ throw new IllegalArgumentException(OnlyPipesErrorMessage)
    }
    this
  }

  def addEdge[In, Out](junctionOut: JunctionOutPort[In], flow: Flow[In, Out], sink: Sink[Out]): this.type = {
    (flow, sink) match {
      case (pipe: Pipe[In, Out], drain: Drain[Out]) ⇒
        addPipeDrainEdge(junctionOut, pipe, drain)
      case (pipe: Pipe[In, Out], spipe: SinkPipe[Out]) ⇒
        addEdge(junctionOut, pipe.connect(spipe))
      case _ ⇒ throw new IllegalArgumentException(OnlyPipesErrorMessage)
    }
    this
  }

  def addEdge[In, Out](source: Source[In], flow: Flow[In, Out], sink: Sink[Out]): this.type = {
    (source, flow, sink) match {
      case (tap: Tap[In], pipe: Pipe[In, Out], drain: Drain[Out]) ⇒
        addGraphEdge(TapVertex(tap), DrainVertex(drain), pipe, inputPort = UnlabeledPort, outputPort = UnlabeledPort)
      case (sourcePipe: SourcePipe[In], pipe: Pipe[In, Out], sinkPipe: SinkPipe[Out]) ⇒
        val tap = sourcePipe.input
        val newPipe = Pipe(sourcePipe.ops).connect(pipe).connect(Pipe(sinkPipe.ops))
        val drain = sinkPipe.output
        addEdge(tap, newPipe, drain) // recursive, but now it is a Tap-Pipe-Drain
      case (tap: Tap[In], pipe: Pipe[In, Out], sinkPipe: SinkPipe[Out]) ⇒
        val newPipe = pipe.connect(Pipe(sinkPipe.ops))
        val drain = sinkPipe.output
        addEdge(tap, newPipe, drain) // recursive, but now it is a Tap-Pipe-Drain
      case (sourcePipe: SourcePipe[In], pipe: Pipe[In, Out], drain: Drain[Out]) ⇒
        val tap = sourcePipe.input
        val newPipe = Pipe(sourcePipe.ops).connect(pipe)
        addEdge(tap, newPipe, drain) // recursive, but now it is a Tap-Pipe-Drain
      case _ ⇒
        throw new IllegalArgumentException(OnlyPipesErrorMessage)
    }

    this
  }

  def addEdge[In, Out](source: UndefinedSource[In], flow: Flow[In, Out], sink: UndefinedSink[Out]): this.type = {
    flow match {
      case pipe: Pipe[In, Out] ⇒
        addGraphEdge(source, sink, pipe, inputPort = UnlabeledPort, outputPort = UnlabeledPort)
      case _ ⇒
        throw new IllegalArgumentException(OnlyPipesErrorMessage)
    }
    this
  }

  def addEdge[In, Out](source: UndefinedSource[In], flow: Flow[In, Out], sink: Sink[Out]): this.type = {
    (flow, sink) match {
      case (pipe: Pipe[In, Out], drain: Drain[Out]) ⇒
        addGraphEdge(source, DrainVertex(drain), pipe, inputPort = UnlabeledPort, outputPort = UnlabeledPort)
      case (pipe: Pipe[In, Out], spipe: SinkPipe[Out]) ⇒
        addGraphEdge(source, DrainVertex(spipe.output), pipe.appendPipe(Pipe(spipe.ops)), inputPort = UnlabeledPort, outputPort = UnlabeledPort)
      case _ ⇒ throw new IllegalArgumentException(OnlyPipesErrorMessage)
    }
    this
  }

  def addEdge[In, Out](source: Source[In], flow: Flow[In, Out], sink: UndefinedSink[Out]): this.type = {
    (flow, source) match {
      case (pipe: Pipe[In, Out], tap: Tap[In]) ⇒
        addGraphEdge(TapVertex(tap), sink, pipe, inputPort = UnlabeledPort, outputPort = UnlabeledPort)
      case (pipe: Pipe[In, Out], spipe: SourcePipe[Out]) ⇒
        addGraphEdge(TapVertex(spipe.input), sink, Pipe(spipe.ops).appendPipe(pipe), inputPort = UnlabeledPort, outputPort = UnlabeledPort)
      case _ ⇒ throw new IllegalArgumentException(OnlyPipesErrorMessage)
    }
    this
  }

  private def addGraphEdge[In, Out](from: Vertex, to: Vertex, pipe: Pipe[In, Out], inputPort: Int, outputPort: Int): Unit = {
    if (edgeQualifier == Int.MaxValue) throw new IllegalArgumentException(s"Too many edges")
    checkAddTapDrainPrecondition(from)
    checkAddTapDrainPrecondition(to)
    val label = EdgeLabel(edgeQualifier)(pipe.asInstanceOf[Pipe[Any, Nothing]], inputPort, outputPort)
    graph.addLEdge(from, to)(label)
    edgeQualifier += 1
  }

  def attachSink[Out](token: UndefinedSink[Out], sink: Sink[Out]): this.type = {
    graph.find(token) match {
      case Some(existing) ⇒
        val edge = existing.incoming.head
        graph.remove(existing)
        sink match {
          case drain: Drain[Out] ⇒
            addGraphEdge(edge.from.value, DrainVertex(drain), edge.label.pipe, edge.label.inputPort, edge.label.outputPort)
          case spipe: SinkPipe[Out] ⇒
            val pipe = edge.label.pipe.appendPipe(Pipe(spipe.ops))
            addGraphEdge(edge.from.value, DrainVertex(spipe.output), pipe, edge.label.inputPort, edge.label.outputPort)
          case _ ⇒ throw new IllegalArgumentException(OnlyPipesErrorMessage)
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
          case tap: Tap[In] ⇒
            addGraphEdge(TapVertex(tap), edge.to.value, edge.label.pipe, edge.label.inputPort, edge.label.outputPort)
          case spipe: SourcePipe[In] ⇒
            val pipe = Pipe(spipe.ops).appendPipe(edge.label.pipe)
            addGraphEdge(TapVertex(spipe.input), edge.to.value, pipe, edge.label.inputPort, edge.label.outputPort)
          case _ ⇒
            throw new IllegalArgumentException(OnlyPipesErrorMessage)
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
        addGraphEdge(outEdge.from.value, inEdge.to.value, newPipe, inEdge.label.inputPort, outEdge.label.outputPort)
      case _ ⇒
        throw new IllegalArgumentException(OnlyPipesErrorMessage)
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

  /**
   * Flow graphs with cycles are in general dangerous as it can result in deadlocks.
   * Therefore, cycles in the graph are by default disallowed. `IllegalArgumentException` will
   * be throw when cycles are detected. Sometimes cycles are needed and then
   * you can allow them with this method.
   */
  def allowCycles(): Unit = {
    cyclesAllowed = true
  }

  private def checkAddTapDrainPrecondition(vertex: Vertex): Unit = {
    vertex match {
      case node @ (_: UndefinedSource[_] | _: UndefinedSink[_]) ⇒
        require(graph.find(node) == None, s"[$node] instance is already used in this flow graph")
      case _ ⇒ // ok
    }
  }

  private def checkJunctionInPortPrecondition(junction: JunctionInPort[_]): Unit = {
    junction.vertex match {
      case iv: InternalVertex ⇒
        graph.find(iv) match {
          case Some(node) ⇒
            require(
              (node.inDegree + 1) <= iv.maximumInputCount,
              s"${node.value} must have at most ${iv.maximumInputCount} incoming edges")
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
              s"${node.value} must have at most ${iv.maximumOutputCount} outgoing edges")
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
    val undefinedSourcesDrains = graph.nodes.filter {
      _.value match {
        case _: UndefinedSource[_] | _: UndefinedSink[_] ⇒ true
        case x ⇒ false
      }
    }
    if (undefinedSourcesDrains.nonEmpty) {
      val formatted = undefinedSourcesDrains.map(n ⇒ n.value match {
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
   * [[FlowGraphBuilder#attachTap]] and [[FlowGraphBuilder#attachDrain]]
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
class FlowGraph private[akka] (private[akka] val graph: ImmutableGraph[FlowGraphInternal.Vertex, FlowGraphInternal.EdgeType]) {
  import FlowGraphInternal._

  /**
   * Materialize the `FlowGraph` and attach all sinks and sources.
   */
  def run()(implicit materializer: FlowMaterializer): MaterializedFlowGraph = {
    val edges = graph.edges
    if (edges.size == 1) {
      val edge = edges.head
      (edge.from.value, edge.to.value) match {
        case (tapVertex: TapVertex, drainVertex: DrainVertex) ⇒
          val pipe = edge.label.pipe
          runSimple(tapVertex, drainVertex, pipe)
        case _ ⇒
          runGraph()
      }
    } else
      runGraph()
  }

  /**
   * Run FlowGraph that only contains one edge from a `Source` to a `Sink`.
   */
  private def runSimple(tapVertex: TapVertex, drainVertex: DrainVertex, pipe: Pipe[Any, Nothing])(implicit materializer: FlowMaterializer): MaterializedFlowGraph = {
    val mf = pipe.withTap(tapVertex.tap).withDrain(drainVertex.drain).run()
    val materializedSources: Map[TapWithKey[_, _], Any] = tapVertex match {
      case TapVertex(tap: TapWithKey[_, _]) ⇒ Map(tap -> mf.getTapFor(tap))
      case _                                ⇒ Map.empty
    }
    val materializedSinks: Map[DrainWithKey[_, _], Any] = drainVertex match {
      case DrainVertex(drain: DrainWithKey[_, _]) ⇒ Map(drain -> mf.getDrainFor(drain))
      case _                                      ⇒ Map.empty
    }
    new MaterializedFlowGraph(materializedSources, materializedSinks)
  }

  private def runGraph()(implicit materializer: FlowMaterializer): MaterializedFlowGraph = {
    import scalax.collection.GraphTraversal._

    // start with drains
    val startingNodes = graph.nodes.filter(n ⇒ n.isLeaf && n.diSuccessors.isEmpty)

    case class Memo(visited: Set[graph.EdgeT] = Set.empty,
                    downstreamSubscriber: Map[graph.EdgeT, Subscriber[Any]] = Map.empty,
                    upstreamPublishers: Map[graph.EdgeT, Publisher[Any]] = Map.empty,
                    taps: Map[TapVertex, SinkPipe[Any]] = Map.empty,
                    materializedDrains: Map[DrainWithKey[_, _], Any] = Map.empty)

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

              // returns the materialized drain, if any
              def connectToDownstream(publisher: Publisher[Any]): Option[(DrainWithKey[_, _], Any)] = {
                val f = pipe.withTap(PublisherTap(publisher))
                edge.to.value match {
                  case DrainVertex(drain: DrainWithKey[_, _]) ⇒
                    val mf = f.withDrain(drain).run()
                    Some(drain -> mf.getDrainFor(drain))
                  case DrainVertex(drain) ⇒
                    f.withDrain(drain).run()
                    None
                  case _ ⇒
                    f.withDrain(SubscriberDrain(memo.downstreamSubscriber(edge))).run()
                    None
                }
              }

              edge.from.value match {
                case tap: TapVertex ⇒
                  val f = pipe.withDrain(SubscriberDrain(memo.downstreamSubscriber(edge)))
                  // connect the tap with the pipe later
                  memo.copy(visited = memo.visited + edge,
                    taps = memo.taps.updated(tap, f))

                case v: InternalVertex ⇒
                  if (memo.upstreamPublishers.contains(edge)) {
                    // vertex already materialized
                    val materializedDrain = connectToDownstream(memo.upstreamPublishers(edge))
                    memo.copy(
                      visited = memo.visited + edge,
                      materializedDrains = memo.materializedDrains ++ materializedDrain)
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
                    val materializedDrain = connectToDownstream(publisher)
                    memo.copy(
                      visited = memo.visited + edge,
                      downstreamSubscriber = memo.downstreamSubscriber ++ edgeSubscribers,
                      upstreamPublishers = memo.upstreamPublishers ++ edgePublishers,
                      materializedDrains = memo.materializedDrains ++ materializedDrain)
                  }

              }
            }

        }

    }

    // connect all input taps as the last thing
    val materializedTaps = result.taps.foldLeft(Map.empty[TapWithKey[_, _], Any]) {
      case (acc, (TapVertex(tap), pipe)) ⇒
        val mf = pipe.withTap(tap).run()
        tap match {
          case tapKey: TapWithKey[_, _] ⇒ acc.updated(tapKey, mf.getTapFor(tapKey))
          case _                        ⇒ acc
        }
    }

    new MaterializedFlowGraph(materializedTaps, result.materializedDrains)
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

}

/**
 * Returned by [[FlowGraph#run]] and can be used as parameter to the
 * accessor method to retrieve the materialized `Tap` or `Drain`, e.g.
 * [[SubscriberTap#subscriber]] or [[PublisherDrain#publisher]].
 */
class MaterializedFlowGraph(materializedTaps: Map[TapWithKey[_, _], Any], materializedDrains: Map[DrainWithKey[_, _], Any])
  extends MaterializedTap with MaterializedDrain {

  /**
   * Do not call directly. Use accessor method in the concrete `Tap`, e.g. [[SubscriberTap#subscriber]].
   */
  override def getTapFor[T](key: TapWithKey[_, T]): T =
    materializedTaps.get(key) match {
      case Some(matTap) ⇒ matTap.asInstanceOf[T]
      case None ⇒
        throw new IllegalArgumentException(s"Tap key [$key] doesn't exist in this flow graph")
    }

  /**
   * Do not call directly. Use accessor method in the concrete `Drain`, e.g. [[PublisherDrain#publisher]].
   */
  def getDrainFor[T](key: DrainWithKey[_, T]): T =
    materializedDrains.get(key) match {
      case Some(matDrain) ⇒ matDrain.asInstanceOf[T]
      case None ⇒
        throw new IllegalArgumentException(s"Drain key [$key] doesn't exist in this flow graph")
    }
}

/**
 * Implicit conversions that provides syntactic sugar for building flow graphs.
 */
object FlowGraphImplicits {

  implicit class SourceOps[Out](val source: Source[Out]) extends AnyVal {

    def ~>[O](flow: Flow[Out, O])(implicit builder: FlowGraphBuilder): SourceNextStep[Out, O] =
      new SourceNextStep(source, flow, builder)

    def ~>(junctionIn: JunctionInPort[Out])(implicit builder: FlowGraphBuilder): JunctionOutPort[junctionIn.NextT] = {
      builder.addEdge(source, Pipe.empty[Out], junctionIn)
      junctionIn.next
    }

    def ~>(sink: Sink[Out])(implicit builder: FlowGraphBuilder): Unit =
      builder.addEdge(source, Pipe.empty[Out], sink)
  }

  class SourceNextStep[In, Out](source: Source[In], flow: Flow[In, Out], builder: FlowGraphBuilder) {
    def ~>(junctionIn: JunctionInPort[Out]): JunctionOutPort[junctionIn.NextT] = {
      builder.addEdge(source, flow, junctionIn)
      junctionIn.next
    }

    def ~>(sink: Sink[Out]): Unit =
      builder.addEdge(source, flow, sink)
  }

  implicit class JunctionOps[In](val junction: JunctionOutPort[In]) extends AnyVal {
    def ~>[Out](flow: Flow[In, Out])(implicit builder: FlowGraphBuilder): JunctionNextStep[In, Out] =
      new JunctionNextStep(junction, flow, builder)

    def ~>(sink: UndefinedSink[In])(implicit builder: FlowGraphBuilder): Unit =
      builder.addEdge(junction, Pipe.empty[In], sink)

    def ~>(junctionIn: JunctionInPort[In])(implicit builder: FlowGraphBuilder): JunctionOutPort[junctionIn.NextT] = {
      builder.addEdge(junction, Pipe.empty[In], junctionIn)
      junctionIn.next
    }

    def ~>(sink: Sink[In])(implicit builder: FlowGraphBuilder): Unit = builder.addEdge(junction, sink)
  }

  class JunctionNextStep[In, Out](junctionOut: JunctionOutPort[In], flow: Flow[In, Out], builder: FlowGraphBuilder) {
    def ~>(junctionIn: JunctionInPort[Out]): JunctionOutPort[junctionIn.NextT] = {
      builder.addEdge(junctionOut, flow, junctionIn)
      junctionIn.next
    }

    def ~>(sink: Sink[Out]): Unit = {
      builder.addEdge(junctionOut, flow, sink)
    }

    def ~>(sink: UndefinedSink[Out]): Unit = {
      builder.addEdge(junctionOut, flow, sink)
    }
  }

  implicit class UndefinedSourceOps[In](val source: UndefinedSource[In]) extends AnyVal {
    def ~>[Out](flow: Flow[In, Out])(implicit builder: FlowGraphBuilder): UndefinedSourceNextStep[In, Out] =
      new UndefinedSourceNextStep(source, flow, builder)

    def ~>(junctionIn: JunctionInPort[In])(implicit builder: FlowGraphBuilder): JunctionOutPort[junctionIn.NextT] = {
      builder.addEdge(source, Pipe.empty[In], junctionIn)
      junctionIn.next
    }

  }

  class UndefinedSourceNextStep[In, Out](source: UndefinedSource[In], flow: Flow[In, Out], builder: FlowGraphBuilder) {
    def ~>(junctionIn: JunctionInPort[Out]): JunctionOutPort[junctionIn.NextT] = {
      builder.addEdge(source, flow, junctionIn)
      junctionIn.next
    }
  }

}
