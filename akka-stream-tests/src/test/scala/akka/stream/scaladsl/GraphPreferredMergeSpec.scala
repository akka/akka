/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.stream.scaladsl.FlowGraphImplicits._
import akka.stream.testkit.TwoStreamsSetup

class GraphPreferredMergeSpec extends TwoStreamsSetup {

  override type Outputs = Int
  val op = MergePreferred[Int]
  override def operationUnderTestLeft = op
  override def operationUnderTestRight = op

  "preferred merge" must {

    commonTests()

    "prefer selected input more than others" in {
      val numElements = 10000

      val preferred = Source(Stream.fill(numElements)(1))
      val aux1, aux2, aux3 = Source(Stream.fill(numElements)(2))
      val sink = Sink.head[Seq[Int]]

      val g = FlowGraph { implicit b ⇒
        val merge = MergePreferred[Int]
        preferred ~> merge.preferred ~> Flow[Int].grouped(numElements * 2) ~> sink
        aux1 ~> merge
        aux2 ~> merge
        aux3 ~> merge
      }.run()

      Await.result(g.get(sink), 3.seconds).filter(_ == 1).size should be(numElements)
    }

    "disallow multiple preferred inputs" in {
      val s1, s2, s3 = Source(0 to 3)

      (the[IllegalArgumentException] thrownBy {
        val g = FlowGraph { implicit b ⇒
          val merge = MergePreferred[Int]

          s1 ~> merge.preferred ~> Sink.head[Int]
          s2 ~> merge.preferred
          s3 ~> merge
        }
      }).getMessage should include("must have at most one preferred edge")
    }

  }

}
