package docs.camel;
//#RequestProducerTemplate
import akka.actor.AbstractActor;
import akka.camel.Camel;
import akka.camel.CamelExtension;
import org.apache.camel.ProducerTemplate;

public class RequestBodyActor extends AbstractActor {
  @Override
  public Receive createReceive() {
    return receiveBuilder()
      .matchAny(message -> {
        Camel camel = CamelExtension.get(getContext().system());
        ProducerTemplate template = camel.template();
        sender().tell(template.requestBody("direct:news", message), self());
      })
      .build();
  }
}
//#RequestProducerTemplate