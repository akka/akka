package akka.actor;

public class JavaAPITestActor extends UntypedAbstractActor {
  public static String ANSWER = "got it!";

  public void onReceive(Object msg) {
    getSender().tell(ANSWER, getSelf());
    getContext().getChildren();
  }
}
