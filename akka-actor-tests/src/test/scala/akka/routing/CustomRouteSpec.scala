/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing

import akka.testkit.AkkaSpec
import akka.actor.Props
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy
import akka.dispatch.Dispatchers
import akka.testkit.ExtractRoute

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class CustomRouteSpec extends AkkaSpec {
  
  class MyRouter extends RouterConfig {
    override def createRoute(p: Props, prov: RouteeProvider): Route = {
      prov.createAndRegisterRoutees(p, 1, Nil)
      
      {
        case (sender, message) => toAll(sender, prov.routees)
      }
    }
    override def supervisorStrategy = SupervisorStrategy.defaultStrategy
    override def routerDispatcher = Dispatchers.DefaultDispatcherId
  }
  
  "A custom RouterConfig" must {
    
    "be testable" in {
      val router = system.actorOf(Props.empty.withRouter(new MyRouter))
      val route = ExtractRoute(router)
      route(testActor -> "hallo").size must be(1)
    }
    
  }
}