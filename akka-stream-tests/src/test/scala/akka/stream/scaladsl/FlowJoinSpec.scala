/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.{ ActorFlowMaterializer, ActorFlowMaterializerSettings }
import akka.stream.testkit._
import com.typesafe.config.ConfigFactory
import scala.concurrent.Await
import scala.concurrent.duration._

class FlowJoinSpec extends AkkaSpec(ConfigFactory.parseString("akka.loglevel=INFO")) {

  val settings = ActorFlowMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val mat = ActorFlowMaterializer(settings)

  "A Flow using join" must {
    "allow for cycles" in {
      val end = 47
      val (even, odd) = (0 to end).partition(_ % 2 == 0)
      val result = Set() ++ even ++ odd ++ odd.map(_ * 10)
      val source = Source(0 to end)
      val probe = TestSubscriber.manualProbe[Seq[Int]]()

      val flow1 = Flow() { implicit b ⇒
        import FlowGraph.Implicits._
        val merge = b.add(Merge[Int](2))
        val broadcast = b.add(Broadcast[Int](2))
        source ~> merge.in(0)
        merge.out ~> broadcast.in
        broadcast.out(0).grouped(1000) ~> Sink(probe)

        (merge.in(1), broadcast.out(1))
      }

      val flow2 = Flow[Int].filter(_ % 2 == 1).map(_ * 10).take((end + 1) / 2)

      val mm = flow1.join(flow2).run()

      val sub = probe.expectSubscription()
      sub.request(1)
      probe.expectNext().toSet should be(result)
      sub.cancel()
    }
  }
}
