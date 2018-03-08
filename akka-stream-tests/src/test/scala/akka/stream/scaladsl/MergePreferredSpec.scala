/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream._
import akka.stream.testkit.TwoStreamsSetup

import scala.concurrent.Await
import scala.concurrent.duration._

class MergePreferredSpec extends TwoStreamsSetup {
  import GraphDSL.Implicits._

  override type Outputs = Int

  override def fixture(b: GraphDSL.Builder[_]): Fixture = new Fixture(b) {
    val merge = b.add(MergePreferred[Outputs](1))

    override def left: Inlet[Outputs] = merge.preferred
    override def right: Inlet[Outputs] = merge.in(0)
    override def out: Outlet[Outputs] = merge.out

  }

  "preferred merge" must {

    "prefer selected input more than others" in {
      val numElements = 10000

      val preferred = Source(Stream.fill(numElements)(1))
      val aux = Source(Stream.fill(numElements)(2))

      val result = MergePreferred(preferred, aux)
        .grouped(numElements * 2)
        .runWith(Sink.head[Seq[Int]])

      Await.result(result, 3.seconds).filter(_ == 1).size should be(numElements)
    }

  }

}
