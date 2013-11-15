package sample.camel.http;

import akka.actor.*;

public class HttpSample {
  public static void main(String[] args) {
    ActorSystem system = ActorSystem.create("some-system");

    final ActorRef httpTransformer = system.actorOf(Props.create(HttpTransformer.class));

    final ActorRef httpProducer = system.actorOf(Props.create(HttpProducer.class, httpTransformer));

    final ActorRef httpConsumer = system.actorOf(Props.create(HttpConsumer.class, httpProducer));
  }
}
