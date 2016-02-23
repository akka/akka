/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.actor

import scala.concurrent.duration._
import akka.pattern.ask
import scala.concurrent.Await
import akka.util.Helpers.ConfigOps

/**
 * This object contains elements which make writing actors and related code
 * more concise, e.g. when trying out actors in the REPL.
 *
 * For the communication of non-actor code with actors, you may use anonymous
 * actors tailored to this job:
 *
 * {{{
 * import ActorDSL._
 * import scala.concurrent.util.duration._
 *
 * implicit val system: ActorSystem = ...
 *
 * implicit val i = inbox()
 * someActor ! someMsg // replies will go to `i`
 *
 * val reply = i.receive()
 * val transformedReply = i.select(5 seconds) {
 *   case x: Int => 2 * x
 * }
 * }}}
 *
 * The `receive` and `select` methods are synchronous, i.e. they block the
 * calling thread until an answer from the actor is received or the timeout
 * expires. The default timeout is taken from configuration item
 * `akka.actor.dsl.default-timeout`.
 *
 * When defining actors in the REPL, say, you may want to have a look at the
 * `Act` trait:
 *
 * {{{
 * import ActorDSL._
 *
 * val system: ActorSystem = ...
 *
 * val a = actor(system, "fred")(new Act {
 *     val b = actor("barney")(new Act {
 *         ...
 *       })
 *
 *     become {
 *       case msg => ...
 *     }
 *   })
 * }}}
 *
 * Note that `actor` can be used with an implicit [[akka.actor.ActorRefFactory]]
 * as shown with `"barney"` (where the [[akka.actor.ActorContext]] serves this
 * purpose), but since nested declarations share the same
 * lexical context `"fred"`’s ActorContext would be ambiguous
 * if the [[akka.actor.ActorSystem]] were declared `implicit` (this could also
 * be circumvented by shadowing the name `system` within `"fred"`).
 *
 * <b>Note:</b> If you want to use an `Act with Stash`, you should use the
 * `ActWithStash` trait in order to have the actor get the necessary deque-based
 * mailbox setting.
 */
object ActorDSL extends dsl.Inbox with dsl.Creators {

  protected object Extension extends ExtensionId[Extension] with ExtensionIdProvider {

    override def lookup = Extension

    override def createExtension(system: ExtendedActorSystem): Extension = new Extension(system)

    /**
     * Java API: retrieve the ActorDSL extension for the given system.
     */
    override def get(system: ActorSystem): Extension = super.get(system)
  }

  protected class Extension(val system: ExtendedActorSystem) extends akka.actor.Extension with InboxExtension {

    private case class MkChild(props: Props, name: String) extends NoSerializationVerificationNeeded
    private val boss = system.systemActorOf(Props(
      new Actor {
        def receive = {
          case MkChild(props, name) ⇒ sender() ! context.actorOf(props, name)
          case any                  ⇒ sender() ! any
        }
      }), "dsl").asInstanceOf[RepointableActorRef]

    lazy val config = system.settings.config.getConfig("akka.actor.dsl")

    val DSLDefaultTimeout = config.getMillisDuration("default-timeout")

    def mkChild(p: Props, name: String): ActorRef =
      if (boss.isStarted)
        boss.underlying.asInstanceOf[ActorCell].attachChild(p, name, systemService = true)
      else {
        implicit val timeout = system.settings.CreationTimeout
        Await.result(boss ? MkChild(p, name), timeout.duration).asInstanceOf[ActorRef]
      }
  }

}

/**
 * An Inbox is an actor-like object which is interrogated from the outside.
 * It contains an actor whose reference can be passed to other actors as
 * usual and it can watch other actors’ lifecycle.
 */
abstract class Inbox {

  /**
   * Receive the next message from this Inbox. This call will return immediately
   * if the internal actor previously received a message, or it will block for
   * up to the specified duration to await reception of a message. If no message
   * is received a [[java.util.concurrent.TimeoutException]] will be raised.
   */
  @throws(classOf[java.util.concurrent.TimeoutException])
  def receive(max: FiniteDuration): Any

  /**
   * Have the internal actor watch the target actor. When the target actor
   * terminates a [[Terminated]] message will be received.
   */
  def watch(target: ActorRef): Unit

  /**
   * Obtain a reference to the internal actor, which can then for example be
   * registered with the event stream or whatever else you may want to do with
   * an [[ActorRef]].
   */
  def getRef(): ActorRef

  /**
   * Have the internal actor act as the sender of the given message which will
   * be sent to the given target. This means that should the target actor reply
   * then those replies will be received by this Inbox.
   */
  def send(target: ActorRef, msg: AnyRef): Unit
}

object Inbox {
  /**
   * Create a new Inbox within the given system.
   */
  def create(system: ActorSystem): Inbox = ActorDSL.inbox()(system)
}
