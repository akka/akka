/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import language.postfixOps

import akka.testkit._
import scala.concurrent.util.duration._
import akka.util.Timeout
import akka.dispatch.{ Await, Future }

object LocalActorRefProviderSpec {
  val config = """
    akka {
      actor {
        default-dispatcher {
          executor = "thread-pool-executor"
          thread-pool-executor {
            core-pool-size-min = 16
            core-pool-size-max = 16
          }
        }
      }
    }
  """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class LocalActorRefProviderSpec extends AkkaSpec(LocalActorRefProviderSpec.config) {
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

    "throw suitable exceptions for malformed actor names" in {
      intercept[InvalidActorNameException](system.actorOf(Props.empty, null)).getMessage.contains("null") must be(true)
      intercept[InvalidActorNameException](system.actorOf(Props.empty, "")).getMessage.contains("empty") must be(true)
      intercept[InvalidActorNameException](system.actorOf(Props.empty, "$hallo")).getMessage.contains("conform") must be(true)
      intercept[InvalidActorNameException](system.actorOf(Props.empty, "a%")).getMessage.contains("conform") must be(true)
      intercept[InvalidActorNameException](system.actorOf(Props.empty, "a?")).getMessage.contains("conform") must be(true)
      intercept[InvalidActorNameException](system.actorOf(Props.empty, "üß")).getMessage.contains("conform") must be(true)
    }

  }
}
