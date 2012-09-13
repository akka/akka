package sample.cluster.transformation

import language.postfixOps
import scala.concurrent.util.duration._

import com.typesafe.config.ConfigFactory

import akka.actor.Props
import akka.cluster.Cluster
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit.ImplicitSender

object TransformationSampleSpec extends MultiNodeConfig {
  // register the named roles (nodes) of the test
  val frontend1 = role("frontend1")
  val frontend2 = role("frontend2")
  val backend1 = role("backend1")
  val backend2 = role("backend2")
  val backend3 = role("backend3")

  // this configuration will be used for all nodes
  // note that no fixed host names and ports are used
  commonConfig(ConfigFactory.parseString("""
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.auto-join = off
    """))

}

// need one concrete test class per node
class TransformationSampleMultiJvmNode1 extends TransformationSampleSpec
class TransformationSampleMultiJvmNode2 extends TransformationSampleSpec
class TransformationSampleMultiJvmNode3 extends TransformationSampleSpec
class TransformationSampleMultiJvmNode4 extends TransformationSampleSpec
class TransformationSampleMultiJvmNode5 extends TransformationSampleSpec

abstract class TransformationSampleSpec extends MultiNodeSpec(TransformationSampleSpec)
  with ImplicitSender {

  import TransformationSampleSpec._

  override def initialParticipants = roles.size

  "The transformation sample" must {
    "illustrate how to start first frontend" in {
      runOn(frontend1) {
        // this will only run on the 'first' node
        Cluster(system) join node(frontend1).address
        val transformationFrontend = system.actorOf(Props[TransformationFrontend], name = "frontend")
        transformationFrontend ! TransformationJob("hello")
        expectMsgPF() {
          // no backends yet, service unavailble
          case JobFailed(_, TransformationJob("hello")) ⇒
        }
      }

      // this will run on all nodes
      // use barrier to coordinate test steps
      testConductor.enter("frontend1-started")
    }

    "illustrate how a backend automatically registers" in within(15 seconds) {
      runOn(backend1) {
        Cluster(system) join node(frontend1).address
        system.actorOf(Props[TransformationBackend], name = "backend")
      }
      testConductor.enter("backend1-started")

      runOn(frontend1) {
        assertServiceOk
      }

      testConductor.enter("frontend1-backend1-ok")
    }

    "illustrate how more nodes registers" in within(15 seconds) {
      runOn(frontend2) {
        Cluster(system) join node(frontend1).address
        system.actorOf(Props[TransformationFrontend], name = "frontend")
      }
      runOn(backend2, backend3) {
        Cluster(system) join node(backend1).address
        system.actorOf(Props[TransformationBackend], name = "backend")
      }

      testConductor.enter("all-started")

      runOn(frontend1, frontend2) {
        assertServiceOk
      }

      testConductor.enter("all-ok")

    }

  }

  def assertServiceOk: Unit = {
    val transformationFrontend = system.actorFor("akka://" + system.name + "/user/frontend")
    // eventually the service should be ok,
    // backends might not have registered initially
    awaitCond {
      transformationFrontend ! TransformationJob("hello")
      expectMsgPF() {
        case unavailble: JobFailed ⇒ false
        case TransformationResult(result) ⇒
          result must be("HELLO")
          true
      }
    }
  }

}