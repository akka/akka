package akka.stream.scaladsl2

import akka.stream.Transformer
import scalax.collection.GraphPredef._
import scalax.collection.GraphEdge._
import scalax.collection.edge.LDiEdge
import scalax.collection.mutable.Graph
import akka.actor.ActorSystem

// FIXME only for playing around
object GraphLab extends App {

  implicit val system = ActorSystem("GraphLab")
  implicit val materializer = FlowMaterializer()

  def op[In, Out]: () => Transformer[In, Out] = { () =>
    new Transformer[In, Out] {
      override def onNext(elem: In) = List(elem.asInstanceOf[Out])
    }
  }

  val f1 = FlowFrom[String].transform("f1", op[String, String])
  val f2 = FlowFrom[String].transform("f2", op[String, String])
  val f3 = FlowFrom[String].transform("f3", op[String, String])
  val f4 = FlowFrom[String].transform("f4", op[String, String])
  val f5 = FlowFrom[String].transform("f5", op[String, String])
  val f6 = FlowFrom[String].transform("f6", op[String, String])

  val in1 = IterableSource(List("a", "b", "c"))
  val in2 = IterableSource(List("d", "e", "f"))
  val out1 = PublisherSink[String]
  val out2 = FutureSink[String]

  val b1 = new EdgeBuilder
  val merge1 = b1.merge[String]
  b1.
    addEdge(in1, f1, merge1).
    addEdge(in2, f2, merge1).
    addEdge(merge1, f3, out1)
  b1.build().run(materializer)

  val b2 = new EdgeBuilder
  val bcast2 = b2.broadcast[String]
  b2.
    addEdge(in1, f1, bcast2).
    addEdge(bcast2, f2, out1).
    addEdge(bcast2, f3, out2)
  b2.build().run(materializer)

  val b3 = new EdgeBuilder
  val merge3 = b3.merge[String]
  val bcast3 = b3.broadcast[String]
  b3.
    addEdge(in1, f1, merge3).
    addEdge(in2, f2, merge3).
    addEdge(merge3, f3, bcast3).
    addEdge(bcast3, f4, out1).
    addEdge(bcast3, f5, out2)
  b3.build().run(materializer)

  /**
   * in ---> f1 -+-> f2 -+-> f3 ---> out1
   *             ^       |
   *             |       V
   *             f5 <-+- f4
   *                  |
   *                  V
   *                  f6 ---> out2
   */

  val b4 = new EdgeBuilder
  val merge4 = b4.merge[String]
  val bcast41 = b4.broadcast[String]
  val bcast42 = b4.broadcast[String]
  b4.
    addEdge(in1, f1, merge4).
    addEdge(merge4, f2, bcast41).
    addEdge(bcast41, f3, out1).
    addEdge(bcast41, f4, bcast42).
    addEdge(bcast42, f5, merge4). // cycle
    addEdge(bcast42, f6, out2)
  try {
    b4.build().run(materializer)
  } catch {
    case e: IllegalArgumentException => println("Expected: " + e.getMessage)
  }

  val b5 = new EdgeBuilder
  val merge5 = b5.merge[Fruit]
  val in3 = IterableSource(List.empty[Apple])
  val in4 = IterableSource(List.empty[Orange])
  val f7 = FlowFrom[Fruit].transform("f7", op[Fruit, Fruit])
  val f8 = FlowFrom[Fruit].transform("f8", op[Fruit, String])
  b5.
    addEdge(in3, f7, merge5).
    addEdge(in4, f7, merge5).
    addEdge(merge5, f8, out1)

}

trait Fruit
trait Apple extends Fruit
trait Orange extends Fruit
