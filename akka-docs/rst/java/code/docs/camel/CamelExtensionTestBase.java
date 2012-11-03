package docs.camel;

import akka.actor.ActorSystem;
import akka.camel.Camel;
import akka.camel.CamelExtension;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.junit.Test;

public class CamelExtensionTestBase {
  @Test
  public void getCamelExtension() {
    //#CamelExtension
    ActorSystem system = ActorSystem.create("some-system");
    Camel camel = CamelExtension.get(system);
    CamelContext camelContext = camel.context();
    ProducerTemplate producerTemplate = camel.template();
    //#CamelExtension
    system.shutdown();
  }
  public void addActiveMQComponent() {
    //#CamelExtensionAddComponent
    ActorSystem system = ActorSystem.create("some-system");
    Camel camel = CamelExtension.get(system);
    CamelContext camelContext = camel.context();
    // camelContext.addComponent("activemq", ActiveMQComponent.activeMQComponent(
    //   "vm://localhost?broker.persistent=false"));
    //#CamelExtensionAddComponent
    system.shutdown();
  }

}
