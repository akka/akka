package akka.actor.remote

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import akka.remote.NettyRemoteSupport
import akka.actor. {Actor, ActorRegistry}
import java.util.concurrent. {TimeUnit, CountDownLatch}

object AkkaRemoteTest {
  class ReplyHandlerActor(latch: CountDownLatch, expect: String) extends Actor {
    def receive = {
      case x: String if x == expect => latch.countDown
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

  val remote = ActorRegistry.remote
  val unit = TimeUnit.SECONDS
  val host = remote.hostname
  val port = remote.port

  var optimizeLocal_? = remote.asInstanceOf[NettyRemoteSupport].optimizeLocalScoped_?

  override def beforeAll {
    remote.asInstanceOf[NettyRemoteSupport].optimizeLocal.set(false) //Can't run the test if we're eliminating all remote calls
  }

  override def afterAll {
    remote.asInstanceOf[NettyRemoteSupport].optimizeLocal.set(optimizeLocal_?) //Reset optimizelocal after all tests
  }

  override def beforeEach {
    remote.start()
    Thread.sleep(2000)
    super.beforeEach
  }

  override def afterEach() {
    remote.shutdown
    ActorRegistry.shutdownAll
    super.afterEach
  }

  /* Utilities */

  def replyHandler(latch: CountDownLatch, expect: String) = Some(Actor.actorOf(new ReplyHandlerActor(latch, expect)).start)
}