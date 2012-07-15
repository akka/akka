package docs.camel;
//#Producer
import akka.camel.javaapi.UntypedProducerActor;

public class Orders extends UntypedProducerActor{
  public String getEndpointUri() {
    return "jms:queue:Orders";
  }
}
//#Producer