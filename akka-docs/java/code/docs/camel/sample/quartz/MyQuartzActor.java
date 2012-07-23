package docs.camel.sample.quartz;
//#QuartzExample
import akka.camel.CamelMessage;
import akka.camel.javaapi.UntypedConsumerActor;

public class MyQuartzActor extends UntypedConsumerActor{
  public String getEndpointUri() {
    return "quartz://example?cron=0/2+*+*+*+*+?";
  }

  public void onReceive(Object message) {
    if (message instanceof CamelMessage) {
      CamelMessage camelMessage = (CamelMessage) message;
      String body = camelMessage.getBodyAs(String.class, getCamelContext());
      System.out.println(String.format("==============> received %s ", body));
    } else
      unhandled(message);
  }
}
//#QuartzExample