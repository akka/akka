/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel;

import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.testkit.JavaTestKit;
import akka.util.Timeout;
import scala.concurrent.Await;
import scala.concurrent.ExecutionContext;
import org.junit.AfterClass;
import org.junit.Test;
import java.util.concurrent.TimeUnit;
import akka.testkit.AkkaSpec;
import static org.junit.Assert.*;
/**
 *
 */
public class ConsumerJavaTestBase {

  static ActorSystem system = ActorSystem.create("test", AkkaSpec.testConf());

  @AfterClass
  public static void tearDownAfterClass() {
    system.shutdown();
  }

    @Test
    public void shouldHandleExceptionThrownByActorAndGenerateCustomResponse() throws Exception {
        new JavaTestKit(system) {{
            String result = new EventFilter<String>(Exception.class) {
                protected String run() {
                    FiniteDuration duration = Duration.create(1, TimeUnit.SECONDS);
                    Timeout timeout = new Timeout(duration);
                    Camel camel = CamelExtension.get(system);
                    ExecutionContext executionContext = system.dispatcher();
                    try {
                        Await.result(
                                camel.activationFutureFor(system.actorOf(new Props(SampleErrorHandlingConsumer.class), "sample-error-handling-consumer"), timeout, executionContext),
                                duration);
                        return camel.template().requestBody("direct:error-handler-test-java", "hello", String.class);
                    }
                    catch (Exception e) {
                        return e.getMessage();
                    }
                }
            }.occurrences(1).exec();
            assertEquals("error: hello", result);
        }};
    }
}
