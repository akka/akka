package akka.camel;

import akka.japi.SideEffect;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import static akka.actor.Actors.*;
import static akka.camel.CamelContextManager.*;
import static akka.camel.CamelServiceManager.*;

import static org.junit.Assert.*;

/**
 * @author Martin Krasser
 */
public class ConsumerJavaTestBase {

    @BeforeClass
    public static void setUpBeforeClass() {
        startCamelService();
    }

    @AfterClass
    public static void tearDownAfterClass() {
        stopCamelService();
        registry().local().shutdownAll();
    }

    @Test @Ignore // TODO: fix race

    // org.apache.camel.CamelExchangeException: No consumers available
    // on endpoint: Endpoint[direct://error-handler-test-java]

    public void shouldHandleExceptionThrownByActorAndGenerateCustomResponse() {
        getMandatoryService().awaitEndpointActivation(1, new SideEffect() {
            public void apply() {
                actorOf(SampleErrorHandlingConsumer.class);
            }
        });
        String result = getMandatoryTemplate().requestBody("direct:error-handler-test-java", "hello", String.class);
        assertEquals("error: hello", result);
    }
}
