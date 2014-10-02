/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.stream.testkit.{ AkkaSpec, StreamTestKit }

import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import scala.util.control.NoStackTrace

class FlowForeachSpec extends AkkaSpec {

  implicit val mat = FlowMaterializer()
  import system.dispatcher

  "A Foreach" must {

    "call the procedure for each element" in {
      val foreachDrain = ForeachDrain[Int](testActor ! _)
      val mf = Source(1 to 3).connect(foreachDrain).run()
      foreachDrain.future(mf).onSuccess {
        case _ ⇒ testActor ! "done"
      }
      expectMsg(1)
      expectMsg(2)
      expectMsg(3)
      expectMsg("done")
    }

    "complete the future for an empty stream" in {
      val foreachDrain = ForeachDrain[Int](testActor ! _)
      val mf = Source(Nil).connect(foreachDrain).run()
      foreachDrain.future(mf).onSuccess {
        case _ ⇒ testActor ! "done"
      }
      expectMsg("done")
    }

    "yield the first error" in {
      val p = StreamTestKit.PublisherProbe[Int]()
      val foreachDrain = ForeachDrain[Int](testActor ! _)
      val mf = Source(p).connect(foreachDrain).run()
      foreachDrain.future(mf).onFailure {
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