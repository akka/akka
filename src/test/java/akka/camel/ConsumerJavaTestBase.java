package akka.camel;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import org.junit.AfterClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;


/**
 * @author Martin Krasser
 */
public class ConsumerJavaTestBase {

    static ActorSystem system = akka.actor.ActorSystem.create("test");
    static Camel camel = ((Camel) CamelExtension.get(system));
    

    @AfterClass
    public static void tearDownAfterClass() {
        system.shutdown();
    }

    @Test
    public void shouldHandleExceptionThrownByActorAndGenerateCustomResponse() {
        ActorRef ref = system.actorOf(new Props().withCreator(SampleErrorHandlingConsumer.class));
        System.out.println("Camel ="+camel);
        camel.awaitActivation(ref, akka.util.Duration.fromNanos(10000000000L));

        String result = camel.template().requestBody("direct:error-handler-test-java", "hello", String.class);
        assertEquals("error: hello", result);
    }
}
