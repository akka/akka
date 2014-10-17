/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.stream.actor.ActorSubscriberMessage.OnComplete
import akka.stream.actor.ActorSubscriberMessage.OnNext
import akka.stream.impl.RequestMore
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit2.ScriptedTest
import akka.stream.testkit.StreamTestKit
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import akka.stream.MaterializerSettings

class FlowTakeSpec extends AkkaSpec with ScriptedTest {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)
    .withFanOutBuffer(initialSize = 1, maxSize = 16)

  implicit val materializer = FlowMaterializer(settings)

  muteDeadLetters(classOf[OnNext], OnComplete.getClass, classOf[RequestMore])()

  "A Take" must {

    "take" in {
      def script(d: Int) = Script((1 to 50) map { n ⇒ Seq(n) -> (if (n > d) Nil else Seq(n)) }: _*)
      (1 to 50) foreach { _ ⇒
        val d = Math.min(Math.max(random.nextInt(-10, 60), 0), 50)
        runScript(script(d), settings)(_.take(d))
      }
    }

    "not take anything for negative n" in {
      val probe = StreamTestKit.SubscriberProbe[Int]()
      Source(List(1, 2, 3)).take(-1).connect(Sink(probe)).run()
      probe.expectSubscription().request(10)
      probe.expectComplete()
    }

  }

}