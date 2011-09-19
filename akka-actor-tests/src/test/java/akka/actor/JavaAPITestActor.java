package akka.actor;

public class JavaAPITestActor extends UntypedActor {
    public void onReceive(Object msg) {
        tryReply("got it!");
    }
}
