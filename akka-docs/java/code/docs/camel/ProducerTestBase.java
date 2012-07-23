package docs.camel;

import akka.actor.*;
import akka.camel.Camel;
import akka.camel.CamelExtension;
import akka.camel.CamelMessage;
import akka.pattern.Patterns;
import scala.concurrent.Future;
import scala.concurrent.util.Duration;
import scala.concurrent.util.FiniteDuration;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ProducerTestBase {
  public void tellJmsProducer() {
    //#TellProducer
    ActorSystem system = ActorSystem.create("some-system");
    Props props = new Props(Orders.class);
    ActorRef producer = system.actorOf(props, "jmsproducer");
    producer.tell("<order amount=\"100\" currency=\"PLN\" itemId=\"12345\"/>");
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
    producer.tell(new CamelMessage("<order amount=\"100\" currency=\"PLN\" itemId=\"12345\"/>",headers));
    //#Correlate
    system.stop(producer);
    system.shutdown();
  }
}
