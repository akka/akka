/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.camel;

import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.AkkaSpec;
import org.junit.ClassRule;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.testkit.JavaTestKit;
import akka.util.Timeout;
import scala.concurrent.Await;
import scala.concurrent.ExecutionContext;
import org.junit.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class ConsumerJavaTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("ConsumerJavaTest", AkkaSpec.testConf());

  private final ActorSystem system = actorSystemResource.getSystem();

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
              camel.activationFutureFor(system.actorOf(Props.create(SampleErrorHandlingConsumer.class), "sample-error-handling-consumer"), timeout, executionContext),
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
