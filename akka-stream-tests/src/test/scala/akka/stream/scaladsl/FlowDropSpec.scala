/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.testkit._
import akka.testkit.AkkaSpec

class FlowDropSpec extends AkkaSpec with ScriptedTest {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "A Drop" must {

    "drop" in {
      def script(d: Int) = Script(TestConfig.RandomTestRange map { n ⇒ Seq(n) -> (if (n <= d) Nil else Seq(n)) }: _*)
      TestConfig.RandomTestRange foreach { _ ⇒
        val d = Math.min(Math.max(random.nextInt(-10, 60), 0), 50)
        runScript(script(d), settings)(_.drop(d))
      }
    }

    "not drop anything for negative n" in {
      val probe = TestSubscriber.manualProbe[Int]()
      Source(List(1, 2, 3)).drop(-1).to(Sink.fromSubscriber(probe)).run()
      probe.expectSubscription().request(10)
      probe.expectNext(1)
      probe.expectNext(2)
      probe.expectNext(3)
      probe.expectComplete()
    }

  }

}
