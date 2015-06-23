/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }

import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.actor.ActorSubscriberMessage.OnComplete
import akka.stream.actor.ActorSubscriberMessage.OnNext
import akka.stream.impl.RequestMore
import akka.stream.testkit._

class FlowTakeSpec extends AkkaSpec with ScriptedTest {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  muteDeadLetters(classOf[OnNext], OnComplete.getClass, classOf[RequestMore])()

  "A Take" must {

    "take" in {
      def script(d: Int) = Script(TestConfig.RandomTestRange map { n ⇒ Seq(n) -> (if (n > d) Nil else Seq(n)) }: _*)
      TestConfig.RandomTestRange foreach { _ ⇒
        val d = Math.min(Math.max(random.nextInt(-10, 60), 0), 50)
        runScript(script(d), settings)(_.take(d))
      }
    }

    "not take anything for negative n" in {
      val probe = TestSubscriber.manualProbe[Int]()
      Source(List(1, 2, 3)).take(-1).to(Sink(probe)).run()
      probe.expectSubscription().request(10)
      probe.expectComplete()
    }

  }

}
