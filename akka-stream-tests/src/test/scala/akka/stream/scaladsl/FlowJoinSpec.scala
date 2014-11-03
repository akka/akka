/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.{ Transformer, FlowMaterializer, MaterializerSettings }
import akka.stream.testkit.{ StreamTestKit, AkkaSpec }
import com.typesafe.config.ConfigFactory

class FlowJoinSpec extends AkkaSpec(ConfigFactory.parseString("akka.loglevel=INFO")) {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)
    .withFanOutBuffer(initialSize = 1, maxSize = 16)

  implicit val mat = FlowMaterializer(settings)

  "A Flow using join" must {
    "allow for cycles" in {
      val end = 47 // needs to be odd
      val (even, odd) = (0 to end).partition(_ % 2 == 0)
      val size = even.size + 2 * odd.size
      val result = Set() ++ even ++ odd ++ odd.map(_ * 10)
      val source = Source(0 to end)
      val in = UndefinedSource[Int]
      val out = UndefinedSink[Int]
      val probe = StreamTestKit.SubscriberProbe[Int]()
      val sink = Sink(probe)

      val flow1 = Flow() { implicit b ⇒
        import FlowGraphImplicits._
        val merge = Merge[Int]
        val broadcast = Broadcast[Int]
        source ~> merge ~> broadcast ~> sink
        in ~> merge
        broadcast ~> out
        in -> out
      }

      val flow2 = Flow[Int].transform[Int]("only-odd-by-ten", () ⇒
        new Transformer[Int, Int] {
          private var _isComplete = false
          override def isComplete = _isComplete
          override def onNext(element: Int) = {
            if (element == end * 10) _isComplete = true
            if (element % 2 == 0) Nil else List(element * 10)
          }
        })

      flow1.join(flow2).run()

      val subscription = probe.expectSubscription()

      val collected = (1 to size).map { _ ⇒
        subscription.request(1)
        probe.expectNext()
      }.toSet

      collected should be(result)
      probe.expectComplete()
    }
  }
}
