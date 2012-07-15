package docs.camel;
//#RequestProducerTemplate
import akka.actor.UntypedActor;
import akka.camel.Camel;
import akka.camel.CamelExtension;
import org.apache.camel.ProducerTemplate;

public class RequestBodyActor extends UntypedActor {
  public void onReceive(Object message) {
    Camel camel = CamelExtension.get(getContext().system());
    ProducerTemplate template = camel.template();
    getSender().tell(template.requestBody("direct:news", message));
  }
}
//#RequestProducerTemplate