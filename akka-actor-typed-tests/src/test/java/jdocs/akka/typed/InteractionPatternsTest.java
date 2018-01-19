/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.akka.typed;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.BehaviorBuilder;
import akka.actor.typed.javadsl.Behaviors;
import javafx.print.Printer;
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
    return BehaviorBuilder.<PrinterProtocol>create()
      .onMessage(DisableOutput.class, (ctx, disableOutput) -> disabledPrinterBehavior())
      .onMessage(PrintMe.class, (ctx, printMe) -> {
        System.out.println(printMe.message);
        return Behaviors.same();
      }).build();
  }

  public Behavior<PrinterProtocol> disabledPrinterBehavior() {
    return BehaviorBuilder.<PrinterProtocol>create()
      .onMessage(EnableOutput.class, (ctx, enableOutput) -> enabledPrinterBehavior())
      .build();
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
