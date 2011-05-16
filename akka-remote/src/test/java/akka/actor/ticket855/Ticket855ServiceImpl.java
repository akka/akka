package akka.actor.ticket855;
import akka.actor.TypedActor;
import akka.dispatch.Future;

public class Ticket855ServiceImpl extends TypedActor implements Ticket855Service  {

    public Ticket855ServiceImpl() {}

    public Future<String> callAndWait(final String message, final Long wait) {
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return future("x: " + message);
    }
}
