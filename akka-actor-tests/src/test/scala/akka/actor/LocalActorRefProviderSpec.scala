/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.testkit._
import akka.util.duration._
import akka.util.Timeout
import akka.dispatch.{ Await, Future }

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class LocalActorRefProviderSpec extends AkkaSpec {
  "An LocalActorRefProvider" must {

    "find actor refs using actorFor" in {
      val a = system.actorOf(Props(ctx ⇒ { case _ ⇒ }))
      val b = system.actorFor(a.path)
      a must be === b
    }

  }

  "An ActorRefFactory" must {

    "only create one instance of an actor with a specific address in a concurrent environment" in {
      val impl = system.asInstanceOf[ActorSystemImpl]
      val provider = impl.provider

      provider.isInstanceOf[LocalActorRefProvider] must be(true)

      for (i ← 0 until 100) {
        val address = "new-actor" + i
        implicit val timeout = Timeout(5 seconds)
        val actors = for (j ← 1 to 4) yield Future(system.actorOf(Props(c ⇒ { case _ ⇒ }), address))
        val set = Set() ++ actors.map(a ⇒ Await.ready(a, timeout.duration).value match {
          case Some(Right(a: ActorRef))                  ⇒ 1
          case Some(Left(ex: InvalidActorNameException)) ⇒ 2
          case x                                         ⇒ x
        })
        set must be === Set(1, 2)
      }
    }

    "only create one instance of an actor from within the same message invocation" in {
      val supervisor = system.actorOf(Props(new Actor {
        def receive = {
          case "" ⇒
            val a, b = context.actorOf(Props.empty, "duplicate")
        }
      }))
      EventFilter[InvalidActorNameException](occurrences = 1) intercept {
        supervisor ! ""
      }
    }

  }
}
