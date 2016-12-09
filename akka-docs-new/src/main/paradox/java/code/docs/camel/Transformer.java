package docs.camel;
//#TransformOutgoingMessage
import akka.camel.CamelMessage;
import akka.camel.javaapi.UntypedProducerActor;
import akka.dispatch.Mapper;
import akka.japi.Function;

public class Transformer extends UntypedProducerActor{
  private String uri;

  public Transformer(String uri) {
    this.uri = uri;
  }

  public String getEndpointUri() {
    return uri;
  }

  private CamelMessage upperCase(CamelMessage msg) {
    return msg.mapBody(new Mapper<String,String>() {
      @Override
      public String apply(String body) {
        return body.toUpperCase();
      }
    });
  }

  @Override
  public Object onTransformOutgoingMessage(Object message) {
    if(message instanceof CamelMessage) {
      CamelMessage camelMessage = (CamelMessage) message;
      return upperCase(camelMessage);
    } else {
      return message;
    }
  }
}
//#TransformOutgoingMessage
