/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.duration._
import java.util.concurrent.ThreadLocalRandom.{ current ⇒ random }
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.actor.ActorSubscriberMessage.OnComplete
import akka.stream.actor.ActorSubscriberMessage.OnNext
import akka.stream.impl.RequestMore
import akka.stream.testkit._

class FlowTakeSpec extends StreamSpec with ScriptedTest {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  muteDeadLetters(classOf[OnNext], OnComplete.getClass, classOf[RequestMore])()

  "A Take" must {

    "take" in {
      def script(d: Int) = Script(TestConfig.RandomTestRange map { n ⇒ Seq(n) → (if (n > d) Nil else Seq(n)) }: _*)
      TestConfig.RandomTestRange foreach { _ ⇒
        val d = Math.min(Math.max(random.nextInt(-10, 60), 0), 50)
        runScript(script(d), settings)(_.take(d))
      }
    }

    "not take anything for negative n" in {
      val probe = TestSubscriber.manualProbe[Int]()
      Source(List(1, 2, 3)).take(-1).to(Sink.fromSubscriber(probe)).run()
      probe.expectSubscription().request(10)
      probe.expectComplete()
    }

    "complete eagerly when zero or less is taken independently of upstream completion" in {
      Await.result(Source.maybe[Int].take(0).runWith(Sink.ignore), 3.second)
      Await.result(Source.maybe[Int].take(-1).runWith(Sink.ignore), 3.second)
    }

  }

}
