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
import akka.typed.*;
import akka.typed.internal.Restarter;
import akka.typed.scaladsl.Actor.Widened;
import scala.reflect.ClassTag;

public abstract class Actor {
	/*
	 * This DSL is implemented in Java in order to ensure consistent usability from Java,
	 * taking possible Scala oddities out of the equation. There is some duplication in
	 * the behavior implementations, but that is unavoidable if both DSLs shall offer the
	 * same runtime performance (especially concerning allocations for function converters).
	 */

  private static class Deferred<T> extends Behavior.DeferredBehavior<T> {
    final akka.japi.function.Function<ActorContext<T>, Behavior<T>> producer;

    public Deferred(akka.japi.function.Function<ActorContext<T>, Behavior<T>> producer) {
      this.producer = producer;
    }

    @Override
    public Behavior<T> apply(akka.typed.ActorContext<T> ctx) throws Exception {
      return producer.apply(ctx);
    }
  }

  private static class Immutable<T> extends ExtensibleBehavior<T> {
    final Function2<ActorContext<T>, T, Behavior<T>> message;
    final Function2<ActorContext<T>, Signal, Behavior<T>> signal;

    public Immutable(Function2<ActorContext<T>, T, Behavior<T>> message,
        Function2<ActorContext<T>, Signal, Behavior<T>> signal) {
      this.signal = signal;
      this.message = message;
    }

    @Override
    public Behavior<T> receiveSignal(akka.typed.ActorContext<T> ctx, Signal msg) throws Exception {
      return signal.apply(ctx, msg);
    }

    @Override
    public Behavior<T> receiveMessage(akka.typed.ActorContext<T> ctx, T msg) throws Exception {
      return message.apply(ctx, msg);
    }
  }

  private static class Tap<T> extends ExtensibleBehavior<T> {
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
      else if (behv == Same() || behv == this)
        return Same();
      else if (Behavior.isAlive(behv))
        return new Tap<T>(signal, message, behv);
      else
        return Stopped();
    }

    @Override
    public Behavior<T> receiveSignal(akka.typed.ActorContext<T> ctx, Signal signal) throws Exception {
      this.signal.apply(ctx, signal);
      return canonicalize(Behavior.interpretSignal(behavior, ctx, signal));
    }

