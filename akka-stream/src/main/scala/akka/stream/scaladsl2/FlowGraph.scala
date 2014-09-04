/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scalax.collection.edge.LDiEdge
import scalax.collection.mutable.Graph
import org.reactivestreams.Subscriber
import akka.stream.impl.BlackholeSubscriber
import org.reactivestreams.Publisher
import org.reactivestreams.Processor

case class Merge[T]() extends FanInOperation[T]
case class Broadcast[T]() extends FanOutOperation[T]

trait FanOutOperation[T] extends FanOperation[T]
trait FanInOperation[T] extends FanOperation[T]
trait FanOperation[T]

/**
 * A mutable FlowGraph builder which allows user to specify edges.
 */
class EdgeBuilder {

  implicit val edgeFactory = scalax.collection.edge.LDiEdge
  private val graph = Graph.empty[Any, LDiEdge]

  def merge[T] = Merge[T]()
  def broadcast[T] = Broadcast[T]()

  def addEdge[In, Out](source: Source[In], flow: ProcessorFlow[In, Out], sink: FanOperation[Out]): this.type = {
    graph.addLEdge(source, sink)(flow)
    this
  }

  def addEdge[In, Out](source: FanOperation[In], flow: ProcessorFlow[In, Out], sink: Sink[Out]): this.type = {
    graph.addLEdge(source, sink)(flow)
    this
  }

  def addEdge[In, Out](source: FanOperation[In], flow: ProcessorFlow[In, Out], sink: FanOperation[Out]): this.type = {
    graph.addLEdge(source, sink)(flow)
    this
  }

  def build(): FlowGraph = new FlowGraph(graph) // FIXME would be nice to convert it to an immutable.Graph here

}

class FlowGraph(graph: Graph[Any, LDiEdge]) {
  def run(implicit materializer: FlowMaterializer): Unit = {
    println("# RUN ----------------")

    graph.nodes.foreach { n =>
      println(s"node ${n} has:\n    successors: ${n.diSuccessors}\n    predecessors${n.diPredecessors}\n    edges ${n.edges}")
    }

    graph.findCycle match {
      case None        =>
      case Some(cycle) => throw new IllegalArgumentException("Cycle detected, not supported yet. " + cycle)
    }

    // start with sinks
    val startingNodes = graph.nodes.filter(_.diSuccessors.isEmpty)

    def dummySubscriber(name: String): Subscriber[Any] = new BlackholeSubscriber[Any](1) {
      override def toString = name
    }
    def dummyPublisher(name: String): Publisher[Any] = new Publisher[Any] {
      def subscribe(subscriber: Subscriber[Any]): Unit = subscriber.onComplete()
      override def toString = name
    }

    println("Starting nodes: " + startingNodes)
    var sources = Map.empty[Source[_], Subscriber[_]]

    var broadcasts = Map.empty[Any, (Subscriber[Any], Publisher[Any])]

    def traverse(edge: graph.EdgeT, downstreamSubscriber: Subscriber[Any]): Unit = {
      edge._1.value match {
        case src: Source[_] =>
          println("# source: " + src)
          sources += (src -> downstreamSubscriber)

        case from: Merge[_] =>
          println(" merge")
          require(edge._1.incoming.size == 2) // FIXME
          // FIXME materialize Merge and attach its output Publisher to the downstreamSubscriber
          val downstreamSub1 = dummySubscriber("subscriber1-" + edge._1.value)
          val downstreamSub2 = dummySubscriber("subscriber2-" + edge._1.value)
          traverse(edge._1.incoming.head, downstreamSub1)
          traverse(edge._1.incoming.tail.head, downstreamSub2)

        case from: Broadcast[_] =>
          require(edge._1.incoming.size == 1) // FIXME
          require(edge._1.outgoing.size == 2) // FIXME
          broadcasts.get(from) match {
            case Some((sub, pub)) =>
              println("# broadcast second")
              // already materialized
              pub.subscribe(downstreamSubscriber)
            case None =>
              println("# broadcast first")
              // FIXME materialize Broadcast and attach its output Publisher to the downstreamSubscriber
              val pub = dummyPublisher("publisher-" + edge._1.value)
              val sub = dummySubscriber("subscriber-" + edge._1.value)
              broadcasts += (from -> ((sub, pub)))
              pub.subscribe(downstreamSubscriber)
              traverse(edge._1.incoming.head, sub)
          }

      }

    }

    startingNodes.foreach { n =>
      n.value match {
        case sink: Sink[_] =>
          require(n.incoming.size == 1) // FIXME
          val edge = n.incoming.head
          val flow = edge.label.asInstanceOf[ProcessorFlow[Any, Any]]
          println("# starting at sink: " + sink + " flow: " + flow)
          val f = flow.withSink(sink.asInstanceOf[Sink[Any]])
          val downstreamSubscriber = f.toSubscriber()
          traverse(edge, downstreamSubscriber)
      }
    }

    println("# Final sources to connect: " + sources)

  }
}

