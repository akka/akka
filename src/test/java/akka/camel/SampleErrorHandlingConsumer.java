package akka.camel;

import akka.util.Duration;
import org.apache.camel.builder.Builder;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;

/**
 * @author Martin Krasser
 */
public class SampleErrorHandlingConsumer extends UntypedConsumerActor {

    public Camel camel(){
        return new DefaultCamel().start();
    }

    public String getEndpointUri() {
        return "direct:error-handler-test-java";
    }

    public BlockingOrNot isBlocking() {
        return new Blocking(Duration.fromNanos(100000000000L));
    }

    public void preStart() {
        onRouteDefinition(new RouteDefinitionHandler() {
            public ProcessorDefinition<?> onRouteDefinition(RouteDefinition rd) {
                return rd.onException(Exception.class).handled(true).transform(Builder.exceptionMessage()).end();
            }
        });
    }

    public void onReceive(Object message) throws Exception {
        Message msg = (Message)message;
        String body = msg.getBodyAs(String.class);
        throw new Exception(String.format("error: %s", body));
   }

}
