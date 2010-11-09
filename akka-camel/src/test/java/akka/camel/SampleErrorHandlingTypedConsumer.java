package akka.camel;

/**
 * @author Martin Krasser
 */
public interface SampleErrorHandlingTypedConsumer {

    @consume(value="direct:error-handler-test-java-typed", routeDefinitionHandler=SampleRouteDefinitionHandler.class)
    String willFail(String s);

}
