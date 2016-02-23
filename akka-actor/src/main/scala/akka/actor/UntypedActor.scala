/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.actor

/**
 * Actor base trait that should be extended by or mixed to create an Actor with the semantics of the 'Actor Model':
 * <a href="http://en.wikipedia.org/wiki/Actor_model">http://en.wikipedia.org/wiki/Actor_model</a>
 *
 * This class is the Java cousin to the [[akka.actor.Actor]] Scala interface.
 * Subclass this abstract class to create a MDB-style untyped actor.
 *
 * An actor has a well-defined (non-cyclic) life-cycle.
 *  - ''RUNNING'' (created and started actor) - can receive messages
 *  - ''SHUTDOWN'' (when 'stop' or 'exit' is invoked) - can't do anything
 *
 * The Actor's own [[akka.actor.ActorRef]] is available as `getSelf()`, the current
 * message’s sender as `getSender()` and the [[akka.actor.UntypedActorContext]] as
 * `getContext()`. The only abstract method is `onReceive()` which is invoked for
 * each processed message unless dynamically overridden using `getContext().become()`.
 *
 * Here is an example on how to create and use an UntypedActor:
 *
 * {{{
 *  public class SampleUntypedActor extends UntypedActor {
 *
 *    public static class Reply implements java.io.Serializable {
 *      final public ActorRef sender;
 *      final public Result result;
 *      Reply(ActorRef sender, Result result) {
 *        this.sender = sender;
 *        this.result = result;
 *      }
 *    }
 *
 *   private static SupervisorStrategy strategy = new OneForOneStrategy(10, Duration.create("1 minute"),
 *     new Function<Throwable, Directive>() {
 *       @Override
 *       public Directive apply(Throwable t) {
 *         if (t instanceof ArithmeticException) {
 *           return resume();
 *         } else if (t instanceof NullPointerException) {
 *           return restart();
 *         } else if (t instanceof IllegalArgumentException) {
 *           return stop();
 *         } else {
 *           return escalate();
 *         }
 *       }
 *     });
 *
 *   @Override
 *   public SupervisorStrategy supervisorStrategy() {
 *     return strategy;
 *    }
 *
 *    public void onReceive(Object message) throws Exception {
 *      if (message instanceof String) {
 *        String msg = (String) message;
 *
 *        if (msg.equals("UseSender")) {
 *          // Reply to original sender of message
 *          getSender().tell(msg, getSelf());
 *
 *        } else if (msg.equals("SendToSelf")) {
 *          // Send message to the actor itself recursively
 *          getSelf().tell("SomeOtherMessage", getSelf());
 *
 *        } else if (msg.equals("ErrorKernelWithDirectReply")) {
 *          // Send work to one-off child which will reply directly to original sender
 *          getContext().actorOf(Props.create(Worker.class)).tell("DoSomeDangerousWork", getSender());
 *
 *        } else if (msg.equals("ErrorKernelWithReplyHere")) {
 *          // Send work to one-off child and collect the answer, reply handled further down
 *          getContext().actorOf(Props.create(Worker.class)).tell("DoWorkAndReplyToMe", getSelf());
 *
 *        } else {
 *          unhandled(message);
 *        }
 *
 *      } else if (message instanceof Reply) {
 *
 *        final Reply reply = (Reply) message;
 *        // might want to do some processing/book-keeping here
 *        reply.sender.tell(reply.result, getSelf());
 *
 *      } else {
 *        unhandled(message);
 *      }
 *    }
 *  }
 * }}}
 */
abstract class UntypedActor extends Actor {

  /**
   * To be implemented by concrete UntypedActor, this defines the behavior of the
   * UntypedActor.
   */
  @throws(classOf[Exception])
  def onReceive(message: Any): Unit

  /**
   * Returns this UntypedActor's UntypedActorContext
   * The UntypedActorContext is not thread safe so do not expose it outside of the
   * UntypedActor.
   */
  def getContext(): UntypedActorContext = context.asInstanceOf[UntypedActorContext]

  /**
   * Returns the ActorRef for this actor.
   */
  def getSelf(): ActorRef = self

  /**
   * The reference sender Actor of the currently processed message. This is
   * always a legal destination to send to, even if there is no logical recipient
   * for the reply, in which case it will be sent to the dead letter mailbox.
   */
  def getSender(): ActorRef = sender()

  /**
   * User overridable definition the strategy to use for supervising
   * child actors.
   */
  override def supervisorStrategy: SupervisorStrategy = super.supervisorStrategy

  /**
   * User overridable callback.
   * <p/>
   * Is called when an Actor is started.
   * Actor are automatically started asynchronously when created.
   * Empty default implementation.
   */
  @throws(classOf[Exception])
  override def preStart(): Unit = super.preStart()

  /**
   * User overridable callback.
   * <p/>
   * Is called asynchronously after 'actor.stop()' is invoked.
   * Empty default implementation.
   */
  @throws(classOf[Exception])
  override def postStop(): Unit = super.postStop()

  /**
   * User overridable callback: '''By default it disposes of all children and then calls `postStop()`.'''
   * <p/>
   * Is called on a crashed Actor right BEFORE it is restarted to allow clean
   * up of resources before Actor is terminated.
   */
  @throws(classOf[Exception])
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = super.preRestart(reason, message)

  /**
   * User overridable callback: By default it calls `preStart()`.
   * <p/>
   * Is called right AFTER restart on the newly created Actor to allow reinitialization after an Actor crash.
   */
  @throws(classOf[Exception])
  override def postRestart(reason: Throwable): Unit = super.postRestart(reason)

  final def receive = { case msg ⇒ onReceive(msg) }

  /**
   * Recommended convention is to call this method if the message
   * isn't handled in [[#onReceive]] (e.g. unknown message type).
   * By default it fails with either a [[akka.actor.DeathPactException]] (in
   * case of an unhandled [[akka.actor.Terminated]] message) or publishes an [[akka.actor.UnhandledMessage]]
   * to the actor's system's [[akka.event.EventStream]].
   */
  override def unhandled(message: Any): Unit = super.unhandled(message)

}

