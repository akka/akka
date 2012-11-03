/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import language.postfixOps

import akka.testkit.AkkaSpec

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class CustomRouteSpec extends AkkaSpec {

  //#custom-router
  import akka.actor.{ ActorRef, Props, SupervisorStrategy }
  import akka.dispatch.Dispatchers

  class MyRouter(target: ActorRef) extends RouterConfig {
    override def createRoute(provider: RouteeProvider): Route = {
      provider.createRoutees(1)

      {
        case (sender, message: String) ⇒ Seq(Destination(sender, target))
        case (sender, message)         ⇒ toAll(sender, provider.routees)
      }
    }
    override def supervisorStrategy = SupervisorStrategy.defaultStrategy
    override def routerDispatcher = Dispatchers.DefaultDispatcherId
  }
  //#custom-router

  "A custom RouterConfig" must {

    "be testable" in {
      //#test-route
      import akka.pattern.ask
      import akka.testkit.ExtractRoute
      import scala.concurrent.Await
      import scala.concurrent.duration._

      val target = system.actorOf(Props.empty)
      val router = system.actorOf(Props.empty.withRouter(new MyRouter(target)))
      val route = ExtractRoute(router)
      val r = Await.result(router.ask(CurrentRoutees)(1 second).
        mapTo[RouterRoutees], 1 second)
      r.routees.size must be(1)
      route(testActor -> "hallo") must be(Seq(Destination(testActor, target)))
      route(testActor -> 12) must be(Seq(Destination(testActor, r.routees.head)))
      //#test-route
    }

  }
}