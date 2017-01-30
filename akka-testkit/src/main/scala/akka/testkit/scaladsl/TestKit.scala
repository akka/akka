/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit.scaladsl

import akka.actor._
import akka.testkit._

import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * Test kit for testing actors. Inheriting from this class enables reception of
 * replies from actors, which are queued by an internal actor and can be
 * examined using the `expectMsg...` methods. Assertions and bounds concerning
 * timing are available in the form of `within` blocks.
 *
 * {{{
 * class Test extends TestKit(ActorSystem()) {
 *   try {
 *
 *     val test = system.actorOf(Props[SomeActor])
 *
 *       within (1.second) {
 *         test ! SomeWork
 *         expectMsg(Result1) // bounded to 1 second
 *         expectMsg(Result2) // bounded to the remainder of the 1 second
 *       }
 *
 *     } finally {
 *       system.terminate()
 *     }
 *
 *   } finally {
 *     system.terminate()
 *   }
 * }
 * }}}
 *
 * Beware of two points:
 *
 *  - the ActorSystem passed into the constructor needs to be shutdown,
 *    otherwise thread pools and memory will be leaked
 *  - this class is not thread-safe (only one actor with one queue, one stack
 *    of `within` blocks); it is expected that the code is executed from a
 *    constructor as shown above, which makes this a non-issue, otherwise take
 *    care not to run tests within a single test class instance in parallel.
 *
 * It should be noted that for CI servers and the like all maximum Durations
 * are scaled using their Duration.dilated method, which uses the
 * TestKitExtension.Settings.TestTimeFactor settable via akka.conf entry "akka.test.timefactor".
 *
 * @since 1.1
 */
class TestKit(_system: ActorSystem) extends { implicit val system = _system } with TestKitBase

object TestKit {

  /**
   * Await until the given condition evaluates to `true` or the timeout
   * expires, whichever comes first.
   */
  def awaitCond(p: ⇒ Boolean, max: Duration, interval: Duration = 100.millis, noThrow: Boolean = false): Boolean = {
    TestKitBase.awaitCond(p, max, interval, noThrow)
  }

  /**
   * Shut down an actor system and wait for termination.
   * On failure debug output will be logged about the remaining actors in the system.
   *
   * If verifySystemShutdown is true, then an exception will be thrown on failure.
   */
  def shutdownActorSystem(
    actorSystem:          ActorSystem,
    duration:             Duration    = 10.seconds,
    verifySystemShutdown: Boolean     = false): Unit = {

    TestKitBase.shutdownActorSystem(actorSystem, duration, verifySystemShutdown)
  }

}
