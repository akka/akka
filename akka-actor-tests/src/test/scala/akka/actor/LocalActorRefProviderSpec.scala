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
      val provider = app.provider

      provider.isInstanceOf[LocalActorRefProvider] must be(true)

      implicit val timeout = Timeout(30 seconds)

      val actors: Seq[Future[ActorRef]] =
        (0 until 100) flatMap { i ⇒ // 100 concurrent runs
          val address = "new-actor" + i
          (1 to 4) map { _ ⇒ Future { provider.actorOf(Props(c ⇒ { case _ ⇒ }), app.guardian, address) } }
        }

      actors.map(_.get).distinct.size must be(100)
    }
  }
}
