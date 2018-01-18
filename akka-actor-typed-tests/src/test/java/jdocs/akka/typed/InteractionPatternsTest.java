/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.akka.typed;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

import java.util.concurrent.TimeUnit;

public class InteractionPatternsTest extends JUnitSuite {

  // #fire-and-forget
  interface PrinterProtocol {}
  class DisableOutput implements PrinterProtocol {}
  class EnableOutput implements PrinterProtocol {}
  class PrintMe implements PrinterProtocol {
    public final String message;
    public PrintMe(String message) {
      this.message = message;
    }
  }

  public Behavior<PrinterProtocol> enabledPrinterBehavior() {
    return Behaviors.immutable((ctx, message) -> {
      if (message instanceof DisableOutput) {
        return disabledPrinterBehavior();
      } else if (message instanceof PrintMe) {
        System.out.println(((PrintMe) message).message);
        return Behaviors.same();
      } else {
        return Behaviors.ignore();
      }
    });
  }

  public Behavior<PrinterProtocol> disabledPrinterBehavior() {
    return Behaviors.immutable((ctx, message) -> {
      if (message instanceof EnableOutput) {
        return enabledPrinterBehavior();
      } else {
        // ignore any other message than enable
        return Behaviors.ignore();
      }
    });
  }

  // #fire-and-forget


  @Test
  public void fireAndForgetSample() throws Exception {
    // #fire-and-forget
    final ActorSystem<PrinterProtocol> system =
        ActorSystem.create(enabledPrinterBehavior(), "printer-sample-system");

    // note that system is also the ActorRef to the guardian actor
    final ActorRef<PrinterProtocol> ref = system;

    // these are all fire and forget
    ref.tell(new PrintMe("message"));
    ref.tell(new DisableOutput());
    ref.tell(new PrintMe("message"));
    ref.tell(new EnableOutput());

    // #fire-and-forget

    Await.ready(system.terminate(), Duration.create(3, TimeUnit.SECONDS));
  }


}
