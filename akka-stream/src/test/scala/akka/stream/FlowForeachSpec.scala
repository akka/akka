/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.ScriptedTest
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import akka.stream.scaladsl.Flow
import akka.stream.testkit.StreamTestKit
import scala.util.control.NoStackTrace

class FlowForeachSpec extends AkkaSpec {

  implicit val mat = FlowMaterializer(MaterializerSettings(dispatcher = "akka.test.stream-dispatcher"))
  import system.dispatcher

  "A Foreach" must {

    "call the procedure for each element" in {
      Flow(1 to 3).foreach(testActor ! _).onSuccess {
        case _ ⇒ testActor ! "done"
      }
      expectMsg(1)
      expectMsg(2)
      expectMsg(3)
      expectMsg("done")
    }

    "complete the future for an empty stream" in {
      Flow(Nil).foreach(testActor ! _).onSuccess {
        case _ ⇒ testActor ! "done"
      }
      expectMsg("done")
    }

    "yield the first error" in {
      val p = StreamTestKit.PublisherProbe[Int]()
      Flow(p).foreach(testActor ! _).onFailure {
        case ex ⇒ testActor ! ex
      }
      val proc = p.expectSubscription
      proc.expectRequest()
      val ex = new RuntimeException("ex") with NoStackTrace
      proc.sendError(ex)
      expectMsg(ex)
    }

  }

}