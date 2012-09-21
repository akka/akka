package docs.camel;
//#RouteResponse
import akka.actor.UntypedActor;
import akka.camel.CamelMessage;

public class ResponseReceiver extends UntypedActor{
  public void onReceive(Object message) {
     if(message instanceof CamelMessage) {
       // do something with the forwarded response
     }
  }
}
//#RouteResponse
