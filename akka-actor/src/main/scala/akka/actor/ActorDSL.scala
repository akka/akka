/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import scala.collection.mutable.Queue
import scala.concurrent.duration._
import akka.pattern.ask
import scala.concurrent.Await
import akka.util.Timeout
import scala.collection.immutable.TreeSet
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

/**
 * This object contains elements which make writing actors and related code
 * more concise, e.g. when trying out actors in the REPL.
 *
 * For the communication of non-actor code with actors, you may use anonymous
 * actors tailored to this job:
 *
 * {{{
 * import ActorDSL._
 * import concurrent.util.duration._
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
 * as shown with `"barney"` (where the [[akka.actor.ActorContext serves this
 * purpose), but since nested declarations share the same
 * lexical context `"fred"`’s ActorContext would be ambiguous
 * if the [[akka.actor.ActorSystem]] were declared `implicit` (this could also
 * be circumvented by shadowing the name `system` within `"fred"`).
 *
 * <b>Note:</b> If you want to use an `Act with Stash`, you should use the
 * `ActWithStash` trait in order to have the actor run on a special dispatcher
 * (`"akka.actor.default-stash-dispatcher"`) which has the necessary deque-based
 * mailbox setting.
 */
object ActorDSL extends dsl.Inbox with dsl.Creators {

  protected object Extension extends ExtensionKey[Extension]

  protected class Extension(val system: ExtendedActorSystem) extends akka.actor.Extension with InboxExtension {

    val boss = system.asInstanceOf[ActorSystemImpl].systemActorOf(Props(
      new Actor {
        def receive = { case any ⇒ sender ! any }
      }), "dsl").asInstanceOf[RepointableActorRef]

    {
      implicit val timeout = system.settings.CreationTimeout
      if (Await.result(boss ? "OK", system.settings.CreationTimeout.duration) != "OK")
        throw new IllegalStateException("Creation of boss actor did not succeed!")
    }

    lazy val config = system.settings.config.getConfig("akka.actor.dsl")

    val DSLDefaultTimeout = Duration(config.getMilliseconds("default-timeout"), TimeUnit.MILLISECONDS)

    def mkChild(p: Props, name: String) = boss.underlying.asInstanceOf[ActorCell].attachChild(p, name, systemService = true)
  }

}
