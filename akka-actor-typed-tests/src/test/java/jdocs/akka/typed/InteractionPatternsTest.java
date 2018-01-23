/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.akka.typed;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.BehaviorBuilder;
import akka.actor.typed.javadsl.Behaviors;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
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

  // #adapted-response

  public static class Backend {
    interface Request {}
    public static class StartTranslationJob implements Request {
      public final int taskId;
      public final URI site;
      public final ActorRef<Response> replyTo;

      public StartTranslationJob(int taskId, URI site, ActorRef<Response> replyTo) {
        this.taskId = taskId;
        this.site = site;
        this.replyTo = replyTo;
      }
    }

    interface Response {}
    public static class JobStarted implements Response {
      public final int taskId;

      public JobStarted(int taskId) {
        this.taskId = taskId;
      }
    }

    public static class JobProgress implements Response {
      public final int taskId;
      public final double progress;

      public JobProgress(int taskId, double progress) {
        this.taskId = taskId;
        this.progress = progress;
      }
    }

    public static class JobCompleted implements Response {
      public final int taskId;
      public final URI result;

      public JobCompleted(int taskId, URI result) {
        this.taskId = taskId;
        this.result = result;
      }
    }

  }

  public static class Frontend {

    interface Command {}
    public static class  Translate implements Command {
      public final URI site;
      public final ActorRef<URI> replyTo;

      public Translate(URI site, ActorRef<URI> replyTo) {
        this.site = site;
        this.replyTo = replyTo;
      }
    }

    private static class WrappedJobStarted implements Command {
      final Backend.JobStarted response;

      public WrappedJobStarted(Backend.JobStarted response) {
        this.response = response;
      }
    }

    private static class WrappedJobProgress implements Command {
      final Backend.JobProgress response;

      public WrappedJobProgress(Backend.JobProgress response) {
        this.response = response;
      }
    }

    private static class WrappedJobCompleted implements Command {
      final Backend.JobCompleted response;

      public WrappedJobCompleted(Backend.JobCompleted response) {
        this.response = response;
      }
    }

    private static class OtherResponse implements Command {
      final Backend.Response response;

      public OtherResponse(Backend.Response response) {
        this.response = response;
      }
    }

    public static class Translator extends Behaviors.MutableBehavior<Command> {
      private final ActorContext<Command> ctx;
      private final ActorRef<Backend.Request> backend;
      private final ActorRef<Backend.Response> backendResponseAdapter;

      private int taskIdCounter = 0;
      private Map<Integer, ActorRef<URI>> inProgress = new HashMap<>();

      public Translator(ActorContext<Command> ctx, ActorRef<Backend.Request> backend) {
        this.ctx = ctx;
        this.backend = backend;
        this.backendResponseAdapter =
            ctx.messageAdapter(Backend.Response.class, rsp -> {
              if (rsp instanceof Backend.JobStarted)
                return new WrappedJobStarted((Backend.JobStarted) rsp);
              else if (rsp instanceof Backend.JobProgress)
                return new WrappedJobProgress((Backend.JobProgress) rsp);
              else if (rsp instanceof Backend.JobCompleted)
                return new WrappedJobCompleted((Backend.JobCompleted) rsp);
              else return new OtherResponse(rsp);
            });
      }

      @Override
      public Behaviors.Receive<Command> createReceive() {
        return receiveBuilder()
          .onMessage(Translate.class, cmd -> {
            taskIdCounter += 1;
            inProgress.put(taskIdCounter, cmd.replyTo);
            backend.tell(new Backend.StartTranslationJob(
                taskIdCounter, cmd.site, backendResponseAdapter));
            return this;
          })
          .onMessage(WrappedJobStarted.class, wrapped -> {
            System.out.println("Started " + wrapped.response.taskId);
            return this;
          })
          .onMessage(WrappedJobProgress.class, wrapped -> {
            System.out.println("Progress " + wrapped.response.taskId + ": " + wrapped.response.progress);
            return this;
          })
          .onMessage(WrappedJobCompleted.class, wrapped -> {
            System.out.println("Completed " + wrapped.response.taskId + ": " + wrapped.response.result);
            return this;
          })
          .onMessage(OtherResponse.class, other -> Behaviors.unhandled())
          .build();
      }
    }
  }
  // #adapted-response


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
