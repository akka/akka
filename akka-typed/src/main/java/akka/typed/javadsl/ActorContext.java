/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed.javadsl;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import akka.actor.Cancellable;
import akka.typed.ActorRef;
import akka.typed.ActorSystem;
import akka.typed.Behavior;
import akka.typed.DeploymentConfig;
import scala.concurrent.ExecutionContextExecutor;
import scala.concurrent.duration.FiniteDuration;

/**
 * An Actor is given by the combination of a {@link akka.typed.Behavior} and a context in which
 * this behavior is executed. As per the Actor Model an Actor can perform the
 * following actions when processing a message:
 *
 * <ul>
 * <li>send a finite number of messages to other Actors it knows</li>
 * <li>create a finite number of Actors</li>
 * <li>designate the behavior for the next message</li>
 * </ul>
 *
 * In Akka the first capability is accessed by using the {@link akka.typed.ActorRef#tell}
 * method, the second is provided by {@link akka.typed.ActorContext#spawn} and the
 * third is implicit in the signature of {@link akka.typed.Behavior} in that the next behavior
 * is always returned from the message processing logic.
 *
 * An <code>ActorContext</code> in addition provides access to the Actor’s own identity
 * ({@link #getSelf “self”}), the {@link akka.typed.ActorSystem} it is part of, methods for querying the list
 * of child Actors it created, access to {@link akka.typed.Terminated DeathWatch} and timed
 * message scheduling.
 */
public interface ActorContext<T> {

  /**
   * The identity of this Actor, bound to the lifecycle of this Actor instance.
   * An Actor with the same name that lives before or after this instance will
   * have a different {@link akka.typed.ActorRef}.
   */
  public ActorRef<T> getSelf();

  /**
   * Return the mailbox capacity that was configured by the parent for this
   * actor.
   */
  public int getMailboxCapacity();

  /**
   * The {@link akka.typed.ActorSystem} to which this Actor belongs.
   */
  public ActorSystem<Void> getSystem();

  /**
   * The list of child Actors created by this Actor during its lifetime that are
   * still alive, in no particular order.
   */
  public List<ActorRef<Void>> getChildren();

  /**
   * The named child Actor if it is alive.
   */
  public Optional<ActorRef<Void>> getChild(String name);

  /**
   * Create a child Actor from the given {@link akka.typed.Behavior} under a randomly chosen name.
   * It is good practice to name Actors wherever practical.
   */
  public <U> ActorRef<U> spawnAnonymous(Behavior<U> behavior);

  /**
   * Create a child Actor from the given {@link akka.typed.Behavior} under a randomly chosen name.
   * It is good practice to name Actors wherever practical.
   */
  public <U> ActorRef<U> spawnAnonymous(Behavior<U> behavior, DeploymentConfig deployment);

  /**
   * Create a child Actor from the given {@link akka.typed.Behavior} and with the given name.
   */
  public <U> ActorRef<U> spawn(Behavior<U> behavior, String name);

  /**
   * Create a child Actor from the given {@link akka.typed.Behavior} and with the given name.
   */
  public <U> ActorRef<U> spawn(Behavior<U> behavior, String name, DeploymentConfig deployment);

  /**
   * Force the child Actor under the given name to terminate after it finishes
   * processing its current message. Nothing happens if the ActorRef does not
   * refer to a current child actor.
   *
   * @return whether the passed-in {@link akka.typed.ActorRef} points to a current child Actor
   */
  public boolean stop(ActorRef<?> child);

  /**
   * Register for {@link akka.typed.Terminated} notification once the Actor identified by the
   * given {@link akka.typed.ActorRef} terminates. This notification is also generated when the
   * {@link akka.typed.ActorSystem} to which the referenced Actor belongs is declared as failed
   * (e.g. in reaction to being unreachable).
   */
  public <U> ActorRef<U> watch(ActorRef<U> other);

  /**
   * Revoke the registration established by {@link #watch}. A {@link akka.typed.Terminated}
   * notification will not subsequently be received for the referenced Actor.
   */
  public <U> ActorRef<U> unwatch(ActorRef<U> other);

  /**
   * Schedule the sending of a notification in case no other message is received
   * during the given period of time. The timeout starts anew with each received
   * message. Provide <code>scala.concurrent.duration.Duration.Undefined</code> to switch off this mechanism.
   */
  public void setReceiveTimeout(FiniteDuration d, T msg);

  /**
   * Cancel the sending of receive timeout notifications.
   */
  public void cancelReceiveTimeout();

  /**
   * Schedule the sending of the given message to the given target Actor after
   * the given time period has elapsed. The scheduled action can be cancelled by
   * invoking {@link akka.actor.Cancellable#cancel} on the returned handle.
   */
  public <U> Cancellable schedule(FiniteDuration delay, ActorRef<U> target, U msg);

  /**
   * This Actor’s execution context. It can be used to run asynchronous tasks
   * like <code>scala.concurrent.Future</code> combinators.
   */
  public ExecutionContextExecutor getExecutionContext();

  /**
   * Create a child actor that will wrap messages such that other Actor’s
   * protocols can be ingested by this Actor. You are strongly advised to cache
   * these ActorRefs or to stop them when no longer needed.
   *
   * The name of the child actor will be composed of a unique identifier
   * starting with a dollar sign to which the given <code>name</code> argument is appended,
   * with an inserted hyphen between these two parts. Therefore the given <code>name</code>
   * argument does not need to be unique within the scope of the parent actor.
   */
  public <U> ActorRef<U> createAdapter(Function<U, T> f, String name);

  /**
   * Create an anonymous child actor that will wrap messages such that other
   * Actor’s protocols can be ingested by this Actor. You are strongly advised
   * to cache these ActorRefs or to stop them when no longer needed.
   */
  public <U> ActorRef<U> createAdapter(Function<U, T> f);

}
