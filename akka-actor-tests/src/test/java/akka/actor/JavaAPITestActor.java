package akka.actor;

public class JavaAPITestActor extends UntypedActor {
  public static String ANSWER = "got it!";

  public void onReceive(Object msg) {
    getSender().tell(ANSWER, getSelf());
    getContext().getChildren();
  }
}
