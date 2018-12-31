/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern.extended

import akka.actor._
import akka.testkit.AkkaSpec
import akka.util.Timeout
import scala.concurrent.duration._

object ExplicitAskSpec {
  case class Request(respondTo: ActorRef)
  case class Response(sentFrom: ActorRef)
}

class ExplicitAskSpec extends AkkaSpec {
  import ExplicitAskSpec._

  "The “ask” pattern with explicit sender" must {
    "allow to access an explicit reference to actor to respond to" in {
      implicit val timeout = Timeout(5.seconds)

      val target = system.actorOf(Props(new Actor {
        def receive = {
          case Request(respondTo) ⇒ respondTo ! Response(self)
        }
      }))

      val f = target ? (respondTo ⇒ Request(respondTo))
      f.futureValue should ===(Response(target))
    }

    "work for ActorSelection" in {
      implicit val timeout = Timeout(5.seconds)

      val target = system.actorOf(Props(new Actor {
        def receive = {
          case Request(respondTo) ⇒ respondTo ! Response(self)
        }
      }), "select-echo")

      val selection = system.actorSelection("/user/select-echo")
      val f = selection ? (respondTo ⇒ Request(respondTo))
      f.futureValue should ===(Response(target))
    }
  }

}
