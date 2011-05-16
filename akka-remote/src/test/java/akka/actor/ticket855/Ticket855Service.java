package akka.actor.ticket855;

import akka.dispatch.Future;

public interface Ticket855Service {
    Future<String> callAndWait(String message, Long wait);
}
