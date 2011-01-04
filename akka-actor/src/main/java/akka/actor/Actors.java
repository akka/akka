package akka.actor;

import akka.japi.Creator;
import akka.remoteinterface.RemoteSupport;

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
   *   });
   *   actor.start();
   *   actor.sendOneWay(message, context);
   *   actor.stop();
   * </pre>
   */
    public static ActorRef actorOf(final Creator<Actor> factory) {
        return Actor$.MODULE$.actorOf(factory);
    }

  /**
   * Creates an ActorRef out of the Actor type represented by the class provided.
   *  Example in Java:
   * <pre>
   *   ActorRef actor = Actors.actorOf(MyUntypedActor.class);
   *   actor.start();
   *   actor.sendOneWay(message, context);
   *   actor.stop();
   * </pre>
   * You can create and start the actor in one statement like this:
   * <pre>
   *   val actor = Actors.actorOf(MyActor.class).start();
   * </pre>
   */
    public static ActorRef actorOf(final Class<? extends Actor> type) {
        return Actor$.MODULE$.actorOf(type);
    }
}