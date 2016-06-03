/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ Supervision, ActorAttributes, ActorMaterializer, ActorMaterializerSettings }
import akka.stream.testkit.Utils._
import akka.stream.testkit._
import scala.util.control.NoStackTrace
import akka.testkit.AkkaSpec

class FlowMapConcatSpec extends AkkaSpec with ScriptedTest {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)
  implicit val materializer = ActorMaterializer(settings)

  "A MapConcat" must {

    "map and concat" in {
      val script = Script(
        Seq(0) → Seq(),
        Seq(1) → Seq(1),
        Seq(2) → Seq(2, 2),
        Seq(3) → Seq(3, 3, 3),
        Seq(2) → Seq(2, 2),
        Seq(1) → Seq(1))
      TestConfig.RandomTestRange foreach (_ ⇒ runScript(script, settings)(_.mapConcat(x ⇒ (1 to x) map (_ ⇒ x))))
    }

    "map and concat grouping with slow downstream" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]
      val input = (1 to 20).grouped(5).toList
      Source(input).mapConcat(identity).map(x ⇒ { Thread.sleep(10); x }).runWith(Sink.fromSubscriber(s))
      val sub = s.expectSubscription()
      sub.request(100)
      for (i ← 1 to 20) s.expectNext(i)
      s.expectComplete()
    }

    "be able to resume" in assertAllStagesStopped {
      val ex = new Exception("TEST") with NoStackTrace

      Source(1 to 5).mapConcat(x ⇒ if (x == 3) throw ex else List(x))
        .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider))
        .runWith(TestSink.probe[Int])
        .request(4).expectNext(1, 2, 4, 5)
        .expectComplete()
    }

  }

}
