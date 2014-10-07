/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.stream.scaladsl2.FlowGraphImplicits._
import akka.stream.testkit2.TwoStreamsSetup

import scala.concurrent.Await
import scala.concurrent.duration._

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
      val drain = FutureDrain[Seq[Int]]

      val g = FlowGraph { implicit b ⇒
        val merge = MergePreferred[Int]
        preferred ~> merge.preferred ~> Flow[Int].grouped(numElements * 2) ~> drain
        aux1 ~> merge
        aux2 ~> merge
        aux3 ~> merge
      }.run()

      Await.result(g.materializedDrain(drain), 3.seconds).filter(_ == 1).size should be(numElements)
    }

    "disallow multiple preferred inputs" in {
      val s1, s2, s3 = Source(0 to 3)

      (the[IllegalArgumentException] thrownBy {
        val g = FlowGraph { implicit b ⇒
          val merge = MergePreferred[Int]

          s1 ~> merge.preferred ~> FutureDrain[Int]
          s2 ~> merge.preferred
          s3 ~> merge
        }
      }).getMessage should include("must have at most one preferred edge")
    }

  }

}
