package akka.actor.remote

import org.scalatest.matchers.MustMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import akka.remote.netty.NettyRemoteSupport
import akka.actor. {Actor, ActorRegistry}
import java.util.concurrent. {TimeUnit, CountDownLatch}
import org.scalatest.{Spec, WordSpec, BeforeAndAfterAll, BeforeAndAfterEach}
import java.util.concurrent.atomic.AtomicBoolean

object AkkaRemoteTest {
  class ReplyHandlerActor(latch: CountDownLatch, expect: String) extends Actor {
    def receive = {
      case x: String if x == expect => latch.countDown()
    }
  }
}

@RunWith(classOf[JUnitRunner])
class AkkaRemoteTest extends
  WordSpec with
  MustMatchers with
  BeforeAndAfterAll with
  BeforeAndAfterEach {
  import AkkaRemoteTest._

  val remote = Actor.remote
  val unit = TimeUnit.SECONDS

  val host = "localhost"
  val port = 25520

  def OptimizeLocal = false

  var optimizeLocal_? = remote.asInstanceOf[NettyRemoteSupport].optimizeLocalScoped_?

  override def beforeAll {
    if (!OptimizeLocal)
      remote.asInstanceOf[NettyRemoteSupport].optimizeLocal.set(false) //Can't run the test if we're eliminating all remote calls
  }

  override def afterAll() {
    if (!OptimizeLocal)
      remote.asInstanceOf[NettyRemoteSupport].optimizeLocal.set(optimizeLocal_?) //Reset optimizelocal after all tests
  }

  override def beforeEach() {
    remote.start(host,port)
    super.beforeEach
  }

  override def afterEach() {
    remote.shutdown()
    Actor.registry.local.shutdownAll()
    super.afterEach()
  }

  /* Utilities */

  def replyHandler(latch: CountDownLatch, expect: String) = Some(Actor.actorOf(new ReplyHandlerActor(latch, expect)).start())
}

trait NetworkFailureTest { self: WordSpec =>
  import akka.actor.Actor._
  import akka.util.Duration

  // override is subclass if needed
  val BYTES_PER_SECOND = "60KByte/s"
  val DELAY_MILLIS     = "350ms"
  val PORT_RANGE       = "1024-65535"

  // FIXME add support for TCP FIN by hooking into Netty and do socket.close

  def replyWithTcpResetFor(duration: Duration, dead: AtomicBoolean) = {
    spawn {
      try {
        enableTcpReset()
        println("===>>> Reply with [TCP RST] for [" + duration + "]")
        Thread.sleep(duration.toMillis)
        restoreIP
      } catch {
        case e =>
          dead.set(true)
          e.printStackTrace
      }
    }
  }

  def throttleNetworkFor(duration: Duration, dead: AtomicBoolean) = {
    spawn {
      try {
        enableNetworkThrottling()
        println("===>>> Throttling network with [" + BYTES_PER_SECOND + ", " + DELAY_MILLIS + "] for [" + duration + "]")
        Thread.sleep(duration.toMillis)
        restoreIP
      } catch {
        case e =>
          dead.set(true)
          e.printStackTrace
      }
    }
  }

  def dropNetworkFor(duration: Duration, dead: AtomicBoolean) = {
    spawn {
      try {
        enableNetworkDrop()
        println("===>>> Blocking network [TCP DENY] for [" + duration + "]")
        Thread.sleep(duration.toMillis)
        restoreIP
      } catch {
        case e =>
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
    assert(new ProcessBuilder("sudo", "ipfw", "add", "pipe", "1", "ip", "from", "any", "to", "any").start.waitFor == 0)
    assert(new ProcessBuilder("sudo", "ipfw", "add", "pipe", "2", "ip", "from", "any", "to", "any").start.waitFor == 0)
    assert(new ProcessBuilder("sudo", "ipfw", "pipe", "1", "config", "bw", BYTES_PER_SECOND, "delay", DELAY_MILLIS).start.waitFor == 0)
    assert(new ProcessBuilder("sudo", "ipfw", "pipe", "2", "config", "bw", BYTES_PER_SECOND, "delay", DELAY_MILLIS).start.waitFor == 0)
  }

  def enableNetworkDrop() = {
    restoreIP()
    assert(new ProcessBuilder("sudo", "ipfw", "add", "1", "deny", "tcp", "from", "any", "to", "any", PORT_RANGE).start.waitFor == 0)
  }

  def enableTcpReset() = {
    restoreIP()
    assert(new ProcessBuilder("sudo", "ipfw", "add", "1", "reset", "tcp", "from", "any", "to", "any", PORT_RANGE).start.waitFor == 0)
  }

  def restoreIP() = {
    println("===>>> Restoring network")
    assert(new ProcessBuilder("sudo", "ipfw", "del", "pipe", "1").start.waitFor == 0)
    assert(new ProcessBuilder("sudo", "ipfw", "del", "pipe", "2").start.waitFor == 0)
    assert(new ProcessBuilder("sudo", "ipfw", "flush").start.waitFor == 0)
    assert(new ProcessBuilder("sudo", "ipfw", "pipe", "flush").start.waitFor == 0)
  }

  def validateSudo() = {
    println("===>>> Validating sudo")
    assert(new ProcessBuilder("sudo", "-v").start.waitFor == 0)
  }
}
