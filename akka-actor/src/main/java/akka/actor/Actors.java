/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor;

import akka.japi.Creator;
import akka.remote.RemoteSupport;

import com.eaio.uuid.UUID;

/**
 * JAVA API for
 *  - creating actors,
 *  - creating remote actors,
 *  - locating actors
 */
public class Actors {
    /**
     *
     * @return The actor registry
     */
    public static ActorRegistry registry() {
        return Actor$.MODULE$.registry();
    }

    /**
     *
     * @return
     * @throws UnsupportedOperationException If remoting isn't configured
     * @throws ModuleNotAvailableException If the class for the remote support cannot be loaded
     */
    public static RemoteSupport remote() {
        return Actor$.MODULE$.remote();
    }

  /**
   * NOTE: Use this convenience method with care, do NOT make it possible to get a reference to the
   * UntypedActor instance directly, but only through its 'ActorRef' wrapper reference.
   * <p/>
   * Creates an ActorRef out of the Actor. Allows you to pass in the instance for the UntypedActor.
   * Only use this method when you need to pass in constructor arguments into the 'UntypedActor'.
   * <p/>
   * You use it by implementing the UntypedActorFactory interface.
   * Example in Java:
   * <pre>
   *   ActorRef actor = Actors.actorOf(new UntypedActorFactory() {
   *     public UntypedActor create() {
   *       return new MyUntypedActor("service:name", 5);
   *     }
   *   }, "my-actor-address");
   *   actor.tell(message, context);
   *   actor.stop();
   * </pre>
   */
    public static ActorRef actorOf(final Creator<Actor> factory, final String address) {
        return Actor$.MODULE$.actorOf(factory, address);
    }

  /**
   * NOTE: Use this convenience method with care, do NOT make it possible to get a reference to the
   * UntypedActor instance directly, but only through its 'ActorRef' wrapper reference.
   * <p/>
   * Creates an ActorRef out of the Actor. Allows you to pass in the instance for the UntypedActor.
   * Only use this method when you need to pass in constructor arguments into the 'UntypedActor'.
   * <p/>
   * You use it by implementing the UntypedActorFactory interface.
   * Example in Java:
   * <pre>
   *   ActorRef actor = Actors.actorOf(new UntypedActorFactory() {
   *     public UntypedActor create() {
   *       return new MyUntypedActor("service:name", 5);
   *     }
   *   });
   *   actor.tell(message, context);
   *   actor.stop();
   * </pre>
   */
    public static ActorRef actorOf(final Creator<Actor> factory) {
        return Actor$.MODULE$.actorOf(factory, new UUID().toString());
    }

  /**
   * Creates an ActorRef out of the Actor type represented by the class provided.
   *  Example in Java:
   * <pre>
   *   ActorRef actor = Actors.actorOf(MyUntypedActor.class, "my-actor-address");
   *   actor.tell(message, context);
   *   actor.stop();
   * </pre>
   * You can create and start the actor in one statement like this:
   * <pre>
   *   val actor = Actors.actorOf(MyActor.class, "my-actor-address");
   * </pre>
   */
    public static ActorRef actorOf(final Class<? extends Actor> type, final String address) {
        return Actor$.MODULE$.actorOf(type, address);
    }

  /**
   * Creates an ActorRef out of the Actor type represented by the class provided.
   *  Example in Java:
   * <pre>
   *   ActorRef actor = Actors.actorOf(MyUntypedActor.class, "my-actor-address");
   *   actor.tell(message, context);
   *   actor.stop();
   * </pre>
   * You can create and start the actor in one statement like this:
   * <pre>
   *   val actor = Actors.actorOf(MyActor.class);
   * </pre>
   */
    public static ActorRef actorOf(final Class<? extends Actor> type) {
        return Actor$.MODULE$.actorOf(type, new UUID().toString());
    }

    /**
     * The message that is sent when an Actor gets a receive timeout.
     * <pre>
     *  if( message == receiveTimeout() ) {
     *    //Timed out
     *  }
     * </pre>
     * @return the single instance of ReceiveTimeout
     */
    public final static ReceiveTimeout$ receiveTimeout() {
        return ReceiveTimeout$.MODULE$;
    }

    /**
     * The message that when sent to an Actor kills it by throwing an exception.
     * <pre>
     *  actor.tell(kill());
     * </pre>
     * @return the single instance of Kill
     */
    public final static Kill$ kill() {
        return Kill$.MODULE$;
    }


    /**
     * The message that when sent to an Actor shuts it down by calling 'stop'.
     * <pre>
     *  actor.tell(poisonPill());
     * </pre>
     * @return the single instance of PoisonPill
     */
    public final static PoisonPill$ poisonPill() {
        return PoisonPill$.MODULE$;
    }
}
