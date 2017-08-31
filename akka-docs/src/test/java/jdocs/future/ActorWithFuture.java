package jdocs.future;

//#context-dispatcher
import akka.actor.AbstractActor;
import akka.dispatch.Futures;

public class ActorWithFuture extends AbstractActor {
  ActorWithFuture(){
    Futures.future(() -> "hello", getContext().dispatcher());
  }

  @Override
  public Receive createReceive() {
    return AbstractActor.emptyBehavior();
  }
}
// #context-dispatcher
