/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scala.language.existentials
import scalax.collection.edge.LDiEdge
import scalax.collection.mutable.Graph
import scalax.collection.immutable.{ Graph ⇒ ImmutableGraph }
import org.reactivestreams.Subscriber
import akka.stream.impl.BlackholeSubscriber
import org.reactivestreams.Publisher
import org.reactivestreams.Processor

/**
 * Fan-in and fan-out vertices in the [[FlowGraph]] implements
 * this marker interface.
 */
sealed trait FanOperation[T] extends FlowGraphInternal.Vertex
/**
 * Fan-out vertices in the [[FlowGraph]] implements this marker interface.
 */
trait FanOutOperation[T] extends FanOperation[T]
/**
 * Fan-in vertices in the [[FlowGraph]] implements this marker interface.
 */
trait FanInOperation[T] extends FanOperation[T]

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
final class Merge[T](override val name: Option[String]) extends FanInOperation[T] with FlowGraphInternal.NamedVertex

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
final class Broadcast[T](override val name: Option[String]) extends FanOutOperation[T] with FlowGraphInternal.NamedVertex

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
final class UndefinedSink[T](override val name: Option[String]) extends FlowGraphInternal.NamedVertex

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
final class UndefinedSource[T](override val name: Option[String]) extends FlowGraphInternal.NamedVertex

/**
 * INTERNAL API
 */
