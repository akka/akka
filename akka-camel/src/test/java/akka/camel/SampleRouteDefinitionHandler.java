package akka.camel;

import org.apache.camel.builder.Builder;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;

/**
 * @author Martin Krasser
 */
public class SampleRouteDefinitionHandler implements RouteDefinitionHandler {
    public ProcessorDefinition<?> onRouteDefinition(RouteDefinition rd) {
        return rd.onException(Exception.class).handled(true).transform(Builder.exceptionMessage()).end();
    }
}
