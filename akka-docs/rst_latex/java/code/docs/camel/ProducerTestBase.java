package docs.camel;

import java.util.HashMap;
import java.util.Map;

import scala.concurrent.Future;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.camel.CamelMessage;
import akka.pattern.Patterns;

public class ProducerTestBase {
  public void tellJmsProducer() {
    //#TellProducer
    ActorSystem system = ActorSystem.create("some-system");
    Props props = new Props(Orders.class);
    ActorRef producer = system.actorOf(props, "jmsproducer");
    producer.tell("<order amount=\"100\" currency=\"PLN\" itemId=\"12345\"/>", null);
    //#TellProducer
    system.shutdown();
  }

  public void askProducer() {
    //#AskProducer
    ActorSystem system = ActorSystem.create("some-system");
    Props props = new Props(FirstProducer.class);
    ActorRef producer = system.actorOf(props,"myproducer");
    Future<Object> future = Patterns.ask(producer, "some request", 1000);
    //#AskProducer
    system.stop(producer);
    system.shutdown();
  }

  public void correlate(){
    //#Correlate
    ActorSystem system = ActorSystem.create("some-system");
    Props props = new Props(Orders.class);
    ActorRef producer = system.actorOf(props,"jmsproducer");
    Map<String,Object> headers = new HashMap<String, Object>();
    headers.put(CamelMessage.MessageExchangeId(),"123");
    producer.tell(new CamelMessage("<order amount=\"100\" currency=\"PLN\" itemId=\"12345\"/>",headers), null);
    //#Correlate
    system.stop(producer);
    system.shutdown();
  }
}
