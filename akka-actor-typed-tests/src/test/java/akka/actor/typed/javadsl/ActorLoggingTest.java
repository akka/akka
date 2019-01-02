/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl;

import akka.actor.typed.Behavior;
import akka.event.Logging;
import akka.japi.pf.PFBuilder;
import akka.testkit.CustomEventFilter;
import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import com.typesafe.config.ConfigFactory;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import scala.concurrent.duration.FiniteDuration;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ActorLoggingTest extends JUnitSuite {

  @ClassRule
  public static final TestKitJunitResource testKit = new TestKitJunitResource(
    ConfigFactory.parseString(
    "akka.loglevel = INFO\n" +
      "akka.loggers = [\"akka.testkit.TestEventListener\"]"
    ));

  interface Protocol {
    String getTransactionId();
  }

  static class Message implements Protocol {
    public final String transactionId;
    public Message(String transactionId) {
      this.transactionId = transactionId;
    }
    public String getTransactionId() {
      return transactionId;
    }
  }

  @Test
  public void loggingProvidesMDC() {
    Behavior<Protocol> behavior = Behaviors.withMdc(
      null,
      (message) -> {
        Map<String, Object> mdc = new HashMap<>();
        mdc.put("txId", message.getTransactionId());
        return mdc;
      },
      Behaviors.receive(Protocol.class)
        .onMessage(Message.class, (context, message) -> {
          context.getLog().info(message.toString());
          return Behaviors.same();
        }).build()
    );

    CustomEventFilter eventFilter = new CustomEventFilter(new PFBuilder<Logging.LogEvent, Object>()
      .match(Logging.LogEvent.class, (event) ->
        event.getMDC().containsKey("txId"))
      .build(),
      1);

    testKit.spawn(behavior);
    eventFilter.awaitDone(FiniteDuration.create(3, TimeUnit.SECONDS));

  }
}
