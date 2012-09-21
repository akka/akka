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
      CamelMessage replacedMessage = camelMessage.mapBody(new Function<Object, String>(){
        public String apply(Object body) {
          String text = new String((byte[])body);
          return text.replaceAll("Akka ", "AKKA ");
        }
      });
      getSender().tell(replacedMessage, getSelf());
    } else if (message instanceof Status.Failure) {
      getSender().tell(message, getSelf());
    } else
      unhandled(message);
  }
}
//#HttpExample