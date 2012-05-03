package akka.remote.testconductor

import akka.remote.AkkaRemoteSpec
import com.typesafe.config.ConfigFactory
import akka.remote.AbstractRemoteActorMultiJvmSpec

object TestConductorMultiJvmSpec extends AbstractRemoteActorMultiJvmSpec {
  override def NrOfNodes = 2
  override def commonConfig = ConfigFactory.parseString("""
    akka.loglevel = DEBUG
    akka.actor.provider = akka.remote.RemoteActorRefProvider
    akka.actor.debug {
      receive = on
      fsm = on
    }
    akka.testconductor {
      host = localhost
      port = 4712
    }
  """)
  def nameConfig(n: Int) = ConfigFactory.parseString("akka.testconductor.name = node" + n).withFallback(nodeConfigs(n))
}

import TestConductorMultiJvmSpec._

class TestConductorMultiJvmNode1 extends AkkaRemoteSpec(nameConfig(0)) {

  val nodes = TestConductorMultiJvmSpec.NrOfNodes

  "running a test" in {
    val tc = TestConductor(system)
    tc.startController()
    barrier("start")
    barrier("first")
    tc.enter("begin")
    barrier("end")
  }
}

class TestConductorMultiJvmNode2 extends AkkaRemoteSpec(nameConfig(1)) {

  val nodes = TestConductorMultiJvmSpec.NrOfNodes

  "running a test" in {
    barrier("start")
    val tc = TestConductor(system)
    tc.startClient(4712)
    barrier("first")
    tc.enter("begin")
    barrier("end")
  }
}
