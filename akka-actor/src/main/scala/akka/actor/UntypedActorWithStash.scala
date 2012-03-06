/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
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
 *           getSender().tell(new Integer(((String) msg).length()));
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
 */
abstract class UntypedActorWithStash extends UntypedActor with Stash
