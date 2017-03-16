/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed.javadsl;

import static akka.typed.scaladsl.Actor.Empty;
import static akka.typed.scaladsl.Actor.Ignore;
import static akka.typed.scaladsl.Actor.Same;
import static akka.typed.scaladsl.Actor.Stopped;
import static akka.typed.scaladsl.Actor.Unhandled;

import java.util.function.Function;

import akka.japi.function.Function2;
import akka.japi.function.Procedure2;
import akka.japi.pf.PFBuilder;
import akka.typed.ActorRef;
import akka.typed.Behavior;
import akka.typed.PreStart;
import akka.typed.Signal;
import akka.typed.patterns.Restarter;
import akka.typed.scaladsl.Actor.Widened;
import scala.reflect.ClassTag;

public abstract class Actor {
	/*
	 * This DSL is implemented in Java in order to ensure consistent usability from Java,
	 * taking possible Scala oddities out of the equation. There is some duplication in
	 * the behavior implementations, but that is unavoidable if both DSLs shall offer the
	 * same runtime performance (especially concerning allocations for function converters).
	 */

  private static class Deferred<T> extends Behavior<T> {
    final akka.japi.function.Function<ActorContext<T>, Behavior<T>> producer;

    public Deferred(akka.japi.function.Function<ActorContext<T>, Behavior<T>> producer) {
      this.producer = producer;
    }

    @Override
    public Behavior<T> management(akka.typed.ActorContext<T> ctx, Signal msg) throws Exception {
      if (msg instanceof PreStart) {
        return Behavior.preStart(producer.apply(ctx), ctx);
      } else
        throw new IllegalStateException("Deferred behavior must receive PreStart as first signal");
    }

    @Override
    public Behavior<T> message(akka.typed.ActorContext<T> ctx, T msg) throws Exception {
      throw new IllegalStateException("Deferred behavior must receive PreStart as first signal");
    }
  }

  private static class Stateful<T> extends Behavior<T> {
    final Function2<ActorContext<T>, T, Behavior<T>> message;
    final Function2<ActorContext<T>, Signal, Behavior<T>> signal;

    public Stateful(Function2<ActorContext<T>, T, Behavior<T>> message,
        Function2<ActorContext<T>, Signal, Behavior<T>> signal) {
      this.signal = signal;
      this.message = message;
    }

    @Override
    public Behavior<T> management(akka.typed.ActorContext<T> ctx, Signal msg) throws Exception {
      return signal.apply(ctx, msg);
    }

    @Override
    public Behavior<T> message(akka.typed.ActorContext<T> ctx, T msg) throws Exception {
      return message.apply(ctx, msg);
    }
  }

  private static class Stateless<T> extends Behavior<T> {
    final Procedure2<ActorContext<T>, T> message;

    public Stateless(Procedure2<ActorContext<T>, T> message) {
      this.message = message;
    }

    @Override
    public Behavior<T> management(akka.typed.ActorContext<T> ctx, Signal msg) throws Exception {
      return Same();
    }

    @Override
    public Behavior<T> message(akka.typed.ActorContext<T> ctx, T msg) throws Exception {
      message.apply(ctx, msg);
      return Same();
    }
  }

  private static class Tap<T> extends Behavior<T> {
    final Procedure2<ActorContext<T>, Signal> signal;
    final Procedure2<ActorContext<T>, T> message;
    final Behavior<T> behavior;

    public Tap(Procedure2<ActorContext<T>, Signal> signal, Procedure2<ActorContext<T>, T> message,
        Behavior<T> behavior) {
      this.signal = signal;
      this.message = message;
      this.behavior = behavior;
    }

    private Behavior<T> canonicalize(Behavior<T> behv) {
      if (Behavior.isUnhandled(behv))
        return Unhandled();
      else if (behv == Same())
        return Same();
      else if (Behavior.isAlive(behv))
        return new Tap<T>(signal, message, behv);
      else
        return Stopped();
    }

    @Override
    public Behavior<T> management(akka.typed.ActorContext<T> ctx, Signal msg) throws Exception {
      signal.apply(ctx, msg);
      return canonicalize(behavior.management(ctx, msg));
    }

    @Override
    public Behavior<T> message(akka.typed.ActorContext<T> ctx, T msg) throws Exception {
      message.apply(ctx, msg);
      return canonicalize(behavior.message(ctx, msg));
    }
  }

  /**
   * Mode selector for the {@link #restarter} wrapper that decides whether an actor
   * upon a failure shall be restarted (to clean initial state) or resumed (keep
   * on running, with potentially compromised state).
   * 
   * Resuming is less safe. If you use <code>OnFailure.RESUME</code> you should at least
   * not hold mutable data fields or collections within the actor as those might
   * be in an inconsistent state (the exception might have interrupted normal
   * processing); avoiding mutable state is possible by returning a fresh
   * behavior with the new state after every message.
   */
  public enum OnFailure {
    RESUME, RESTART;
  }