private[akka] object FlowGraphInternal {

  sealed trait Vertex
  case class SourceVertex(source: Source[_]) extends Vertex {
    override def toString = source.toString
  }
  case class SinkVertex(sink: Sink[_]) extends Vertex {
    override def toString = sink.toString
  }

  sealed trait NamedVertex extends Vertex {
    def name: Option[String]

    final override def equals(obj: Any): Boolean =
      obj match {
        case other: NamedVertex ⇒
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

}

/**
 * Builder of [[FlowGraph]] and [[PartialFlowGraph]].
 * Syntactic sugar is provided by [[FlowGraphImplicits]].
 */
class FlowGraphBuilder private (graph: Graph[FlowGraphInternal.Vertex, LDiEdge]) {
  import FlowGraphInternal._

  private[akka] def this() = this(Graph.empty[FlowGraphInternal.Vertex, LDiEdge])

  private[akka] def this(immutableGraph: ImmutableGraph[FlowGraphInternal.Vertex, LDiEdge]) =
    this(Graph.from(edges = immutableGraph.edges.map(e ⇒ LDiEdge(e.from.value, e.to.value)(e.label)).toIterable))

  private implicit val edgeFactory = scalax.collection.edge.LDiEdge

  def addEdge[In, Out](source: Source[In], flow: ProcessorFlow[In, Out], sink: FanOperation[Out]): this.type = {
    val sourceVertex = SourceVertex(source)
    checkAddSourceSinkPrecondition(sourceVertex)
    checkAddFanPrecondition(sink, in = true)
    graph.addLEdge(sourceVertex, sink)(flow)
    this
  }

  def addEdge[In, Out](source: UndefinedSource[In], flow: ProcessorFlow[In, Out], sink: FanOperation[Out]): this.type = {
    checkAddSourceSinkPrecondition(source)
    checkAddFanPrecondition(sink, in = true)
    graph.addLEdge(source, sink)(flow)
    this
  }

  def addEdge[In, Out](source: FanOperation[In], flow: ProcessorFlow[In, Out], sink: Sink[Out]): this.type = {
    val sinkVertex = SinkVertex(sink)
    checkAddSourceSinkPrecondition(sinkVertex)
    checkAddFanPrecondition(source, in = false)
    graph.addLEdge(source, sinkVertex)(flow)
    this
  }

  def addEdge[In, Out](source: FanOperation[In], flow: ProcessorFlow[In, Out], sink: UndefinedSink[Out]): this.type = {
    checkAddSourceSinkPrecondition(sink)
    checkAddFanPrecondition(source, in = false)
    graph.addLEdge(source, sink)(flow)
    this
  }

  def addEdge[In, Out](source: FanOperation[In], flow: ProcessorFlow[In, Out], sink: FanOperation[Out]): this.type = {
    checkAddFanPrecondition(source, in = false)
    checkAddFanPrecondition(sink, in = true)
    graph.addLEdge(source, sink)(flow)
    this
  }

  def addEdge[In, Out](flow: FlowWithSource[In, Out], sink: FanOperation[Out]): this.type = {
    addEdge(flow.input, flow.withoutSource, sink)
    this
  }

  def addEdge[In, Out](source: FanOperation[In], flow: FlowWithSink[In, Out]): this.type = {
    addEdge(source, flow.withoutSink, flow.output)
    this
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

  private def checkAddSourceSinkPrecondition(node: Vertex): Unit =
    require(graph.find(node) == None, s"[$node] instance is already used in this flow graph")

  private def checkAddFanPrecondition(fan: FanOperation[_], in: Boolean): Unit = {
    fan match {
      case _: FanOutOperation[_] if in ⇒
        graph.find(fan) match {
          case Some(existing) if existing.incoming.nonEmpty ⇒
            throw new IllegalArgumentException(s"Fan-out [$fan] is already attached to input [${existing.incoming.head}]")
          case _ ⇒ // ok
        }
      case _: FanInOperation[_] if !in ⇒
        graph.find(fan) match {
          case Some(existing) if existing.outgoing.nonEmpty ⇒
            throw new IllegalArgumentException(s"Fan-in [$fan] is already attached to output [${existing.outgoing.head}]")
          case _ ⇒ // ok
        }
      case _ ⇒ // ok
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
  private def immutableGraph(): ImmutableGraph[Vertex, LDiEdge] =
    ImmutableGraph.from(edges = graph.edges.map(e ⇒ LDiEdge(e.from.value, e.to.value)(e.label)).toIterable)

  private def checkPartialBuildPreconditions(): Unit = {
    graph.findCycle match {
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

    // we will be able to relax these checks
    graph.nodes.foreach { node ⇒
      node.value match {
        case merge: Merge[_] ⇒
          require(node.incoming.size == 2, "Merge must have two incoming edges: " + node.incoming)
          require(node.outgoing.size == 1, "Merge must have one outgoing edge: " + node.outgoing)
        case bcast: Broadcast[_] ⇒
          require(node.incoming.size == 1, "Broadcast must have one incoming edge: " + node.incoming)
          require(node.outgoing.size == 2, "Broadcast must have two outgoing edges: " + node.outgoing)
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
    apply(ImmutableGraph.empty[FlowGraphInternal.Vertex, LDiEdge])(block)

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

  private def apply(graph: ImmutableGraph[FlowGraphInternal.Vertex, LDiEdge])(block: FlowGraphBuilder ⇒ Unit): FlowGraph = {
    val builder = new FlowGraphBuilder(graph)
    block(builder)
    builder.build()
  }
}

/**
 * Concrete flow graph that can be materialized with [[#run]].
 */
class FlowGraph private[akka] (private[akka] val graph: ImmutableGraph[FlowGraphInternal.Vertex, LDiEdge]) {
  import FlowGraphInternal._

  /**
   * Materialize the `FlowGraph` and attach all sinks and sources.
   */
  def run()(implicit materializer: FlowMaterializer): MaterializedFlowGraph = {
    import scalax.collection.GraphTraversal._

    // FIXME remove when real materialization is done
    def dummyProcessor(name: String): Processor[Any, Any] = new BlackholeSubscriber[Any](1) with Publisher[Any] with Processor[Any, Any] {
      def subscribe(subscriber: Subscriber[_ >: Any]): Unit = subscriber.onComplete()
      override def toString = name
    }

    // start with sinks
    val startingNodes = graph.nodes.filter(n ⇒ n.isLeaf && n.diSuccessors.isEmpty)

    case class Memo(visited: Set[graph.EdgeT] = Set.empty,
                    nodeProcessor: Map[graph.NodeT, Processor[Any, Any]] = Map.empty,
                    sources: Map[Source[_], FlowWithSink[Any, Any]] = Map.empty,
                    materializedSinks: Map[Sink[_], Any] = Map.empty)

    val result = startingNodes.foldLeft(Memo()) {
      case (memo, start) ⇒

        val traverser = graph.innerEdgeTraverser(start, parameters = Parameters(direction = Predecessors, kind = BreadthFirst),
          ordering = graph.defaultEdgeOrdering)
        traverser.foldLeft(memo) {
          case (memo, edge) ⇒
            if (memo.visited(edge)) {
              memo
            } else {
              val flow = edge.label.asInstanceOf[ProcessorFlow[Any, Any]]

              // returns the materialized sink, if any
              def connectProcessorToDownstream(processor: Processor[Any, Any]): Option[(SinkWithKey[_, _], Any)] = {
                val f = flow.withSource(PublisherSource(processor))
                edge.to.value match {
                  case SinkVertex(sink: SinkWithKey[_, _]) ⇒
                    val mf = f.withSink(sink.asInstanceOf[Sink[Any]]).run()
                    Some(sink -> mf.getSinkFor(sink))
                  case SinkVertex(sink) ⇒
                    f.withSink(sink.asInstanceOf[Sink[Any]]).run()
                    None
                  case _ ⇒
                    f.withSink(SubscriberSink(memo.nodeProcessor(edge.to))).run()
                    None
                }
              }

              edge.from.value match {
                case SourceVertex(src) ⇒
                  val f = flow.withSink(SubscriberSink(memo.nodeProcessor(edge.to)))
                  // connect the source with the flow later
                  memo.copy(visited = memo.visited + edge,
                    sources = memo.sources.updated(src, f))

                case _: FanOperation[_] ⇒
                  val processor = edge.from.value match {
                    case merge: Merge[_] ⇒
                      // FIXME materialize Merge
                      dummyProcessor("merge-processor")
                    case bcast: Broadcast[_] ⇒
                      memo.nodeProcessor.getOrElse(edge.from, {
                        // FIXME materialize Broadcast
                        dummyProcessor("bcast-processor")
                      })
                    case other ⇒
                      throw new IllegalArgumentException("Unknown fan operation: " + other)
                  }
                  val materializedSink = connectProcessorToDownstream(processor)
                  memo.copy(
                    visited = memo.visited + edge,
                    nodeProcessor = memo.nodeProcessor.updated(edge.from, processor),
                    materializedSinks = memo.materializedSinks ++ materializedSink)
              }
            }

        }

    }

    // connect all input sources as the last thing
    val materializedSources = result.sources.foldLeft(Map.empty[Source[_], Any]) {
      case (acc, (src, flow)) ⇒
        val mf = flow.withSource(src).run()
        src match {
          case srcKey: SourceWithKey[_, _] ⇒ acc.updated(src, mf.getSourceFor(srcKey))
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
    apply(ImmutableGraph.empty[FlowGraphInternal.Vertex, LDiEdge])(block)

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

  private def apply(graph: ImmutableGraph[FlowGraphInternal.Vertex, LDiEdge])(block: FlowGraphBuilder ⇒ Unit): PartialFlowGraph = {
    val builder = new FlowGraphBuilder(graph)
    block(builder)
    builder.partialBuild()
  }

}

/**
 * `PartialFlowGraph` may have sources and sinks that are not attach, and it can therefore not
 * be `run`.
 */
class PartialFlowGraph private[akka] (private[akka] val graph: ImmutableGraph[FlowGraphInternal.Vertex, LDiEdge]) {
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
class MaterializedFlowGraph(materializedSources: Map[Source[_], Any], materializedSinks: Map[Sink[_], Any])
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
  }

  class SourceNextStep[In, Out](source: Source[In], flow: ProcessorFlow[In, Out], builder: FlowGraphBuilder) {
    def ~>(sink: FanOperation[Out]): FanOperation[Out] = {
      builder.addEdge(source, flow, sink)
      sink
    }
  }

  implicit class FanOps[In](val fan: FanOperation[In]) extends AnyVal {
    def ~>[Out](flow: ProcessorFlow[In, Out])(implicit builder: FlowGraphBuilder): FanNextStep[In, Out] = {
      new FanNextStep(fan, flow, builder)
    }
  }

  class FanNextStep[In, Out](fan: FanOperation[In], flow: ProcessorFlow[In, Out], builder: FlowGraphBuilder) {
    def ~>(sink: FanOperation[Out]): FanOperation[Out] = {
      builder.addEdge(fan, flow, sink)
      sink
    }

    def ~>(sink: Sink[Out]): Unit = {
      builder.addEdge(fan, flow, sink)
    }

    def ~>(sink: UndefinedSink[Out]): Unit = {
      builder.addEdge(fan, flow, sink)
    }
  }

  implicit class FlowWithSourceOps[In, Out](val flow: FlowWithSource[In, Out]) extends AnyVal {
    def ~>(sink: FanOperation[Out])(implicit builder: FlowGraphBuilder): FanOperation[Out] = {
      builder.addEdge(flow, sink)
      sink
    }
  }

  // FIXME add more for FlowWithSource and FlowWithSink, and shortcuts injecting identity flows

  implicit class UndefinedSourceOps[In](val source: UndefinedSource[In]) extends AnyVal {
    def ~>[Out](flow: ProcessorFlow[In, Out])(implicit builder: FlowGraphBuilder): UndefinedSourceNextStep[In, Out] = {
      new UndefinedSourceNextStep(source, flow, builder)
    }
  }

  class UndefinedSourceNextStep[In, Out](source: UndefinedSource[In], flow: ProcessorFlow[In, Out], builder: FlowGraphBuilder) {
    def ~>(sink: FanOperation[Out]): FanOperation[Out] = {
      builder.addEdge(source, flow, sink)
      sink
    }
  }

}
