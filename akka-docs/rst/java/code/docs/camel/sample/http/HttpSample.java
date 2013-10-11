package docs.camel.sample.http;

import akka.actor.*;

public class HttpSample {
  public static void main(String[] args) {
    //#HttpExample
    // Create the actors. this can be done in a Boot class so you can
    // run the example in the MicroKernel. Just add the three lines below
    // to your boot class.
    ActorSystem system = ActorSystem.create("some-system");
    
    final ActorRef httpTransformer = system.actorOf(
        Props.create(HttpTransformer.class));

    final ActorRef httpProducer = system.actorOf(
        Props.create(HttpProducer.class, httpTransformer));
    
    final ActorRef httpConsumer = system.actorOf(
        Props.create(HttpConsumer.class, httpProducer));
    //#HttpExample
  }
}
