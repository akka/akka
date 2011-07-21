package akka.actor;

public class JavaAPITestActor extends UntypedActor {
    public void onReceive(Object msg) {
        getContext().tryReply("got it!");
    }
}
