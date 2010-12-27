/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import akka.dispatch._
import akka.config.Supervision._
import akka.japi.{Creator, Procedure}

import java.net.InetSocketAddress

import scala.reflect.BeanProperty

/**
 * Subclass this abstract class to create a MDB-style untyped actor.
 * <p/>
 * This class is meant to be used from Java.
 * <p/>
 * Here is an example on how to create and use an UntypedActor:
 * <pre>
 *  public class SampleUntypedActor extends UntypedActor {
 *    public void onReceive(Object message) throws Exception {
 *      if (message instanceof String) {
 *        String msg = (String)message;
 *
 *        if (msg.equals("UseReply")) {
 *          // Reply to original sender of message using the 'replyUnsafe' method
 *          getContext().replyUnsafe(msg + ":" + getContext().getUuid());
 *
 *        } else if (msg.equals("UseSender") && getContext().getSender().isDefined()) {
 *          // Reply to original sender of message using the sender reference
 *          // also passing along my own refererence (the context)
 *          getContext().getSender().get().sendOneWay(msg, context);
 *
 *        } else if (msg.equals("UseSenderFuture") && getContext().getSenderFuture().isDefined()) {
 *          // Reply to original sender of message using the sender future reference
 *          getContext().getSenderFuture().get().completeWithResult(msg);
 *
 *        } else if (msg.equals("SendToSelf")) {
 *          // Send message to the actor itself recursively
 *          getContext().sendOneWay(msg)
 *
 *        } else if (msg.equals("ForwardMessage")) {
 *          // Retreive an actor from the ActorRegistry by ID and get an ActorRef back
 *          ActorRef actorRef = ActorRegistry.actorsFor("some-actor-id").head();
 *
 *        } else throw new IllegalArgumentException("Unknown message: " + message);
 *      } else throw new IllegalArgumentException("Unknown message: " + message);
 *    }
 *
 *    public static void main(String[] args) {
 *      ActorRef actor = UntypedActor.actorOf(SampleUntypedActor.class);
 *      actor.start();
 *      actor.sendOneWay("SendToSelf");
 *      actor.stop();
 *    }
 *  }
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class UntypedActor extends Actor {

  def logger = log.logger //Give the Java guys a break

  def getContext(): ActorRef = self

  final protected def receive = {
    case msg => onReceive(msg)
  }

  /**
   * Java API for become
   */
  def become(behavior: Procedure[Any]):Unit = become(behavior,false)

  /*
   * Java API for become with optional discardOld
   */
  def become(behavior: Procedure[Any], discardOld: Boolean): Unit =
    super.become({ case msg => behavior.apply(msg) }, discardOld)

  @throws(classOf[Exception])
  def onReceive(message: Any): Unit
}

/**
 * Factory closure for an UntypedActor, to be used with 'UntypedActor.actorOf(factory)'.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait UntypedActorFactory extends Creator[Actor]

/**
 * Extend this abstract class to create a remote UntypedActor.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class RemoteUntypedActor(address: InetSocketAddress) extends UntypedActor {
  def this(hostname: String, port: Int) = this(new InetSocketAddress(hostname, port))
  self.makeRemote(address)
}

/**
 * Factory object for creating and managing 'UntypedActor's. Meant to be used from Java.
 * <p/>
 * Example on how to create an actor:
 * <pre>
 *   ActorRef actor = UntypedActor.actorOf(MyUntypedActor.class);
 *   actor.start();
 *   actor.sendOneWay(message, context)
 *   actor.stop();
 * </pre>
 * You can create and start the actor in one statement like this:
 * <pre>
 *   ActorRef actor = UntypedActor.actorOf(MyUntypedActor.class).start();
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object UntypedActor {

  /**
   * Creates an ActorRef out of the Actor type represented by the class provided.
   *  Example in Java:
   * <pre>
   *   ActorRef actor = UntypedActor.actorOf(MyUntypedActor.class);
   *   actor.start();
   *   actor.sendOneWay(message, context);
   *   actor.stop();
   * </pre>
   * You can create and start the actor in one statement like this:
   * <pre>
   *   val actor = actorOf(classOf[MyActor]).start
   * </pre>
   */
  def actorOf[T <: Actor](clazz: Class[T]): ActorRef = Actor.actorOf(clazz)

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
   *   ActorRef actor = UntypedActor.actorOf(new UntypedActorFactory() {
   *     public UntypedActor create() {
   *       return new MyUntypedActor("service:name", 5);
   *     }
   *   });
   *   actor.start();
   *   actor.sendOneWay(message, context);
   *   actor.stop();
   * </pre>
   */
  def actorOf(factory: UntypedActorFactory): ActorRef = Actor.actorOf(factory.create)
}
