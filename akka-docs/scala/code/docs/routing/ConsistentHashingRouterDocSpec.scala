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
  import akka.routing.ConsistentHashingRouter.ConsistentHashableEnvelope

  class Cache extends Actor {
    var cache = Map.empty[String, String]

    def receive = {
      case Entry(key, value) ⇒ cache += (key -> value)
      case Get(key)          ⇒ sender ! cache.get(key)
    }
  }

  case class Get(key: String) extends ConsistentHashable {
    override def consistentHashKey: Any = key
  }

  case class Entry(key: String, value: String)
  case class EntryEnvelope(entry: Entry) extends ConsistentHashableEnvelope {
    override def consistentHashKey: Any = entry.key
    override def message: Any = entry
  }
  //#cache-actor

}

class ConsistentHashingRouterDocSpec extends AkkaSpec with ImplicitSender {

  import ConsistentHashingRouterDocSpec._

  "demonstrate usage of ConsistentHashableRouter" in {

    //#consistent-hashing-router
    import akka.actor.Props
    import akka.routing.ConsistentHashingRouter

    val cache = system.actorOf(Props[Cache].withRouter(ConsistentHashingRouter(10)), "cache")

    cache ! EntryEnvelope(Entry("hello", "HELLO"))
    cache ! EntryEnvelope(Entry("hi", "HI"))

    cache ! Get("hello")
    cache ! Get("hi")
    expectMsg(Some("HELLO"))
    expectMsg(Some("HI"))
    //#consistent-hashing-router
  }

}
