package docs.camel;
//#Consumer3
import akka.actor.Status;
import akka.camel.Ack;
import akka.camel.CamelMessage;
import akka.camel.javaapi.UntypedConsumerActor;

public class Consumer3 extends UntypedConsumerActor{

  @Override
  public boolean autoAck() {
    return false;
  }

  public String getEndpointUri() {
    return "jms:queue:test";
  }

  public void onReceive(Object message) {
    if (message instanceof CamelMessage) {
      sender().tell(Ack.getInstance(), self());
      // on success
      // ..
      Exception someException = new Exception("e1");
      // on failure
      sender().tell(new Status.Failure(someException), self());
    } else
      unhandled(message);
  }
}
//#Consumer3