  private static Function2<Object, Object, Object> _unhandledFun = (ctx, msg) -> Unhandled();

  @SuppressWarnings("unchecked")
  private static <T> Function2<ActorContext<T>, Signal, Behavior<T>> unhandledFun() {
    return (Function2<ActorContext<T>, Signal, Behavior<T>>) (Object) _unhandledFun;
  }

  private static Procedure2<Object, Object> _doNothing = (ctx, msg) -> {
  };

  @SuppressWarnings("unchecked")
  private static <T> Procedure2<ActorContext<T>, Signal> doNothing() {
    return (Procedure2<ActorContext<T>, Signal>) (Object) _doNothing;
  }

  /**
   * Construct an actor behavior that can react to incoming messages but not to
   * lifecycle signals. After spawning this actor from another actor (or as the
   * guardian of an {@link akka.typed.ActorSystem}) it will be executed within an
   * {@link ActorContext} that allows access to the system, spawning and watching
   * other actors, etc.
   * 
   * This constructor is called stateful because processing the next message
   * results in a new behavior that can potentially be different from this one.
   * If no change is desired, use {@link #same}.
   * 
   * @param message
   *          the function that describes how this actor reacts to the next
   *          message
   * @return the behavior
   */
  static public <T> Behavior<T> stateful(Function2<ActorContext<T>, T, Behavior<T>> message) {
    return new Stateful<T>(message, unhandledFun());
  }
  
  /**
   * Construct an actor behavior that can react to both incoming messages and 
   * lifecycle signals. After spawning this actor from another actor (or as the
   * guardian of an {@link akka.typed.ActorSystem}) it will be executed within an
   * {@link ActorContext} that allows access to the system, spawning and watching
   * other actors, etc.
   * 
   * This constructor is called stateful because processing the next message
   * results in a new behavior that can potentially be different from this one.
   * If no change is desired, use {@link #same}.
   * 
   * @param message
   *          the function that describes how this actor reacts to the next
   *          message
   * @param signal
   *          the function that describes how this actor reacts to the given
   *          signal
   * @return the behavior
   */
  static public <T> Behavior<T> stateful(Function2<ActorContext<T>, T, Behavior<T>> message,
      Function2<ActorContext<T>, Signal, Behavior<T>> signal) {
    return new Stateful<T>(message, signal);
  }

  /**
   * Construct an actor behavior that can react to incoming messages but not to
   * lifecycle signals. After spawning this actor from another actor (or as the
   * guardian of an {@link akka.typed.ActorSystem}) it will be executed within an
   * {@link ActorContext} that allows access to the system, spawning and watching
   * other actors, etc.
   * 
   * This constructor is called stateless because it cannot be replaced by
   * another one after it has been installed. It is most useful for leaf actors
   * that do not create child actors themselves.
   * 
   * @param message
   *          the function that describes how this actor reacts to the next
   *          message
   * @return the behavior
   */
  static public <T> Behavior<T> stateless(Procedure2<ActorContext<T>, T> message) {
    return new Stateless<T>(message);
  }

  /**
   * Return this behavior from message processing in order to advise the system
   * to reuse the previous behavior. This is provided in order to avoid the
   * allocation overhead of recreating the current behavior where that is not
   * necessary.
   * 
   * @return pseudo-behavior marking “no change”
   */
  static public <T> Behavior<T> same() {
    return Same();
  }

  /**
   * Return this behavior from message processing in order to advise the system
   * to reuse the previous behavior, including the hint that the message has not
   * been handled. This hint may be used by composite behaviors that delegate
   * (partial) handling to other behaviors.
   * 
   * @return pseudo-behavior marking “unhandled”
   */
  static public <T> Behavior<T> unhandled() {
    return Unhandled();
  }

  /**
   * Return this behavior from message processing to signal that this actor
   * shall terminate voluntarily. If this actor has created child actors then
   * these will be stopped as part of the shutdown procedure. The PostStop
   * signal that results from stopping this actor will NOT be passed to the
   * current behavior, it will be effectively ignored.
   * 
   * @return the inert behavior
   */
  static public <T> Behavior<T> stopped() {
    return Stopped();
  }

  /**
   * A behavior that treats every incoming message as unhandled.
   * 
   * @return the empty behavior
   */
  static public <T> Behavior<T> empty() {
    return Empty();
  }

