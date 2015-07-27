/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.actor.ActorSystem
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.testkit._
import com.typesafe.config.ConfigFactory
import scala.concurrent.Await
import scala.concurrent.duration._

class FlowSlidingSpec extends AkkaSpec {
  import system.dispatcher
  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val mat = ActorMaterializer(settings)

  "Sliding" must {
    "work with n = 3, step = 1" in assertAllStagesStopped {
      Source(1 to 6).sliding(n = 3, step = 1).runForeach(testActor ! _) onSuccess {
        case _ ⇒ testActor ! "done"
      }
      expectMsg(Vector(1, 2, 3))
      expectMsg(Vector(2, 3, 4))
      expectMsg(Vector(3, 4, 5))
      expectMsg(Vector(4, 5, 6))
      expectMsg("done")
    }

    "work with n = 3, step = 1, 7 elements" in assertAllStagesStopped {
      Source(1 to 7).sliding(n = 3, step = 1).runForeach(testActor ! _) onSuccess {
        case _ ⇒ testActor ! "done"
      }
      expectMsg(Vector(1, 2, 3))
      expectMsg(Vector(2, 3, 4))
      expectMsg(Vector(3, 4, 5))
      expectMsg(Vector(4, 5, 6))
      expectMsg(Vector(5, 6, 7))
      expectMsg("done")
    }

    "work with n = 3, step = 2" in assertAllStagesStopped {
      Source(1 to 6).sliding(n = 3, step = 2).runForeach(testActor ! _) onSuccess {
        case _ ⇒ testActor ! "done"
      }
      expectMsg(Vector(1, 2, 3))
      expectMsg(Vector(3, 4, 5))
      expectMsg(Vector(5, 6))
      expectMsg("done")
    }

    "work with n = 3, step = 2, complete group" in assertAllStagesStopped {
      Source(1 to 7).sliding(n = 3, step = 2).runForeach(testActor ! _) onSuccess {
        case _ ⇒ testActor ! "done"
      }
      expectMsg(Vector(1, 2, 3))
      expectMsg(Vector(3, 4, 5))
      expectMsg(Vector(5, 6, 7))
      expectMsg("done")
    }

    "work with n = 3, step = 3" in assertAllStagesStopped {
      Source(1 to 6).sliding(n = 3, step = 3).runForeach(testActor ! _) onSuccess {
        case _ ⇒ testActor ! "done"
      }
      expectMsg(Vector(1, 2, 3))
      expectMsg(Vector(4, 5, 6))
      expectMsg("done")
    }

    "work with n = 2, step = 3" in assertAllStagesStopped {
      Source(1 to 6).sliding(n = 2, step = 3).runForeach(testActor ! _) onSuccess {
        case _ ⇒ testActor ! "done"
      }
      expectMsg(Vector(1, 2))
      expectMsg(Vector(4, 5))
      expectMsg("done")
    }

    "work with n = 2, step = 1" in assertAllStagesStopped {
      Source(1 to 6).sliding(n = 2, step = 1).runForeach(testActor ! _) onSuccess {
        case _ ⇒ testActor ! "done"
      }
      expectMsg(Vector(1, 2))
      expectMsg(Vector(2, 3))
      expectMsg(Vector(3, 4))
      expectMsg(Vector(4, 5))
      expectMsg(Vector(5, 6))
      expectMsg("done")
    }

    "work with n = 3, step = 4" in assertAllStagesStopped {
      Source(1 to 12).sliding(n = 3, step = 4).runForeach(testActor ! _) onSuccess {
        case _ ⇒ testActor ! "done"
      }
      expectMsg(Vector(1, 2, 3))
      expectMsg(Vector(5, 6, 7))
      expectMsg(Vector(9, 10, 11))
      expectMsg("done")
    }

    "work with n = 3, step = 6" in assertAllStagesStopped {
      Source(1 to 12).sliding(n = 3, step = 6).runForeach(testActor ! _) onSuccess {
        case _ ⇒ testActor ! "done"
      }
      expectMsg(Vector(1, 2, 3))
      expectMsg(Vector(7, 8, 9))
      expectMsg("done")
    }

    "work with n = 3, step = 10, incomplete group" in assertAllStagesStopped {
      Source(1 to 12).sliding(n = 3, step = 10).runForeach(testActor ! _) onSuccess {
        case _ ⇒ testActor ! "done"
      }
      expectMsg(Vector(1, 2, 3))
      expectMsg(Vector(11, 12))
      expectMsg("done")
    }

    "work with empty sources" in assertAllStagesStopped {
      Source.empty.sliding(1).runForeach(testActor ! _) onSuccess {
        case _ ⇒ testActor ! "done"
      }
      expectMsg("done")
    }
  }
}
