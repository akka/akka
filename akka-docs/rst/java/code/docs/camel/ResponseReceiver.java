package docs.camel;
//#RouteResponse
import akka.actor.UntypedAbstractActor;
import akka.camel.CamelMessage;

public class ResponseReceiver extends UntypedAbstractActor{
  public void onReceive(Object message) {
     if(message instanceof CamelMessage) {
       // do something with the forwarded response
     }
  }
}
//#RouteResponse
