/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.{ FlowMaterializer, MaterializerSettings }
import akka.stream.testkit.{ StreamTestKit, AkkaSpec }
import com.typesafe.config.ConfigFactory
import scala.concurrent.Await
import scala.concurrent.duration._

class FlowJoinSpec extends AkkaSpec(ConfigFactory.parseString("akka.loglevel=INFO")) {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val mat = FlowMaterializer(settings)

  "A Flow using join" must {
    "allow for cycles" in {
      val end = 47
      val (even, odd) = (0 to end).partition(_ % 2 == 0)
      val size = even.size + 2 * odd.size
      val result = Set() ++ even ++ odd ++ odd.map(_ * 10)
      val source = Source(0 to end)
      val in = UndefinedSource[Int]
      val out = UndefinedSink[Int]
      val probe = StreamTestKit.SubscriberProbe[Int]()
      val sink = Sink.head[Seq[Int]]

      val flow1 = Flow() { implicit b ⇒
        import FlowGraphImplicits._
        val merge = Merge[Int]
        val broadcast = Broadcast[Int]
        source ~> merge ~> broadcast ~> Flow[Int].grouped(1000) ~> sink
        in ~> merge
        broadcast ~> out
        in -> out
      }

      val flow2 = Flow[Int].filter(_ % 2 == 1).map(_ * 10).take((end + 1) / 2)

      val mm = flow1.join(flow2).run()
      Await.result(mm get sink, 1.second).toSet should be(result)
    }
  }
}
