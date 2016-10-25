package docs.camel;
//#Consumer1
import akka.camel.CamelMessage;
import akka.camel.javaapi.UntypedConsumerActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;

public class Consumer1 extends UntypedConsumerActor {
  LoggingAdapter log = Logging.getLogger(getContext().system(), this);

  public String getEndpointUri() {
    return "file:data/input/actor";
  }

  public void onReceive(Object message) {
    if (message instanceof CamelMessage) {
      CamelMessage camelMessage = (CamelMessage) message;
      String body = camelMessage.getBodyAs(String.class, getCamelContext());
      log.info("Received message: {}", body);
    } else
      unhandled(message);
  }
}
//#Consumer1
