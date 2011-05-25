package akka.camel;

import org.apache.camel.builder.Builder;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;

/**
 * @author Martin Krasser
 */
public class SampleErrorHandlingConsumer extends UntypedConsumerActor {

    public String getEndpointUri() {
        return "direct:error-handler-test-java";
    }

    public boolean isBlocking() {
        return true;
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
