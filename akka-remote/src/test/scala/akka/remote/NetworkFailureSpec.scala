/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }

import akka.remote.netty.NettyRemoteSupport
import akka.actor.Actor
import akka.testkit.AkkaSpec
import akka.testkit.DefaultTimeout
import akka.dispatch.Future

import java.util.concurrent.{ TimeUnit, CountDownLatch }
import java.util.concurrent.atomic.AtomicBoolean

trait NetworkFailureSpec extends DefaultTimeout { self: AkkaSpec ⇒
  import Actor._
  import scala.util.Duration

  val BytesPerSecond = "60KByte/s"
  val DelayMillis = "350ms"
  val PortRange = "1024-65535"

  def replyWithTcpResetFor(duration: Duration, dead: AtomicBoolean) = {
    Future {
      try {
        enableTcpReset()
        println("===>>> Reply with [TCP RST] for [" + duration + "]")
        Thread.sleep(duration.toMillis)
        restoreIP
      } catch {
        case e ⇒
          dead.set(true)
          e.printStackTrace
      }
    }
  }

  def throttleNetworkFor(duration: Duration, dead: AtomicBoolean) = {
    Future {
      try {
        enableNetworkThrottling()
        println("===>>> Throttling network with [" + BytesPerSecond + ", " + DelayMillis + "] for [" + duration + "]")
        Thread.sleep(duration.toMillis)
        restoreIP
      } catch {
        case e ⇒
          dead.set(true)
          e.printStackTrace
      }
    }
  }

  def dropNetworkFor(duration: Duration, dead: AtomicBoolean) = {
    Future {
      try {
        enableNetworkDrop()
        println("===>>> Blocking network [TCP DENY] for [" + duration + "]")
        Thread.sleep(duration.toMillis)
        restoreIP
      } catch {
        case e ⇒
          dead.set(true)
          e.printStackTrace
      }
    }
  }

  def sleepFor(duration: Duration) = {
    println("===>>> Sleeping for [" + duration + "]")
    Thread sleep (duration.toMillis)
  }

  def enableNetworkThrottling() = {
    restoreIP()
    assert(new ProcessBuilder("ipfw", "add", "pipe", "1", "ip", "from", "any", "to", "any").start.waitFor == 0)
    assert(new ProcessBuilder("ipfw", "add", "pipe", "2", "ip", "from", "any", "to", "any").start.waitFor == 0)
    assert(new ProcessBuilder("ipfw", "pipe", "1", "config", "bw", BytesPerSecond, "delay", DelayMillis).start.waitFor == 0)
    assert(new ProcessBuilder("ipfw", "pipe", "2", "config", "bw", BytesPerSecond, "delay", DelayMillis).start.waitFor == 0)
  }

  def enableNetworkDrop() = {
    restoreIP()
    assert(new ProcessBuilder("ipfw", "add", "1", "deny", "tcp", "from", "any", "to", "any", PortRange).start.waitFor == 0)
  }

  def enableTcpReset() = {
    restoreIP()
    assert(new ProcessBuilder("ipfw", "add", "1", "reset", "tcp", "from", "any", "to", "any", PortRange).start.waitFor == 0)
  }

  def restoreIP() = {
    println("===>>> Restoring network")
    assert(new ProcessBuilder("ipfw", "del", "pipe", "1").start.waitFor == 0)
    assert(new ProcessBuilder("ipfw", "del", "pipe", "2").start.waitFor == 0)
    assert(new ProcessBuilder("ipfw", "flush").start.waitFor == 0)
    assert(new ProcessBuilder("ipfw", "pipe", "flush").start.waitFor == 0)
  }
}
