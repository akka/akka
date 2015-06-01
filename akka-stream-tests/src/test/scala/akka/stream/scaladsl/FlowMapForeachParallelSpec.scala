/**
 * Copyright (C) 2014-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.ActorOperationAttributes.supervisionStrategy
import akka.stream.Supervision.resumingDecider
import akka.stream.testkit.Utils._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class FlowMapForeachParallelSpec extends AsyncUnorderedSetup {

  override def functionToTest[T, Out, Mat](flow: Source[Out, Mat], parallelism: Int, f: Out ⇒ T): Source[T, Mat] =
    flow.foreachParallel(parallelism)(f)(system.dispatcher)

  "A Flow with foreachParallel" must {

    commonTests()

    "resume after multiple failures" in assertAllStagesStopped {
      implicit val ec = system.dispatcher

      Await.result(Source(1 to 5)
        .foreachParallel(4)((n: Int) ⇒ {
          if (n < 5) throw new RuntimeException("err") with NoStackTrace
          else n
        })
        .withAttributes(supervisionStrategy(resumingDecider))
        .runWith(Sink.head), 3.seconds) should ===(5)
    }

  }
}
