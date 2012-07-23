package docs.camel;
//#Consumer4
import akka.camel.CamelMessage;
import akka.camel.javaapi.UntypedConsumerActor;
import akka.util.Duration;
import akka.util.FiniteDuration;

import java.util.concurrent.TimeUnit;

public class Consumer4 extends UntypedConsumerActor {
  private final static FiniteDuration timeout = Duration.create(500, TimeUnit.MILLISECONDS);

  @Override
  public Duration replyTimeout() {
    return timeout;
  }

  public String getEndpointUri() {
    return "jetty:http://localhost:8877/camel/default";
  }

  public void onReceive(Object message) {
    if (message instanceof CamelMessage) {
      CamelMessage camelMessage = (CamelMessage) message;
      String body = camelMessage.getBodyAs(String.class, getCamelContext());
      getSender().tell(String.format("Hello %s",body));
    } else
      unhandled(message);
  }
}
//#Consumer4