  /**
   * A behavior that ignores every incoming message and returns “same”.
   * 
   * @return the inert behavior
   */
  static public <T> Behavior<T> ignore() {
    return Ignore();
  }

  /**
   * Behavior decorator that allows you to perform any side-effect before a
   * signal or message is delivered to the wrapped behavior. The wrapped
   * behavior can evolve (i.e. be stateful) without needing to be wrapped in a
   * <code>tap(...)</code> call again.
   * 
   * @param signal
   *          procedure to invoke with the {@link ActorContext} and the
   *          {@link akka.typed.Signal} as arguments before delivering the signal to
   *          the wrapped behavior
   * @param message
   *          procedure to invoke with the {@link ActorContext} and the received
   *          message as arguments before delivering the signal to the wrapped
   *          behavior
   * @param behavior
   *          initial behavior to be wrapped
   * @return the decorated behavior
   */
  static public <T> Behavior<T> tap(Procedure2<ActorContext<T>, Signal> signal, Procedure2<ActorContext<T>, T> message,
      Behavior<T> behavior) {
    return new Tap<T>(signal, message, behavior);
  }

  /**
   * Behavior decorator that copies all received message to the designated
   * monitor {@link akka.typed.ActorRef} before invoking the wrapped behavior. The
   * wrapped behavior can evolve (i.e. be stateful) without needing to be
   * wrapped in a <code>monitor(...)</code> call again.
   * 
   * @param monitor
   *          ActorRef to which to copy all received messages
   * @param behavior
   *          initial behavior to be wrapped
   * @return the decorated behavior
   */
  static public <T> Behavior<T> monitor(ActorRef<T> monitor, Behavior<T> behavior) {
    return new Tap<T>(doNothing(), (ctx, msg) -> monitor.tell(msg), behavior);
  }

  /**
   * Wrap the given behavior such that it is restarted (i.e. reset to its
   * initial state) whenever it throws an exception of the given class or a
   * subclass thereof. Exceptions that are not subtypes of <code>Thr</code> will not be
   * caught and thus lead to the termination of the actor.
   * 
   * It is possible to specify that the actor shall not be restarted but
   * resumed. This entails keeping the same state as before the exception was
   * thrown and is thus less safe. If you use <code>OnFailure.RESUME</code> you should at
   * least not hold mutable data fields or collections within the actor as those
   * might be in an inconsistent state (the exception might have interrupted
   * normal processing); avoiding mutable state is possible by returning a fresh
   * behavior with the new state after every message.
   * 
   * @param clazz
   *          the type of exceptions that shall be caught
   * @param mode
   *          whether to restart or resume the actor upon a caught failure
   * @param initialBehavior
   *          the initial behavior, that is also restored during a restart
   * @return the wrapped behavior
   */
  static public <T, Thr extends Throwable> Behavior<T> restarter(Class<Thr> clazz, OnFailure mode,
      Behavior<T> initialBehavior) {
    final ClassTag<Thr> catcher = akka.japi.Util.classTag(clazz);
    return new Restarter<T, Thr>(initialBehavior, mode == OnFailure.RESUME, initialBehavior, catcher);
  }

  /**
   * Widen the wrapped Behavior by placing a funnel in front of it: the supplied
   * PartialFunction decides which message to pull in (those that it is defined
   * at) and may transform the incoming message to place them into the wrapped
   * Behavior’s type hierarchy. Signals are not transformed.
   *
   * <code><pre>
   * Behavior&lt;String> s = stateless((ctx, msg) -> System.out.println(msg))
   * Behavior&lt;Number> n = widened(s, pf -> pf.
   *         match(BigInteger.class, i -> "BigInteger(" + i + ")").
   *         match(BigDecimal.class, d -> "BigDecimal(" + d + ")")
   *         // drop all other kinds of Number
   *     );
   * </pre></code>
   * 
   * @param behavior
   *          the behavior that will receive the selected messages
   * @param selector
   *          a partial function builder for describing the selection and
   *          transformation
   * @return a behavior of the widened type
   */
  static public <T, U> Behavior<U> widened(Behavior<T> behavior, Function<PFBuilder<U, T>, PFBuilder<U, T>> selector) {
    return new Widened<T, U>(behavior, selector.apply(new PFBuilder<>()).build());
  }

  /**
   * Wrap a behavior factory so that it runs upon PreStart, i.e. behavior
   * creation is deferred to the child actor instead of running within the
   * parent.
   * 
   * @param producer
   *          behavior factory that takes the child actor’s context as argument
   * @return the deferred behavior
   */
  static public <T> Behavior<T> deferred(akka.japi.function.Function<ActorContext<T>, Behavior<T>> producer) {
    return new Deferred<T>(producer);
  }

}
