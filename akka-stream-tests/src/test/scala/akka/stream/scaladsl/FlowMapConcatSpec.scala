/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.duration._
import akka.stream.ActorMaterializerSettings
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.stream.ActorMaterializer

class FlowMapConcatSpec extends AkkaSpec with ScriptedTest {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  "A MapConcat" must {

    "map and concat" in {
      val script = Script(
        Seq(0) -> Seq(),
        Seq(1) -> Seq(1),
        Seq(2) -> Seq(2, 2),
        Seq(3) -> Seq(3, 3, 3),
        Seq(2) -> Seq(2, 2),
        Seq(1) -> Seq(1))
      TestConfig.RandomTestRange foreach (_ ⇒ runScript(script, settings)(_.mapConcat(x ⇒ (1 to x) map (_ ⇒ x))))
    }

    "map and concat grouping with slow downstream" in {
      val settings = ActorMaterializerSettings(system)
        .withInputBuffer(initialSize = 2, maxSize = 2)
      implicit val materializer = ActorMaterializer(settings)
      assertAllStagesStopped {
        val s = TestSubscriber.manualProbe[Int]
        val input = (1 to 20).grouped(5).toList
        Source(input).mapConcat(identity).map(x ⇒ { Thread.sleep(10); x }).runWith(Sink.fromSubscriber(s))
        val sub = s.expectSubscription()
        sub.request(100)
        for (i ← 1 to 20) s.expectNext(i)
        s.expectComplete()
      }
    }

  }

}
