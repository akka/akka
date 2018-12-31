/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.camel;

import akka.actor.*;
import akka.testkit.javadsl.TestKit;

public class OnRouteResponseTestBase {

  public void onRouteResponse(){
    //#RouteResponse
    ActorSystem system = ActorSystem.create("some-system");
    Props receiverProps = Props.create(ResponseReceiver.class);
    final ActorRef receiver = system.actorOf(receiverProps,"responseReceiver");
    ActorRef forwardResponse = system.actorOf(Props.create(
        Forwarder.class, "http://localhost:8080/news/akka", receiver));
    // the Forwarder sends out a request to the web page and forwards the response to
    // the ResponseReceiver
    forwardResponse.tell("some request", ActorRef.noSender());
    //#RouteResponse
    system.stop(receiver);
    system.stop(forwardResponse);
    TestKit.shutdownActorSystem(system);
  }
}
