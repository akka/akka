package sample.cluster.transformation.japi

import language.postfixOps
import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import org.scalatest.BeforeAndAfterAll
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.actor.Props
import akka.cluster.Cluster
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit.ImplicitSender
import sample.cluster.transformation.japi.TransformationMessages._

object TransformationSampleJapiSpecConfig extends MultiNodeConfig {
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
class TransformationSampleJapiSpecMultiJvmNode1 extends TransformationSampleJapiSpec
class TransformationSampleJapiSpecMultiJvmNode2 extends TransformationSampleJapiSpec
class TransformationSampleJapiSpecMultiJvmNode3 extends TransformationSampleJapiSpec
class TransformationSampleJapiSpecMultiJvmNode4 extends TransformationSampleJapiSpec
class TransformationSampleJapiSpecMultiJvmNode5 extends TransformationSampleJapiSpec

abstract class TransformationSampleJapiSpec extends MultiNodeSpec(TransformationSampleJapiSpecConfig)
  with WordSpec with MustMatchers with BeforeAndAfterAll with ImplicitSender {

  import TransformationSampleJapiSpecConfig._

  override def initialParticipants = roles.size

  override def beforeAll() = multiNodeSpecBeforeAll()

  override def afterAll() = multiNodeSpecAfterAll()

  "The japi transformation sample" must {
    "illustrate how to start first frontend" in within(15 seconds) {
      runOn(frontend1) {
        // this will only run on the 'first' node
        Cluster(system) join node(frontend1).address
        val transformationFrontend = system.actorOf(Props[TransformationFrontend], name = "frontend")
        transformationFrontend ! new TransformationJob("hello")
        expectMsgPF() {
          // no backends yet, service unavailble
          case f: JobFailed ⇒
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
      transformationFrontend ! new TransformationJob("hello")
      expectMsgPF() {
        case unavailble: JobFailed ⇒ false
        case r: TransformationResult ⇒
          r.getText must be("HELLO")
          true
      }
    }
  }

}