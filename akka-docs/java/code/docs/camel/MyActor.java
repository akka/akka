package docs.camel;
//#ProducerTemplate
import akka.actor.UntypedActor;
import akka.camel.Camel;
import akka.camel.CamelExtension;
import org.apache.camel.ProducerTemplate;

public class MyActor extends UntypedActor {
  public void onReceive(Object message) {
    Camel camel = CamelExtension.get(getContext().system());
    ProducerTemplate template = camel.template();
    template.sendBody("direct:news", message);
  }
}
//#ProducerTemplate