    @Override
    public Behavior<T> receiveMessage(akka.typed.ActorContext<T> ctx, T msg) throws Exception {
      message.apply(ctx, msg);
      return canonicalize(Behavior.interpretMessage(behavior, ctx, msg));
    }
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
   * This constructor is called immutable because the behavior instance doesn't
   * have or close over any mutable state. Processing the next message
   * results in a new behavior that can potentially be different from this one.
   * State is updated by returning a new behavior that holds the new immutable
   * state. If no change is desired, use {@link #same}.
   *
   * @param message
   *          the function that describes how this actor reacts to the next
   *          message
   * @return the behavior
   */
  static public <T> Behavior<T> immutable(Function2<ActorContext<T>, T, Behavior<T>> message) {
    return new Immutable<T>(message, unhandledFun());
  }

  /**
   * Construct an actor behavior that can react to both incoming messages and
   * lifecycle signals. After spawning this actor from another actor (or as the
   * guardian of an {@link akka.typed.ActorSystem}) it will be executed within an
   * {@link ActorContext} that allows access to the system, spawning and watching
   * other actors, etc.
   *
   * This constructor is called immutable because the behavior instance doesn't
   * have or close over any mutable state. Processing the next message
   * results in a new behavior that can potentially be different from this one.
   * State is updated by returning a new behavior that holds the new immutable
   * state. If no change is desired, use {@link #same}.
   *
   * @param message
   *          the function that describes how this actor reacts to the next
   *          message
   * @param signal
   *          the function that describes how this actor reacts to the given
   *          signal
   * @return the behavior
   */
  static public <T> Behavior<T> immutable(Function2<ActorContext<T>, T, Behavior<T>> message,
      Function2<ActorContext<T>, Signal, Behavior<T>> signal) {
    return new Immutable<T>(message, signal);
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
   * behavior can evolve (i.e. be immutable) without needing to be wrapped in a
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
   * wrapped behavior can evolve (i.e. return different behavior) without needing to be
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
   * It is possible to specify different supervisor strategies, such as restart,
   * resume, backoff.
   *
   * @param clazz
   *          the type of exceptions that shall be caught
   * @param strategy
   *          whether to restart, resume, or backoff the actor upon a caught failure
   * @param initialBehavior
   *          the initial behavior, that is also restored during a restart
   * @return the wrapped behavior
   */
  static public <T, Thr extends Throwable> Behavior<T> restarter(Class<Thr> clazz, SupervisorStrategy strategy,
      Behavior<T> initialBehavior) {
    final ClassTag<Thr> catcher = akka.japi.Util.classTag(clazz);
    return Restarter.apply(Behavior.validateAsInitial(initialBehavior), strategy, catcher);
  }

  /**
   * Widen the wrapped Behavior by placing a funnel in front of it: the supplied
   * PartialFunction decides which message to pull in (those that it is defined
   * at) and may transform the incoming message to place them into the wrapped
   * Behavior’s type hierarchy. Signals are not transformed.
   *
   * <code><pre>
   * Behavior&lt;String> s = immutable((ctx, msg) -> {
   *     System.out.println(msg);
   *     return same();
   *   });
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

  /**
   * Factory for creating a <code>MutableBehavior</code> that typically holds mutable state as
   * instance variables in the concrete <code>MutableBehavior</code> implementation class.
   *
   * Creation of the behavior instance is deferred, i.e. it is created via the <code>producer</code>
   * function. The reason for the deferred creation is to avoid sharing the same instance in
   * multiple actors, and to create a new instance when the actor is restarted.
   *
   * @param producer
   *          behavior factory that takes the child actor’s context as argument
   * @return the deferred behavior
   */
  static public <T> Behavior<T> mutable(akka.japi.function.Function<ActorContext<T>, MutableBehavior<T>> producer) {
    return deferred(ctx -> producer.apply(ctx));
  }

  /**
   * Mutable behavior can be implemented by extending this class and implement the
   * abstract method {@link MutableBehavior#onMessage} and optionally override
   * {@link MutableBehavior#onSignal}.
   *
   * Instances of this behavior should be created via {@link Actor#mutable} and if
   * the {@link ActorContext} is needed it can be passed as a constructor parameter
   * from the factory function.
   *
   * @see Actor#mutable
   */
  static public abstract class MutableBehavior<T> extends ExtensibleBehavior<T> {
    @Override
    final public Behavior<T> receiveMessage(akka.typed.ActorContext<T> ctx, T msg) throws Exception {
      return onMessage(msg);
    }

    /**
     * Implement this method to process an incoming message and return the next behavior.
     *
     * The returned behavior can in addition to normal behaviors be one of the canned special objects:
     * <ul>
     * <li>returning `stopped` will terminate this Behavior</li>
     * <li>returning `this` or `same` designates to reuse the current Behavior</li>
     * <li>returning `unhandled` keeps the same Behavior and signals that the message was not yet handled</li>
     * </ul>
     *
     */
    public abstract Behavior<T> onMessage(T msg) throws Exception;

    @Override
    final public Behavior<T> receiveSignal(akka.typed.ActorContext<T> ctx, Signal msg) throws Exception {
      return onSignal(msg);
    }

    /**
     * Override this method to process an incoming {@link akka.typed.Signal} and return the next behavior.
     * This means that all lifecycle hooks, ReceiveTimeout, Terminated and Failed messages
     * can initiate a behavior change.
     *
     * The returned behavior can in addition to normal behaviors be one of the canned special objects:
     * <ul>
     * <li>returning `stopped` will terminate this Behavior</li>
     * <li>returning `this` or `same` designates to reuse the current Behavior</li>
     * <li>returning `unhandled` keeps the same Behavior and signals that the message was not yet handled</li>
     * </ul>
     *
     * By default, this method returns `unhandled`.
     */
    public Behavior<T> onSignal(Signal msg) throws Exception {
      return unhandled();
    }
  }

}
