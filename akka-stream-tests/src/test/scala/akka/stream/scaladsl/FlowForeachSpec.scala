/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.util.control.NoStackTrace

import akka.stream.ActorFlowMaterializer
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }

class FlowForeachSpec extends AkkaSpec {

  implicit val mat = ActorFlowMaterializer()
  import system.dispatcher

  "A Foreach" must {

    "call the procedure for each element" in {
      Source(1 to 3).foreach(testActor ! _) onSuccess {
        case _ ⇒ testActor ! "done"
      }
      expectMsg(1)
      expectMsg(2)
      expectMsg(3)
      expectMsg("done")
    }

    "complete the future for an empty stream" in {
      Source.empty.foreach(testActor ! _) onSuccess {
        case _ ⇒ testActor ! "done"
      }
      expectMsg("done")
    }

    "yield the first error" in {
      val p = StreamTestKit.PublisherProbe[Int]()
      Source(p).foreach(testActor ! _) onFailure {
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