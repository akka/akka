/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.testkit._
import akka.util.duration._
import akka.dispatch.Future

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class LocalActorRefProviderSpec extends AkkaSpec {
  "An LocalActorRefProvider" must {

    "only create one instance of an actor with a specific address in a concurrent environment" in {
      val impl = system.asInstanceOf[ActorSystemImpl]
      val provider = impl.provider

      provider.isInstanceOf[LocalActorRefProvider] must be(true)

      (0 until 100) foreach { i ⇒ // 100 concurrent runs
        val address = "new-actor" + i
        implicit val timeout = Timeout(5 seconds)
        ((1 to 4) map { _ ⇒ Future { provider.actorOf(impl, Props(c ⇒ { case _ ⇒ }), impl.guardian, address) } }).map(_.get).distinct.size must be(1)
      }
    }
  }
}
