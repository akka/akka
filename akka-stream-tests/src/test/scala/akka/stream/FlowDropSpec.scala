/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.{ AkkaSpec, ScriptedTest }

import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import akka.stream.testkit.StreamTestKit
import akka.stream.scaladsl.Flow

class FlowDropSpec extends AkkaSpec with ScriptedTest {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)
    .withFanOutBuffer(initialSize = 1, maxSize = 16)

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