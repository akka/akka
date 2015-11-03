/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit.TwoStreamsSetup
import akka.stream._

import scala.concurrent.Await
import scala.concurrent.duration._

class GraphMergePreferredSpec extends TwoStreamsSetup {
  import FlowGraph.Implicits._

  override type Outputs = Int

  override def fixture(b: FlowGraph.Builder[_]): Fixture = new Fixture(b) {
    val merge = b.add(MergePreferred[Outputs](1))

    override def left: Inlet[Outputs] = merge.preferred
    override def right: Inlet[Outputs] = merge.in(0)
    override def out: Outlet[Outputs] = merge.out

  }

  "preferred merge" must {
    commonTests()

    "prefer selected input more than others" in {
      val numElements = 10000

      val preferred = Source(Stream.fill(numElements)(1))
      val aux = Source(Stream.fill(numElements)(2))

      val result = RunnableGraph.fromGraph(FlowGraph.create(Sink.head[Seq[Int]]) { implicit b ⇒
        sink ⇒
          val merge = b.add(MergePreferred[Int](3))
          val group = b.add(Flow[Int].grouped(numElements * 2))

          b.add(preferred) ~> merge.preferred
          merge ~> group ~> sink.inlet
          b.add(aux) ~> merge.in(0)
          b.add(aux) ~> merge.in(1)
          b.add(aux) ~> merge.in(2)
          ClosedShape
      }).run()

      Await.result(result, 3.seconds).filter(_ == 1).size should be(numElements)
    }

    "eventually pass through all elements" in {
      val result = RunnableGraph.fromGraph(FlowGraph.create(Sink.head[Seq[Int]]) { implicit b ⇒
        sink ⇒
          val merge = b.add(MergePreferred[Int](3))
          b.add(Source(1 to 100)) ~> merge.preferred
          val group = b.add(Flow[Int].grouped(500))

          merge ~> group ~> sink.inlet
          b.add(Source(101 to 200)) ~> merge.in(0)
          b.add(Source(201 to 300)) ~> merge.in(1)
          b.add(Source(301 to 400)) ~> merge.in(2)
          ClosedShape
      }).run()

      Await.result(result, 3.seconds).toSet should ===((1 to 400).toSet)
    }

    "disallow multiple preferred inputs" in {
      val s = Source(0 to 3)

      (the[IllegalArgumentException] thrownBy {
        val g = RunnableGraph.fromGraph(FlowGraph.create() { implicit b ⇒
          val merge = b.add(MergePreferred[Int](1))

          b.add(s) ~> merge.preferred
          b.add(s) ~> merge.preferred
          b.add(s) ~> merge.in(0)

          merge.out ~> b.add(Sink.head[Int])
          ClosedShape
        })
      }).getMessage should include("[MergePreferred.preferred] is already connected")
    }

  }

}
