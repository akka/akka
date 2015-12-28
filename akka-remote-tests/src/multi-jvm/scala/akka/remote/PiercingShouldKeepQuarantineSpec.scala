package akka.remote

import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor._
import akka.testkit._
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec, STMultiNodeSpec }
import akka.remote.testconductor.RoleName

object PiercingShouldKeepQuarantineSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString("""
      #akka.loglevel = INFO
      #akka.remote.log-remote-lifecycle-events = INFO
      akka.remote.retry-gate-closed-for = 0.5s
      
      akka.diagnostics.checker.disabled-checks += retry-gate-closed-for
                              """)))

  class Subject extends Actor {
    def receive = {
      case "getuid" ⇒ sender() ! AddressUidExtension(context.system).addressUid
    }
  }

}

class PiercingShouldKeepQuarantineSpecMultiJvmNode1 extends PiercingShouldKeepQuarantineSpec
class PiercingShouldKeepQuarantineSpecMultiJvmNode2 extends PiercingShouldKeepQuarantineSpec

abstract class PiercingShouldKeepQuarantineSpec extends MultiNodeSpec(PiercingShouldKeepQuarantineSpec)
  with STMultiNodeSpec
  with ImplicitSender {

  import PiercingShouldKeepQuarantineSpec._

  override def initialParticipants = roles.size

  "While probing through the quarantine remoting" must {

    "not lose existing quarantine marker" taggedAs LongRunningTest in {
      runOn(first) {
        enterBarrier("actors-started")

        // Communicate with second system
        system.actorSelection(node(second) / "user" / "subject") ! "getuid"
        val uid = expectMsgType[Int](10.seconds)
        enterBarrier("actor-identified")

        // Manually Quarantine the other system
        RARP(system).provider.transport.quarantine(node(second).address, Some(uid))

        // Quarantining is not immediate
        Thread.sleep(1000)

        // Quarantine is up -- Should not be able to communicate with remote system any more
        for (_ ← 1 to 4) {
          system.actorSelection(node(second) / "user" / "subject") ! "getuid"
          expectNoMsg(2.seconds)
        }

        enterBarrier("quarantine-intact")

      }

      runOn(second) {
        system.actorOf(Props[Subject], "subject")
        enterBarrier("actors-started")
        enterBarrier("actor-identified")
        enterBarrier("quarantine-intact")
      }

    }

  }
}
