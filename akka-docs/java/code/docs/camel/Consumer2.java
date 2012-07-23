package docs.camel;
//#Consumer2
import akka.camel.CamelMessage;
import akka.camel.javaapi.UntypedConsumerActor;

public class Consumer2 extends UntypedConsumerActor {
  public String getEndpointUri() {
    return "jetty:http://localhost:8877/camel/default";
  }

  public void onReceive(Object message) {
    if (message instanceof CamelMessage) {
      CamelMessage camelMessage = (CamelMessage) message;
      String body = camelMessage.getBodyAs(String.class, getCamelContext());
      getSender().tell(String.format("Received message: %s",body));
    } else
      unhandled(message);
  }
}
//#Consumer2
