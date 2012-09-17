/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.routing

import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender

object ConsistentHashingRouterDocSpec {

  //#cache-actor
  import akka.actor.Actor
  import akka.routing.ConsistentHashingRouter.ConsistentHashable

  class Cache extends Actor {
    var cache = Map.empty[String, String]

    def receive = {
      case Entry(key, value) ⇒ cache += (key -> value)
      case Get(key)          ⇒ sender ! cache.get(key)
      case Evict(key)        ⇒ cache -= key
    }
  }

  case class Evict(key: String)

  case class Get(key: String) extends ConsistentHashable {
    override def consistentHashKey: Any = key
  }

  case class Entry(key: String, value: String)
  //#cache-actor

}

class ConsistentHashingRouterDocSpec extends AkkaSpec with ImplicitSender {

  import ConsistentHashingRouterDocSpec._

  "demonstrate usage of ConsistentHashableRouter" in {

    //#consistent-hashing-router
    import akka.actor.Props
    import akka.routing.ConsistentHashingRouter
    import akka.routing.ConsistentHashingRouter.ConsistentHashRoute
    import akka.routing.ConsistentHashingRouter.ConsistentHashableEnvelope

    def consistentHashRoute: ConsistentHashRoute = {
      case Evict(key) ⇒ key
    }

    val cache = system.actorOf(Props[Cache].withRouter(ConsistentHashingRouter(10,
      consistentHashRoute = consistentHashRoute)), name = "cache")

    cache ! ConsistentHashableEnvelope(
      message = Entry("hello", "HELLO"), consistentHashKey = "hello")
    cache ! ConsistentHashableEnvelope(
      message = Entry("hi", "HI"), consistentHashKey = "hi")

    cache ! Get("hello")
    expectMsg(Some("HELLO"))

    cache ! Get("hi")
    expectMsg(Some("HI"))

    cache ! Evict("hi")
    cache ! Get("hi")
    expectMsg(None)

    //#consistent-hashing-router
  }

}
