/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.akka.typed;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Props;
import akka.actor.typed.javadsl.*;
import akka.testkit.typed.javadsl.TestProbe;
import akka.util.Timeout;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class InteractionPatternsTest extends JUnitSuite {

  // #fire-and-forget-definition
  class PrintMe {
    public final String message;
    public PrintMe(String message) {
      this.message = message;
    }
  }

  static final Behavior<PrintMe> printerBehavior = Behaviors.immutable(PrintMe.class)
    .onMessage(PrintMe.class, (ctx, printMe) -> {
      ctx.getLog().info(printMe.message);
      return Behaviors.same();
    }).build();
  // #fire-and-forget-definition

  // #request-response-protocol
  class Request {
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
    Behaviors.immutable(Request.class)
      .onMessage(Request.class, (ctx, request) -> {
        // ... process request ...
        request.respondTo.tell(new Response("Here's your response!"));
        return Behaviors.same();
      }).build();
    // #request-response-respond

    ActorRef<Request> otherActor = null;
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
    public static class Translate implements Command {
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
            ctx.getLog().info("Started {}", wrapped.response.taskId);
            return this;
          })
          .onMessage(WrappedJobProgress.class, wrapped -> {
            ctx.getLog().info("Progress {}: {}", wrapped.response.taskId,
              wrapped.response.progress);
            return this;
          })
          .onMessage(WrappedJobCompleted.class, wrapped -> {
            ctx.getLog().info("Completed {}: {}", wrapped.response.taskId,
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
    final ActorSystem<PrintMe> system =
      ActorSystem.create(printerBehavior, "printer-sample-system");

    // note that system is also the ActorRef to the guardian actor
    final ActorRef<PrintMe> ref = system;

    // these are all fire and forget
    ref.tell(new PrintMe("message 1"));
    ref.tell(new PrintMe("message 2"));
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
    TestProbe<Batch> probe = TestProbe.create("batcher", system);
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
  interface HalCommand {}
  static final class OpenThePodBayDoorsPlease implements HalCommand {
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

  static final Behavior<HalCommand> halBehavior =
    Behaviors.immutable(HalCommand.class)
      .onMessage(OpenThePodBayDoorsPlease.class, (ctx, msg) -> {
        msg.respondTo.tell(new HalResponse("I'm sorry, Dave. I'm afraid I can't do that."));
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

  public static Behavior<DaveProtocol> daveBehavior(final ActorRef<HalCommand> hal) {
    return Behaviors.setup((ActorContext<DaveProtocol> ctx) -> {

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
          innerCtx.getLog().info("Got response from HAL: {}", response.message);
          return Behaviors.same();
        }).build();
    });
  }
  // #actor-ask


  // #standalone-ask
  interface CookieCommand {}
  static class GiveMeCookies implements CookieCommand {
    public final ActorRef<Cookies> cookies;
    GiveMeCookies(ActorRef<Cookies> cookies) {
      this.cookies = cookies;
    }
  };
  static class Cookies {}


  public void askAndPrint(ActorSystem<Object> system, ActorRef<CookieCommand> cookieActorRef) {
    CompletionStage<Cookies> result = AskPattern.ask(
      cookieActorRef,
      GiveMeCookies::new,
      // asking someone requires a timeout and a scheduler, if the timeout hits without response
      // the ask is failed with a TimeoutException
      Timeout.apply(3, TimeUnit.SECONDS),
      system.scheduler());

    result.whenComplete((cookies, failure) -> {
      if (cookies != null) System.out.println("Yay, cookies!");
      else System.out.println("Boo! didn't get cookies in time.");
    });
  }
  // #standalone-ask


  // #per-session-child
  // dummy data types just for this sample
  interface Keys {}
  interface Wallet {}
  // #per-session-child

  static final Behavior<GetKeys> keyCabinetBehavior = null;
  static final Behavior<GetWallet> drawerBehavior = null;
  // #per-session-child
  // messages for the two services we interact with
  class GetKeys {
    public final String whoseKeys;
    public final ActorRef<Keys> respondTo;
    public GetKeys(String whoseKeys, ActorRef<Keys> respondTo) {
      this.whoseKeys = whoseKeys;
      this.respondTo = respondTo;
    }
  }
  class GetWallet {
    public final String whoseWallet;
    public final ActorRef<Wallet> respondTo;
    public GetWallet(String whoseWallet, ActorRef<Wallet> respondTo) {
      this.whoseWallet = whoseWallet;
      this.respondTo = respondTo;
    }
  }

  interface HomeCommand {}
  class LeaveHome implements HomeCommand {
    public final String who;
    public final ActorRef<ReadyToLeaveHome> respondTo;
    public LeaveHome(String who, ActorRef<ReadyToLeaveHome> respondTo) {
      this.who = who;
      this.respondTo = respondTo;
    }
  }

  class ReadyToLeaveHome {
    public final String who;
    public final Keys keys;
    public final Wallet wallet;
    public ReadyToLeaveHome(String who, Keys keys, Wallet wallet) {
      this.who = who;
      this.keys = keys;
      this.wallet = wallet;
    }
  }

  // actor behavior
  public Behavior<HomeCommand> homeBehavior() {
    return Behaviors.setup((ctx) -> {
      final ActorRef<GetKeys> keyCabinet = ctx.spawn(keyCabinetBehavior, "key-cabinet");
      final ActorRef<GetWallet> drawer = ctx.spawn(drawerBehavior, "drawer");

      return Behaviors.immutable(HomeCommand.class)
        .onMessage(LeaveHome.class, (innerCtx, msg) -> {
          ctx.spawn(new PrepareToLeaveHome(msg.who, msg.respondTo, keyCabinet, drawer), "leaving" + msg.who);
          return Behavior.same();
        }).build();
    });
  }

  // per session actor behavior
  class PrepareToLeaveHome extends Behaviors.MutableBehavior<Object> {
    private final String whoIsLeaving;
    private final ActorRef<ReadyToLeaveHome> respondTo;
    private final ActorRef<GetKeys> keyCabinet;
    private final ActorRef<GetWallet> drawer;
    private Optional<Wallet> wallet = Optional.empty();
    private Optional<Keys> keys = Optional.empty();
    public PrepareToLeaveHome(String whoIsLeaving, ActorRef<ReadyToLeaveHome> respondTo, ActorRef<GetKeys> keyCabinet, ActorRef<GetWallet> drawer) {
      this.whoIsLeaving = whoIsLeaving;
      this.respondTo = respondTo;
      this.keyCabinet = keyCabinet;
      this.drawer = drawer;
    }

    @Override
    public Behaviors.Receive<Object> createReceive() {
      return receiveBuilder()
        .onMessage(Wallet.class, (wallet) -> {
          this.wallet = Optional.of(wallet);
          return completeOrContinue();
        }).onMessage(Keys.class, (keys) -> {
          this.keys = Optional.of(keys);
          return completeOrContinue();
        }).build();
    }


    private Behavior<Object> completeOrContinue() {
      if (wallet.isPresent() && keys.isPresent()) {
        respondTo.tell(new ReadyToLeaveHome(whoIsLeaving, keys.get(), wallet.get()));
        return Behaviors.stopped();
      } else {
        return this;
      }
    }
  }
  // #per-session-child



}



