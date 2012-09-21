/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.testkit.ExtractRoute;

public class CustomRouteTest {
  
  static private ActorSystem system;
  
  // only to test compilability
  public void testRoute() {
    final ActorRef ref = system.actorOf(new Props().withRouter(new RoundRobinRouter(1)));
    final scala.Function1<scala.Tuple2<ActorRef, Object>, scala.collection.Iterable<Destination>> route = ExtractRoute.apply(ref);
    route.apply(null);
  }
  
}
