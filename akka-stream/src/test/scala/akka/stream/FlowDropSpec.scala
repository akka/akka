/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.ScriptedTest
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import akka.stream.testkit.StreamTestKit
import akka.stream.scaladsl.Flow

class FlowDropSpec extends AkkaSpec with ScriptedTest {

  val settings = MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 16,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 16,
    dispatcher = "akka.test.stream-dispatcher")

  implicit val materializer = FlowMaterializer(settings)

  "A Drop" must {

    "drop" in {
      def script(d: Int) = Script((1 to 50) map { n ⇒ Seq(n) -> (if (n <= d) Nil else Seq(n)) }: _*)
      (1 to 50) foreach { _ ⇒
        val d = Math.min(Math.max(random.nextInt(-10, 60), 0), 50)
        runScript(script(d), settings)(_.drop(d))
      }
    }

    "not drop anything for negative n" in {
      val probe = StreamTestKit.SubscriberProbe[Int]()
      Flow(List(1, 2, 3)).drop(-1).produceTo(probe)
      probe.expectSubscription().request(10)
      probe.expectNext(1)
      probe.expectNext(2)
      probe.expectNext(3)
      probe.expectComplete()
    }

  }

}