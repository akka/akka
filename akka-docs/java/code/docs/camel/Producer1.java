package docs.camel;
//#Producer1
import akka.camel.javaapi.UntypedProducerActor;

public class Producer1 extends UntypedProducerActor{
  public String getEndpointUri() {
    return "http://localhost:8080/news";
  }
}
//#Producer1