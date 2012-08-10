/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import scala.collection.mutable.Queue
import scala.concurrent.util.Duration
import scala.concurrent.util.duration._
import akka.pattern.ask
import scala.concurrent.Await
import akka.util.Timeout
import scala.collection.immutable.TreeSet
import scala.concurrent.util.Deadline
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
 * someActor ! someMsg // replies will go to `recv`
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
 * as shown with `"barney"`, but since nested declarations share the same
 * lexical context `"fred"`’s [[akka.actor.ActorContext]] would be ambiguous
 * if the [[akka.actor.ActorSystem]] were declared `implicit` (this could also
 * be circumvented by shadowing the name `system` within `"fred"`).
 */
object ActorDSL extends dsl.Inbox with dsl.Creators {

  protected object Extension extends ExtensionKey[Extension]

  protected class Extension(val system: ExtendedActorSystem) extends akka.actor.Extension with InboxExtension {

    val boss = system.asInstanceOf[ActorSystemImpl].systemActorOf(Props.empty, "dsl").asInstanceOf[RepointableActorRef]
    while (!boss.isStarted) Thread.sleep(10)

    lazy val config = system.settings.config.getConfig("akka.actor.dsl")

    val DSLDefaultTimeout = Duration(config.getMilliseconds("default-timeout"), TimeUnit.MILLISECONDS)

    def mkChild(p: Props, name: String) = boss.underlying.asInstanceOf[ActorCell].attachChild(p, name, systemService = true)
  }

}
