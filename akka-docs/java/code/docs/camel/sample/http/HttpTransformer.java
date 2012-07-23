package docs.camel.sample.http;

import akka.actor.Status;
import akka.actor.UntypedActor;
import akka.camel.CamelMessage;
import akka.japi.Function;

//#HttpExample
public class HttpTransformer extends UntypedActor{
  public void onReceive(Object message) {
    if (message instanceof CamelMessage) {
      CamelMessage camelMessage = (CamelMessage) message;
      CamelMessage replacedMessage = camelMessage.mapBody(new Function<String, String>(){
        public String apply(String body) {
          return body.replaceAll("Akka ", "AKKA ");
        }
      });
      getSender().tell(replacedMessage);
    } else if (message instanceof Status.Failure) {
      getSender().tell(message);
    } else
      unhandled(message);
  }
}
//#HttpExample