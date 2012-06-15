/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.testkit._
import akka.actor._
import com.typesafe.config._
import akka.dispatch.{ Await, Future }
import akka.pattern.ask
import java.io.File
import akka.event.LoggingAdapter
import java.security.{ SecureRandom, PrivilegedAction, AccessController }
import netty.NettySSLSupport

object Configuration {
  // set this in your JAVA_OPTS to see all ssl debug info: "-Djavax.net.debug=ssl,keymanager"
  // The certificate will expire in 2109
  private val trustStore = getPath("truststore")
  private val keyStore = getPath("keystore")
  private def getPath(name: String): String = (new File("akka-remote/src/test/resources/" + name)).getAbsolutePath.replace("\\", "\\\\")
  private val conf = """
    akka {
      actor.provider = "akka.remote.RemoteActorRefProvider"
      remote.netty {
        hostname = localhost
        port = 12345
        ssl {
          enable = on
          trust-store = "%s"
          key-store = "%s"
          random-number-generator = "%s"
        }
      }
      actor.deployment {
        /blub.remote = "akka://remote-sys@localhost:12346"
        /looker/child.remote = "akka://remote-sys@localhost:12346"
        /looker/child/grandchild.remote = "akka://Ticket1978CommunicationSpec@localhost:12345"
      }
    }
  """

  def getConfig(rng: String): String = conf.format(trustStore, keyStore, rng)
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class Ticket1978SHA1PRNG extends Ticket1978CommunicationSpec("SHA1PRNG")

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class Ticket1978AES128CounterRNGFast extends Ticket1978CommunicationSpec("AES128CounterRNGFast")

/**
 * Both of the <quote>Secure</quote> variants require access to the Internet to access random.org.
 */
@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class Ticket1978AES128CounterRNGSecure extends Ticket1978CommunicationSpec("AES128CounterRNGSecure")

/**
 * Both of the <quote>Secure</quote> variants require access to the Internet to access random.org.
 */
@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class Ticket1978AES256CounterRNGSecure extends Ticket1978CommunicationSpec("AES256CounterRNGSecure")

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
abstract class Ticket1978CommunicationSpec(val cipher: String) extends AkkaSpec(Configuration.getConfig(cipher)) with ImplicitSender with DefaultTimeout {

  import RemoteCommunicationSpec._

  // default SecureRandom RNG
  def this() = this(Configuration.getConfig(""))

  val conf = ConfigFactory.parseString("akka.remote.netty.port=12346").withFallback(system.settings.config)
  val other = ActorSystem("remote-sys", conf)

  val remote = other.actorOf(Props(new Actor {
    def receive = {
      case "ping" ⇒ sender ! (("pong", sender))
    }
  }), "echo")

  val here = system.actorFor("akka://remote-sys@localhost:12346/user/echo")

  override def atTermination() {
    other.shutdown()
  }

  val isSupportedOnPlatform: Boolean = try {
    NettySSLSupport.initialiseCustomSecureRandom(Some(cipher), None, log) ne null
  } catch {
    case iae: IllegalArgumentException if iae.getMessage == "Cannot support %s with currently installed providers".format(cipher) ⇒ false
  }

  "SSL Remoting" must {
    if (isSupportedOnPlatform) {
      "support remote look-ups" in {
        here ! "ping"
        expectMsgPF() {
          case ("pong", s: AnyRef) if s eq testActor ⇒ true
        }
      }

      "send error message for wrong address" in {
        EventFilter.error(start = "dropping", occurrences = 1).intercept {
          system.actorFor("akka://remotesys@localhost:12346/user/echo") ! "ping"
        }(other)
      }

      "support ask" in {
        Await.result(here ? "ping", timeout.duration) match {
          case ("pong", s: akka.pattern.PromiseActorRef) ⇒ // good
          case m                                         ⇒ fail(m + " was not (pong, AskActorRef)")
        }
      }

      "send dead letters on remote if actor does not exist" in {
        EventFilter.warning(pattern = "dead.*buh", occurrences = 1).intercept {
          system.actorFor("akka://remote-sys@localhost:12346/does/not/exist") ! "buh"
        }(other)
      }

      "create and supervise children on remote node" in {
        val r = system.actorOf(Props[Echo], "blub")
        r.path.toString must be === "akka://remote-sys@localhost:12346/remote/Ticket1978CommunicationSpec@localhost:12345/user/blub"
        r ! 42
        expectMsg(42)
        EventFilter[Exception]("crash", occurrences = 1).intercept {
          r ! new Exception("crash")
        }(other)
        expectMsg("preRestart")
        r ! 42
        expectMsg(42)
        system.stop(r)
        expectMsg("postStop")
      }

      "look-up actors across node boundaries" in {
        val l = system.actorOf(Props(new Actor {
          def receive = {
            case (p: Props, n: String) ⇒ sender ! context.actorOf(p, n)
            case s: String             ⇒ sender ! context.actorFor(s)
          }
        }), "looker")
        l ! (Props[Echo], "child")
        val r = expectMsgType[ActorRef]
        r ! (Props[Echo], "grandchild")
        val remref = expectMsgType[ActorRef]
        remref.isInstanceOf[LocalActorRef] must be(true)
        val myref = system.actorFor(system / "looker" / "child" / "grandchild")
        myref.isInstanceOf[RemoteActorRef] must be(true)
        myref ! 43
        expectMsg(43)
        lastSender must be theSameInstanceAs remref
        r.asInstanceOf[RemoteActorRef].getParent must be(l)
        system.actorFor("/user/looker/child") must be theSameInstanceAs r
        Await.result(l ? "child/..", timeout.duration).asInstanceOf[AnyRef] must be theSameInstanceAs l
        Await.result(system.actorFor(system / "looker" / "child") ? "..", timeout.duration).asInstanceOf[AnyRef] must be theSameInstanceAs l
      }

      "not fail ask across node boundaries" in {
        val f = for (_ ← 1 to 1000) yield here ? "ping" mapTo manifest[(String, ActorRef)]
        Await.result(Future.sequence(f), remaining).map(_._1).toSet must be(Set("pong"))
      }
    } else {
      "not be run when the cipher is not supported by the platform this test is currently being executed on" ignore {

      }
    }

  }

}
