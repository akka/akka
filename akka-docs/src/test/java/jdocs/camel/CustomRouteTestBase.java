/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.camel;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.camel.Camel;
import akka.camel.CamelExtension;
import akka.testkit.javadsl.TestKit;

public class CustomRouteTestBase {
  public void customRoute() throws Exception {
    // #CustomRoute
    ActorSystem system = ActorSystem.create("some-system");
    try {
      Camel camel = CamelExtension.get(system);
      ActorRef responder = system.actorOf(Props.create(Responder.class), "TestResponder");
      camel.context().addRoutes(new CustomRouteBuilder(responder));
      // #CustomRoute
    } finally {
      TestKit.shutdownActorSystem(system);
    }
  }
}
