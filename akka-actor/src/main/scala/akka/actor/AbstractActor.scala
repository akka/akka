/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.actor

/**
 * Java API: compatible with lambda expressions
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
object AbstractActor {
  /**
   * emptyBehavior is a Receive-expression that matches no messages at all, ever.
   */
  final val emptyBehavior = Actor.emptyBehavior
}

/**
 * Java API: compatible with lambda expressions
 *
 * Actor base class that should be extended to create Java actors that use lambdas.
 * <p/>
 * Example:
 * <pre>
 * public class MyActor extends AbstractActor {
 *   int count = 0;
 *
 *   public MyActor() {
 *     receive(ReceiveBuilder.
 *       match(Double.class, d -> {
 *         sender().tell(d.isNaN() ? 0 : d, self());
 *       }).
 *       match(Integer.class, i -> {
 *         sender().tell(i * 10, self());
 *       }).
 *       match(String.class, s -> s.startsWith("foo"), s -> {
 *         sender().tell(s.toUpperCase(), self());
 *       }).build()
 *     );
 *   }
 * }
 * </pre>
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
abstract class AbstractActor extends Actor {

  private var _receive: Receive = null

  /**
   * Set up the initial receive behavior of the Actor.
   *
   * @param receive  The receive behavior.
   */
  @throws(classOf[IllegalActorStateException])
  protected def receive(receive: Receive): Unit =
    if (_receive == null) _receive = receive
    else throw IllegalActorStateException("Actor behavior has already been set with receive(...), " +
      "use context().become(...) to change it later")

  /**
   * Returns this AbstractActor's AbstractActorContext
   * The AbstractActorContext is not thread safe so do not expose it outside of the
   * AbstractActor.
   */
  def getContext(): AbstractActorContext = context.asInstanceOf[AbstractActorContext]

  override def receive =
    if (_receive != null) _receive
    else throw IllegalActorStateException("Actor behavior has not been set with receive(...)")
}

/**
 * Java API: compatible with lambda expressions
 *
 * Actor base class that mixes in logging into the Actor.
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
abstract class AbstractLoggingActor extends AbstractActor with ActorLogging

/**
 * Java API: compatible with lambda expressions
 *
 * Actor base class that should be extended to create an actor with a stash.
 *
 * The stash enables an actor to temporarily stash away messages that can not or
 * should not be handled using the actor's current behavior.
 * <p/>
 * Example:
 * <pre>
 * public class MyActorWithStash extends AbstractActorWithStash {
 *   int count = 0;
 *
 *   public MyActorWithStash() {
 *     receive(ReceiveBuilder. match(String.class, s -> {
 *       if (count < 0) {
 *         sender().tell(new Integer(s.length()), self());
 *       } else if (count == 2) {
 *         count = -1;
 *         unstashAll();
 *       } else {
 *         count += 1;
 *         stash();
 *       }}).build()
 *     );
 *   }
 * }
 * </pre>
 * Note that the subclasses of `AbstractActorWithStash` by default request a Deque based mailbox since this class
 * implements the `RequiresMessageQueue&lt;DequeBasedMessageQueueSemantics&gt;` marker interface.
 * You can override the default mailbox provided when `DequeBasedMessageQueueSemantics` are requested via config:
 * <pre>
 *   akka.actor.mailbox.requirements {
 *     "akka.dispatch.BoundedDequeBasedMessageQueueSemantics" = your-custom-mailbox
 *   }
 * </pre>
 * Alternatively, you can add your own requirement marker to the actor and configure a mailbox type to be used
 * for your marker.
 *
 * For a `Stash` based actor that enforces unbounded deques see [[akka.actor.AbstractActorWithUnboundedStash]].
 * There is also an unrestricted version [[akka.actor.AbstractActorWithUnrestrictedStash]] that does not
 * enforce the mailbox type.
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
abstract class AbstractActorWithStash extends AbstractActor with Stash

/**
 * Java API: compatible with lambda expressions
 *
 * Actor base class with `Stash` that enforces an unbounded deque for the actor. The proper mailbox has to be configured
 * manually, and the mailbox should extend the [[akka.dispatch.DequeBasedMessageQueueSemantics]] marker trait.
 * See [[akka.actor.AbstractActorWithStash]] for details on how `Stash` works.
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
abstract class AbstractActorWithUnboundedStash extends AbstractActor with UnboundedStash

/**
 * Java API: compatible with lambda expressions
 *
 * Actor base class with `Stash` that does not enforce any mailbox type. The mailbox of the actor has to be configured
 * manually. See [[akka.actor.AbstractActorWithStash]] for details on how `Stash` works.
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
abstract class AbstractActorWithUnrestrictedStash extends AbstractActor with UnrestrictedStash
