package docs.camel.sample.http;

import akka.actor.*;

public class HttpSample {
  public static void main(String[] args) {
    //#HttpExample
    // Create the actors. this can be done in a Boot class so you can
    // run the example in the MicroKernel. just add the below three lines to your boot class.
    ActorSystem system = ActorSystem.create("some-system");
    final ActorRef httpTransformer = system.actorOf(new Props(HttpTransformer.class));

    final ActorRef httpProducer = system.actorOf(new Props(new UntypedActorFactory(){
      public Actor create() {
        return new HttpProducer(httpTransformer);
      }
    }));

    ActorRef httpConsumer = system.actorOf(new Props(new UntypedActorFactory(){
      public Actor create() {
        return new HttpConsumer(httpProducer);
      }
    }));
    //#HttpExample
  }
}
