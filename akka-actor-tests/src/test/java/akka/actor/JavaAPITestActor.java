package akka.actor;

public class JavaAPITestActor extends UntypedActor {
  public void onReceive(Object msg) {
    getSender().tell("got it!", getSelf());
    getContext().getChildren();
  }
}
