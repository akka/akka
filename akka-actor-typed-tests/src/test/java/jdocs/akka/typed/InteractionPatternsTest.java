/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.akka.typed;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Props;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.BehaviorBuilder;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.TimerScheduler;
import akka.testkit.typed.scaladsl.TestProbe;
import akka.util.Timeout;
import javafx.scene.control.DateCell;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class InteractionPatternsTest extends JUnitSuite {

  // #fire-and-forget-definition
  interface PrinterProtocol { }
  class DisableOutput implements PrinterProtocol { }
  class EnableOutput implements PrinterProtocol { }
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
  // #fire-and-forget-definition

  // #request-response-protocol
  interface Protocol {}
  class Request implements Protocol {
    public final String query;
    public final ActorRef<Response> respondTo;
    public Request(String query, ActorRef<Response> respondTo) {
      this.query = query;
      this.respondTo = respondTo;
    }
  }

  class Response {
    public final String result;
    public Response(String result) {
      this.result = result;
    }
  }
  // #request-response-protocol

  public void compileOnlyRequestResponse() {

    // #request-response-respond
    // actor behavior
    Behaviors.immutable(Protocol.class)
      .onMessage(Request.class, (ctx, request) -> {
        // ... process request ...
        request.respondTo.tell(new Response("Here's your response!"));
        return Behaviors.same();
      }).build();
    // #request-response-respond

    ActorRef<Protocol> otherActor = null;
    ActorContext<Response> ctx = null;
    // #request-response-send
    otherActor.tell(new Request("give me cookies", ctx.getSelf()));
    // #request-response-send
  }


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
            System.out.println("Progress " + wrapped.response.taskId + ": " +
              wrapped.response.progress);
            return this;
          })
          .onMessage(WrappedJobCompleted.class, wrapped -> {
            System.out.println("Completed " + wrapped.response.taskId + ": " +
              wrapped.response.result);
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
    // #fire-and-forget-doit
    final ActorSystem<PrinterProtocol> system =
      ActorSystem.create(enabledPrinterBehavior(), "printer-sample-system");

    // note that system is also the ActorRef to the guardian actor
    final ActorRef<PrinterProtocol> ref = system;

    // these are all fire and forget
    ref.tell(new PrintMe("message"));
    ref.tell(new DisableOutput());
    ref.tell(new PrintMe("message"));
    ref.tell(new EnableOutput());

    // #fire-and-forget-doit

    Await.ready(system.terminate(), Duration.create(3, TimeUnit.SECONDS));
  }

  //#timer
  interface Msg {
  }

  public static final class Batch {
    private final List<Msg> messages;

    public Batch(List<Msg> messages) {
      this.messages = Collections.unmodifiableList(messages);
    }

    public List<Msg> getMessages() {
      return messages;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Batch batch = (Batch) o;
      return Objects.equals(messages, batch.messages);
    }

    @Override
    public int hashCode() {
      return Objects.hash(messages);
    }
  }

  public static final class ExcitingMessage implements Msg {
    private final String msg;

    public ExcitingMessage(String msg) {
      this.msg = msg;
    }
  }

  private static final Object TIMER_KEY = new Object();

  private static class TimeoutMsg implements Msg {
  }

  public static Behavior<Msg> behavior(ActorRef<Batch> target, FiniteDuration after, int maxSize) {
    return Behaviors.withTimers(timers -> idle(timers, target, after, maxSize));
  }

  private static Behavior<Msg> idle(TimerScheduler<Msg> timers, ActorRef<Batch> target,
                                    FiniteDuration after, int maxSize) {
    return Behaviors.immutable(Msg.class)
      .onMessage(Msg.class, (ctx, msg) -> {
        timers.startSingleTimer(TIMER_KEY, new TimeoutMsg(), after);
        List<Msg> buffer = new ArrayList<>();
        buffer.add(msg);
        return active(buffer, timers, target, after, maxSize);
      })
      .build();
  }

  private static Behavior<Msg> active(List<Msg> buffer, TimerScheduler<Msg> timers,
                                      ActorRef<Batch> target, FiniteDuration after, int maxSize) {
    return Behaviors.immutable(Msg.class)
      .onMessage(TimeoutMsg.class, (ctx, msg) -> {
        target.tell(new Batch(buffer));
        return idle(timers, target, after, maxSize);
      })
      .onMessage(Msg.class, (ctx, msg) -> {
        buffer.add(msg);
        if (buffer.size() == maxSize) {
          timers.cancel(TIMER_KEY);
          target.tell(new Batch(buffer));
          return idle(timers, target, after, maxSize);
        } else {
          return active(buffer, timers, target, after, maxSize);
        }
      })
      .build();
  }
  //#timer

  @Test
  public void timers() throws Exception {
    final ActorSystem<Object> system = ActorSystem.create(Behaviors.empty(), "timers-sample");
    TestProbe<Batch> probe = new TestProbe<>("batcher", system);
    ActorRef<Msg> bufferer = Await.result(system.systemActorOf(
      behavior(probe.ref(), new FiniteDuration(1, TimeUnit.SECONDS), 10),
      "batcher", Props.empty(), akka.util.Timeout.apply(1, TimeUnit.SECONDS)),
      new FiniteDuration(1, TimeUnit.SECONDS));

    ExcitingMessage msgOne = new ExcitingMessage("one");
    ExcitingMessage msgTwo = new ExcitingMessage("two");
    bufferer.tell(msgOne);
    bufferer.tell(msgTwo);
    probe.expectNoMessage(new FiniteDuration(1, TimeUnit.MILLISECONDS));
    probe.expectMessage(new FiniteDuration(2, TimeUnit.SECONDS),
      new Batch(Arrays.asList(msgOne, msgTwo)));

    Await.ready(system.terminate(), Duration.create(3, TimeUnit.SECONDS));
  }



  // #actor-ask
  interface HalProtocol {}
  static final class OpenThePodBayDoorsPlease implements HalProtocol {
    public final ActorRef<HalResponse> respondTo;
    OpenThePodBayDoorsPlease(ActorRef<HalResponse> respondTo) {
      this.respondTo = respondTo;
    }
  }
  static final class HalResponse {
    public final String message;
    HalResponse(String message) {
      this.message = message;
    }
  }

  static final Behavior<HalProtocol> halBehavior =
    Behaviors.immutable(HalProtocol.class)
      .onMessage(OpenThePodBayDoorsPlease.class, (ctx, msg) -> {
        msg.respondTo.tell(new HalResponse("I'm sorry Dave, I cannot do that!"));
        return Behaviors.same();
      }).build();

  interface DaveProtocol {}

  // this is a part of the protocol that is internal to the actor itself
  private static final class AdaptedResponse implements DaveProtocol {
    public final String message;
    public AdaptedResponse(String message) {
      this.message = message;
    }
  }

  public static Behavior<DaveProtocol> daveBehavior(final ActorRef<HalProtocol> hal) {
    return Behaviors.deferred((ActorContext<DaveProtocol> ctx) -> {

      // asking someone requires a timeout, if the timeout hits without response
      // the ask is failed with a TimeoutException
      final Timeout timeout = Timeout.apply(3, TimeUnit.SECONDS);

      ctx.ask(
        HalResponse.class,
        hal,
        timeout,
        // construct the outgoing message
        (ActorRef<HalResponse> ref) -> new OpenThePodBayDoorsPlease(ref),
        // adapt the response (or failure to respond)
        (response, throwable) -> {
          if (response != null) {
            return new AdaptedResponse(response.message);
          } else {
            return new AdaptedResponse("Request failed");
          }
      });

      // we can also tie in request context into an interaction, it is safe to look at
      // actor internal state from the transformation function, but remember that it may have
      // changed at the time the response arrives and the transformation is done, best is to
      // use immutable state we have closed over like here.
      final int requestId = 1;
      ctx.ask(
        HalResponse.class,
        hal,
        timeout,
        // construct the outgoing message
        (ActorRef<HalResponse> ref) -> new OpenThePodBayDoorsPlease(ref),
        // adapt the response (or failure to respond)
        (response, throwable) -> {
          if (response != null) {
            return new AdaptedResponse(requestId + ": " + response.message);
          } else {
            return new AdaptedResponse(requestId + ": Request failed");
          }
        });

      return Behaviors.immutable(DaveProtocol.class)
        // the adapted message ends up being processed like any other
        // message sent to the actor
        .onMessage(AdaptedResponse.class, (innerCtx, response) -> {
          System.out.println("Got response from HAL: " + response.message);
          return Behaviors.same();
        }).build();
    });
  }

  // #actor-ask

}



