package docs.camel;
//#ErrorThrowingConsumer
import akka.actor.Status;
import akka.camel.CamelMessage;
import akka.camel.javaapi.UntypedConsumerActor;
import akka.dispatch.Mapper;
import org.apache.camel.builder.Builder;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import scala.Option;

public class ErrorThrowingConsumer extends UntypedConsumerActor{
  private String uri;

  private Mapper<RouteDefinition, ProcessorDefinition<?>> routeDefinitionHandler = new Mapper<RouteDefinition, ProcessorDefinition<?>>(){
      @Override
      public ProcessorDefinition<?> checkedApply(RouteDefinition rd) {
        // Catch any exception and handle it by returning the exception message as response
        return rd.onException(Exception.class).handled(true).transform(Builder.exceptionMessage()).end();
      }
  };

  public ErrorThrowingConsumer(String uri){
    this.uri = uri;
  }

  public String getEndpointUri() {
    return uri;
  }

  public void onReceive(Object message) throws Exception{
    if (message instanceof CamelMessage) {
      CamelMessage camelMessage = (CamelMessage) message;
      String body = camelMessage.getBodyAs(String.class, getCamelContext());
      throw new Exception(String.format("error: %s",body));
    } else
      unhandled(message);
  }

  @Override
  public Mapper<RouteDefinition, ProcessorDefinition<?>> getRouteDefinitionHandler() {
    return routeDefinitionHandler;
  }

  @Override
  public void preRestart(Throwable reason, Option<Object> message) {
    getSender().tell(new Status.Failure(reason));
  }
}
//#ErrorThrowingConsumer