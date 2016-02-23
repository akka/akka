/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.actor

/**
 * Actor base class that should be extended to create an actor with a stash.
 *
 * The stash enables an actor to temporarily stash away messages that can not or
 * should not be handled using the actor's current behavior.
 * <p/>
 * Example:
 * <pre>
 *   public class MyActorWithStash extends UntypedActorWithStash {
 *     int count = 0;
 *     public void onReceive(Object msg) {
 *       if (msg instanceof String) {
 *         if (count < 0) {
 *           getSender().tell(new Integer(((String) msg).length()), getSelf());
 *         } else if (count == 2) {
 *           count = -1;
 *           unstashAll();
 *         } else {
 *           count += 1;
 *           stash();
 *         }
 *       }
 *     }
 *   }
 * </pre>
 * Note that the subclasses of `UntypedActorWithStash` by default request a Deque based mailbox since this class
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
 * For a `Stash` based actor that enforces unbounded deques see [[akka.actor.UntypedActorWithUnboundedStash]].
 * There is also an unrestricted version [[akka.actor.UntypedActorWithUnrestrictedStash]] that does not
 * enforce the mailbox type.
 */
abstract class UntypedActorWithStash extends UntypedActor with Stash

/**
 * Actor base class with `Stash` that enforces an unbounded deque for the actor. The proper mailbox has to be configured
 * manually, and the mailbox should extend the [[akka.dispatch.DequeBasedMessageQueueSemantics]] marker trait.
 * See [[akka.actor.UntypedActorWithStash]] for details on how `Stash` works.
 */
abstract class UntypedActorWithUnboundedStash extends UntypedActor with UnboundedStash

/**
 * Actor base class with `Stash` that does not enforce any mailbox type. The mailbox of the actor has to be configured
 * manually. See [[akka.actor.UntypedActorWithStash]] for details on how `Stash` works.
 */
abstract class UntypedActorWithUnrestrictedStash extends UntypedActor with UnrestrictedStash
