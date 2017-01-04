/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.concurrent.Eventually._
import scala.collection.JavaConversions._
import scala.collection.mutable.Set
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal

/**
 * The 192.0.2.1 is a Documentation IP-address and should not be used at all
 * by any network node. Therefore we assume here that the initialization of
 * the ActorSystem with the use of remoting will intentionally fail.
 */
class RemoteInitErrorSpec extends FlatSpec with Matchers {
  val conf = ConfigFactory.parseString(
    """
      akka {
        actor {
          provider = remote
        }
        remote {
          enabled-transports = ["akka.remote.netty.tcp"]
            netty.tcp {
              hostname = "192.0.2.1"
              port = 12344
            }
        }
      }
    """).resolve()

  def currentThreadIds(): Set[Long] = {
    val threads = Thread.getAllStackTraces().keySet()
    threads.collect({ case t: Thread if (!t.isDaemon()) ⇒ t.getId() })
  }

  "Remoting" must "shut down properly on RemoteActorRefProvider initialization failure" in {
    val start = currentThreadIds()
    try {
      ActorSystem("duplicate", ConfigFactory.parseString("akka.loglevel=OFF").withFallback(conf))
      fail("initialization should fail due to invalid IP address")
    } catch {
      case NonFatal(e) ⇒ {
        eventually(timeout(30 seconds), interval(800 milliseconds)) {
          val current = currentThreadIds()
          // no new threads should remain compared to the start state
          (current diff start) should be(empty)
        }
      }
    }
  }
}
