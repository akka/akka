/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.testkit._
import akka.util.duration._
import akka.testkit.Testing.sleepFor
import akka.actor.Actor._

import java.util.concurrent.{ TimeUnit, CountDownLatch }

object LocalActorRefProviderSpec {

  class NewActor extends Actor {
    def receive = {
      case _ ⇒ {}
    }
  }
}

class LocalActorRefProviderSpec extends WordSpec with MustMatchers {
  import akka.actor.LocalActorRefProviderSpec._

  "An LocalActorRefProvider" must {

    "only create one instance of an actor with a specific address in a concurrent environment" in {
      val provider = new LocalActorRefProvider

      for (i ← 0 until 100) { // 100 concurrent runs
        spawn {
          val latch = new CountDownLatch(4)

          var a1: Option[ActorRef] = None
          var a2: Option[ActorRef] = None
          var a3: Option[ActorRef] = None
          var a4: Option[ActorRef] = None

          val address = "new-actor" + i

          spawn {
            a1 = provider.actorOf(Props(creator = () ⇒ new NewActor), address, false)
            latch.countDown()
          }
          spawn {
            a2 = provider.actorOf(Props(creator = () ⇒ new NewActor), address, false)
            latch.countDown()
          }
          spawn {
            a3 = provider.actorOf(Props(creator = () ⇒ new NewActor), address, false)
            latch.countDown()
          }
          spawn {
            a4 = provider.actorOf(Props(creator = () ⇒ new NewActor), address, false)
            latch.countDown()
          }

          latch.await(5, TimeUnit.SECONDS) must be === true

          a1.isDefined must be(true)
          a2.isDefined must be(true)
          a3.isDefined must be(true)
          a4.isDefined must be(true)
          (a1 == a2) must be(true)
          (a1 == a2) must be(true)
          (a1 == a4) must be(true)
        }
      }
    }
  }
}
