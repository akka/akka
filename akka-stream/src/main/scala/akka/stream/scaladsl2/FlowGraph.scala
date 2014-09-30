/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scala.language.existentials
import scalax.collection.edge.LkDiEdge
import scalax.collection.mutable.Graph
import scalax.collection.immutable.{ Graph ⇒ ImmutableGraph }
import org.reactivestreams.Subscriber
import akka.stream.impl.BlackholeSubscriber
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
sealed trait JunctionOutPort[+T] {
  private[akka] def port: Int = FlowGraphInternal.UnlabeledPort
  private[akka] def vertex: FlowGraphInternal.Vertex
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
 * When building the [[FlowGraph]] you must connect one or more input flows/sources
 * and one output flow/sink to the `Merge` vertex.
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
 * It is possible to define a [[PartialFlowGraph]] with output flows that are not connected
 * yet by using this placeholder instead of the real [[Sink]]. Later the placeholder can
 * be replaced with [[FlowGraphBuilder#attachSink]].
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
 * It is possible to define a [[PartialFlowGraph]] with input flows that are not connected
 * yet by using this placeholder instead of the real [[Source]]. Later the placeholder can
 * be replaced with [[FlowGraphBuilder#attachSource]].
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

  def UnlabeledPort = -1

  sealed trait Vertex
  case class SourceVertex(source: Source[_]) extends Vertex {
    override def toString = source.toString
    // these are unique keys, case class equality would break them
    final override def equals(other: Any): Boolean = super.equals(other)
    final override def hashCode: Int = super.hashCode
  }
  case class SinkVertex(sink: Sink[_]) extends Vertex {
    override def toString = sink.toString
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
  case class EdgeLabel(qualifier: Int, inputPort: Int)(val flow: ProcessorFlow[Any, Any]) {
    override def toString: String = flow.toString
  }

}

/**
 * Builder of [[FlowGraph]] and [[PartialFlowGraph]].
 * Syntactic sugar is provided by [[FlowGraphImplicits]].
 */
class FlowGraphBuilder private (graph: Graph[FlowGraphInternal.Vertex, LkDiEdge]) {
  import FlowGraphInternal._

  private[akka] def this() = this(Graph.empty[FlowGraphInternal.Vertex, LkDiEdge])

  private[akka] def this(immutableGraph: ImmutableGraph[FlowGraphInternal.Vertex, LkDiEdge]) =
    this(Graph.from(edges = immutableGraph.edges.map(e ⇒ LkDiEdge(e.from.value, e.to.value)(e.label)).toIterable))

  private implicit val edgeFactory = scalax.collection.edge.LkDiEdge

  var edgeQualifier = graph.edges.size

  private var cyclesAllowed = false

  def addEdge[In, Out](source: Source[In], flow: ProcessorFlow[In, Out], sink: JunctionInPort[Out]): this.type = {
    val sourceVertex = SourceVertex(source)
    checkAddSourceSinkPrecondition(sourceVertex)
    checkJunctionInPortPrecondition(sink)
    addGraphEdge(sourceVertex, sink.vertex, flow, sink.port)
    this
  }

  def addEdge[In, Out](source: UndefinedSource[In], flow: ProcessorFlow[In, Out], sink: JunctionInPort[Out]): this.type = {
    checkAddSourceSinkPrecondition(source)
    checkJunctionInPortPrecondition(sink)
    addGraphEdge(source, sink.vertex, flow, sink.port)
    this
  }

  def addEdge[In, Out](source: JunctionOutPort[In], flow: ProcessorFlow[In, Out], sink: Sink[Out]): this.type = {
    val sinkVertex = SinkVertex(sink)
    checkAddSourceSinkPrecondition(sinkVertex)
    checkJunctionOutPortPrecondition(source)
    // FIXME: output ports are not handled yet
    addGraphEdge(source.vertex, sinkVertex, flow, UnlabeledPort)
    this
  }

  def addEdge[In, Out](source: JunctionOutPort[In], flow: ProcessorFlow[In, Out], sink: UndefinedSink[Out]): this.type = {
    checkAddSourceSinkPrecondition(sink)
    checkJunctionOutPortPrecondition(source)
    // FIXME: output ports are not handled yet
    addGraphEdge(source.vertex, sink, flow, UnlabeledPort)
    this
  }

  def addEdge[In, Out](source: JunctionOutPort[In], flow: ProcessorFlow[In, Out], sink: JunctionInPort[Out]): this.type = {
    checkJunctionOutPortPrecondition(source)
    checkJunctionInPortPrecondition(sink)
    addGraphEdge(source.vertex, sink.vertex, flow, sink.port)
    this
  }

  def addEdge[In, Out](flow: FlowWithSource[In, Out], sink: JunctionInPort[Out]): this.type = {
    addEdge(flow.input, flow.withoutSource, sink)
    this
  }

  def addEdge[In, Out](source: JunctionOutPort[In], flow: FlowWithSink[In, Out]): this.type = {
    addEdge(source, flow.withoutSink, flow.output)
    this
  }

  private def addGraphEdge[In, Out](from: Vertex, to: Vertex, flow: ProcessorFlow[In, Out], portQualifier: Int): Unit = {
    if (edgeQualifier == Int.MaxValue) throw new IllegalArgumentException(s"Too many edges")
    val effectivePortQualifier = if (portQualifier == UnlabeledPort) edgeQualifier else portQualifier
    val label = EdgeLabel(edgeQualifier, portQualifier)(flow.asInstanceOf[ProcessorFlow[Any, Any]])
    graph.addLEdge(from, to)(label)
    edgeQualifier += 1
  }

  def attachSink[Out](token: UndefinedSink[Out], sink: Sink[Out]): this.type = {
    graph.find(token) match {
      case Some(existing) ⇒
        require(existing.value.isInstanceOf[UndefinedSink[_]], s"Flow already attached to a sink [${existing.value}]")
        val edge = existing.incoming.head
        graph.remove(existing)
        graph.addLEdge(edge.from.value, SinkVertex(sink))(edge.label)
      case None ⇒ throw new IllegalArgumentException(s"No matching UndefinedSink [${token}]")
    }
    this
  }

  def attachSource[In](token: UndefinedSource[In], source: Source[In]): this.type = {
    graph.find(token) match {
      case Some(existing) ⇒
        require(existing.value.isInstanceOf[UndefinedSource[_]], s"Flow already attached to a source [${existing.value}]")
        val edge = existing.outgoing.head
        graph.remove(existing)
        graph.addLEdge(SourceVertex(source), edge.to.value)(edge.label)
      case None ⇒ throw new IllegalArgumentException(s"No matching UndefinedSource [${token}]")
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

  private def checkAddSourceSinkPrecondition(node: Vertex): Unit =
    require(graph.find(node) == None, s"[$node] instance is already used in this flow graph")

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
  private def immutableGraph(): ImmutableGraph[Vertex, LkDiEdge] =
    ImmutableGraph.from(edges = graph.edges.map(e ⇒ LkDiEdge(e.from.value, e.to.value)(e.label)).toIterable)

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
    apply(ImmutableGraph.empty[FlowGraphInternal.Vertex, LkDiEdge])(block)

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

  private def apply(graph: ImmutableGraph[FlowGraphInternal.Vertex, LkDiEdge])(block: FlowGraphBuilder ⇒ Unit): FlowGraph = {
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
class FlowGraph private[akka] (private[akka] val graph: ImmutableGraph[FlowGraphInternal.Vertex, LkDiEdge]) {
  import FlowGraphInternal._

  /**
   * Materialize the `FlowGraph` and attach all sinks and sources.
   */
  def run()(implicit materializer: FlowMaterializer): MaterializedFlowGraph = {
    import scalax.collection.GraphTraversal._

    // start with sinks
    val startingNodes = graph.nodes.filter(n ⇒ n.isLeaf && n.diSuccessors.isEmpty)

    case class Memo(visited: Set[graph.EdgeT] = Set.empty,
                    downstreamSubscriber: Map[graph.EdgeT, Subscriber[Any]] = Map.empty,
                    upstreamPublishers: Map[graph.EdgeT, Publisher[Any]] = Map.empty,
                    sources: Map[SourceVertex, FlowWithSink[Any, Any]] = Map.empty,
                    materializedSinks: Map[SinkWithKey[_, _], Any] = Map.empty)

    val result = startingNodes.foldLeft(Memo()) {
      case (memo, start) ⇒

        val traverser = graph.innerEdgeTraverser(start, parameters = Parameters(direction = Predecessors, kind = BreadthFirst),
          ordering = graph.defaultEdgeOrdering)
        traverser.foldLeft(memo) {
          case (memo, edge) ⇒
            if (memo.visited(edge)) {
              memo
            } else {
              val flow = edge.label.asInstanceOf[EdgeLabel].flow

              // returns the materialized sink, if any
              def connectToDownstream(publisher: Publisher[Any]): Option[(SinkWithKey[_, _], Any)] = {
                val f = flow.withSource(PublisherSource(publisher))
                edge.to.value match {
                  case SinkVertex(sink: SinkWithKey[_, _]) ⇒
                    val mf = f.withSink(sink.asInstanceOf[Sink[Any]]).run()
                    Some(sink -> mf.getSinkFor(sink))
                  case SinkVertex(sink) ⇒
                    f.withSink(sink.asInstanceOf[Sink[Any]]).run()
                    None
                  case _ ⇒
                    f.withSink(SubscriberSink(memo.downstreamSubscriber(edge))).run()
                    None
                }
              }

              edge.from.value match {
                case src: SourceVertex ⇒
                  val f = flow.withSink(SubscriberSink(memo.downstreamSubscriber(edge)))
                  // connect the source with the flow later
                  memo.copy(visited = memo.visited + edge,
                    sources = memo.sources.updated(src, f))

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
                      edge.from.incoming.toSeq.sortBy(_.label.asInstanceOf[EdgeLabel].inputPort).zip(subscribers)
                    val edgePublishers =
                      edge.from.outgoing.toSeq.sortBy(_.label.asInstanceOf[EdgeLabel].inputPort).zip(publishers).toMap
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
    val materializedSources = result.sources.foldLeft(Map.empty[SourceWithKey[_, _], Any]) {
      case (acc, (SourceVertex(src), flow)) ⇒
        val mf = flow.withSource(src).run()
        src match {
          case srcKey: SourceWithKey[_, _] ⇒ acc.updated(srcKey, mf.getSourceFor(srcKey))
          case _                           ⇒ acc
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
    apply(ImmutableGraph.empty[FlowGraphInternal.Vertex, LkDiEdge])(block)

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

  private def apply(graph: ImmutableGraph[FlowGraphInternal.Vertex, LkDiEdge])(block: FlowGraphBuilder ⇒ Unit): PartialFlowGraph = {
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
class PartialFlowGraph private[akka] (private[akka] val graph: ImmutableGraph[FlowGraphInternal.Vertex, LkDiEdge]) {
  import FlowGraphInternal._

  def undefinedSources: Set[UndefinedSource[_]] =
    graph.nodes.collect {
      case n if n.value.isInstanceOf[UndefinedSource[_]] ⇒ n.value.asInstanceOf[UndefinedSource[_]]
    }(collection.breakOut)

  def undefinedSinks: Set[UndefinedSink[_]] =
    graph.nodes.collect {
      case n if n.value.isInstanceOf[UndefinedSink[_]] ⇒ n.value.asInstanceOf[UndefinedSink[_]]
    }(collection.breakOut)

}

/**
 * Returned by [[FlowGraph#run]] and can be used as parameter to the
 * accessor method to retrieve the materialized `Source` or `Sink`, e.g.
 * [[SubscriberSource#subscriber]] or [[PublisherSink#publisher]].
 */
class MaterializedFlowGraph(materializedSources: Map[SourceWithKey[_, _], Any], materializedSinks: Map[SinkWithKey[_, _], Any])
  extends MaterializedSource with MaterializedSink {

  /**
   * Do not call directly. Use accessor method in the concrete `Source`, e.g. [[SubscriberSource#subscriber]].
   */
  override def getSourceFor[T](key: SourceWithKey[_, T]): T =
    materializedSources.get(key) match {
      case Some(matSource) ⇒ matSource.asInstanceOf[T]
      case None ⇒
        throw new IllegalArgumentException(s"Source key [$key] doesn't exist in this flow graph")
    }

  /**
   * Do not call directly. Use accessor method in the concrete `Sink`, e.g. [[PublisherSink#publisher]].
   */
  def getSinkFor[T](key: SinkWithKey[_, T]): T =
    materializedSinks.get(key) match {
      case Some(matSink) ⇒ matSink.asInstanceOf[T]
      case None ⇒
        throw new IllegalArgumentException(s"Sink key [$key] doesn't exist in this flow graph")
    }
}

/**
 * Implicit conversions that provides syntactic sugar for building flow graphs.
 */
object FlowGraphImplicits {
  implicit class SourceOps[In](val source: Source[In]) extends AnyVal {
    def ~>[Out](flow: ProcessorFlow[In, Out])(implicit builder: FlowGraphBuilder): SourceNextStep[In, Out] = {
      new SourceNextStep(source, flow, builder)
    }

    def ~>(sink: JunctionInPort[In])(implicit builder: FlowGraphBuilder): JunctionOutPort[sink.NextT] = {
      builder.addEdge(source, ProcessorFlow.empty[In], sink)
      sink.next
    }
  }

  class SourceNextStep[In, Out](source: Source[In], flow: ProcessorFlow[In, Out], builder: FlowGraphBuilder) {
    def ~>(sink: JunctionInPort[Out]): JunctionOutPort[sink.NextT] = {
      builder.addEdge(source, flow, sink)
      sink.next
    }
  }

  implicit class JunctionOps[In](val junction: JunctionOutPort[In]) extends AnyVal {
    def ~>[Out](flow: ProcessorFlow[In, Out])(implicit builder: FlowGraphBuilder): JunctionNextStep[In, Out] = {
      new JunctionNextStep(junction, flow, builder)
    }

    def ~>(sink: Sink[In])(implicit builder: FlowGraphBuilder): Unit =
      builder.addEdge(junction, ProcessorFlow.empty[In], sink)

    def ~>(sink: UndefinedSink[In])(implicit builder: FlowGraphBuilder): Unit =
      builder.addEdge(junction, ProcessorFlow.empty[In], sink)

    def ~>(sink: JunctionInPort[In])(implicit builder: FlowGraphBuilder): JunctionOutPort[sink.NextT] = {
      builder.addEdge(junction, ProcessorFlow.empty[In], sink)
      sink.next
    }

    def ~>(flow: FlowWithSink[In, _])(implicit builder: FlowGraphBuilder): Unit =
      builder.addEdge(junction, flow)
  }

  class JunctionNextStep[In, Out](junction: JunctionOutPort[In], flow: ProcessorFlow[In, Out], builder: FlowGraphBuilder) {
    def ~>(sink: JunctionInPort[Out]): JunctionOutPort[sink.NextT] = {
      builder.addEdge(junction, flow, sink)
      sink.next
    }

    def ~>(sink: Sink[Out]): Unit = {
      builder.addEdge(junction, flow, sink)
    }

    def ~>(sink: UndefinedSink[Out]): Unit = {
      builder.addEdge(junction, flow, sink)
    }
  }

  implicit class FlowWithSourceOps[In, Out](val flow: FlowWithSource[In, Out]) extends AnyVal {
    def ~>(sink: JunctionInPort[Out])(implicit builder: FlowGraphBuilder): JunctionOutPort[sink.NextT] = {
      builder.addEdge(flow, sink)
      sink.next
    }
  }

  implicit class UndefinedSourceOps[In](val source: UndefinedSource[In]) extends AnyVal {
    def ~>[Out](flow: ProcessorFlow[In, Out])(implicit builder: FlowGraphBuilder): UndefinedSourceNextStep[In, Out] = {
      new UndefinedSourceNextStep(source, flow, builder)
    }

    def ~>(sink: JunctionInPort[In])(implicit builder: FlowGraphBuilder): JunctionOutPort[sink.NextT] = {
      builder.addEdge(source, ProcessorFlow.empty[In], sink)
      sink.next
    }

  }

  class UndefinedSourceNextStep[In, Out](source: UndefinedSource[In], flow: ProcessorFlow[In, Out], builder: FlowGraphBuilder) {
    def ~>(sink: JunctionInPort[Out]): JunctionOutPort[sink.NextT] = {
      builder.addEdge(source, flow, sink)
      sink.next
    }
  }

}
