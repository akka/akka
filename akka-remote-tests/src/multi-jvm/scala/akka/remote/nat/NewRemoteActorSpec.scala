/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.nat

import language.postfixOps
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.pattern.ask
import akka.remote.testkit.{ STMultiNodeSpec, MultiNodeConfig, MultiNodeSpec }
import akka.testkit._
import akka.actor.Terminated
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.remote.RemoteActorRef
import akka.actor.ActorRef
import scala.util.Properties

  /**
   * IMPORTANT: this spec tests NAT and should only be run as a multi-node-test
   * this Spec is not built to be used with multi:jvm tests
   * 
   * Also the IP of the router that will perform NAT needs to be configured for both nodes as JVM prop "multi-node.nat-router"
   * See the .opts files to configure this.
   */
object NewRemoteActorMultiJvmSpec extends MultiNodeConfig {

  class SomeActor extends Actor {
    def receive = {
      case "identify" ⇒ sender ! self
    }
  }
  
  
 def setupConfig(public: Boolean, host: String, port: Int) = 
   ConfigFactory.parseString("""akka.remote.netty {
          force-bind-address = %b
          hostname = "%s"
          port = %d   
}""".format(public, host, port))

  
  /**
   * IMPORTANT: set this JVM property when testing. This should NOT be any physical IP on a node.
   * instead it should be the ip address of a router which will use NAT to connect you to the nodes
   * 
   * forward router:6996 to master:6996 
   * forward router:3663 to slave:3663
   */
  val natRouterIp = Properties.propOrNone("multi-node.nat-router").filter(_ != "0.0.0.0").getOrElse(throw new Exception("multi-node.nat-router property must be set to Router IP!"))
    
  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString("akka.remote.log-remote-lifecycle-events = off")))

  val master = role("master")
  nodeConfig(master)(setupConfig(true, natRouterIp, 6996))
  
  val slave = role("slave")
  val slavePort = 3663
  nodeConfig(slave)(setupConfig(true, natRouterIp, slavePort))
  
  val slaveUri = ("akka://NewRemoteActorSpec@%s:" + slavePort).format(natRouterIp)
  val slavesUriAddress = akka.actor.AddressFromURIString(slaveUri)
  
  deployOn(master, """
    /service-hello.remote = "%s"
    /service-hello3.remote = "%s"
    """.format(slaveUri, slaveUri))

  deployOnAll("""/service-hello2.remote = "%s" """.format(slaveUri))
}

class NewRemoteActorMultiJvmMaster extends NewRemoteActorSpec
class NewRemoteActorMultiJvmSlave extends NewRemoteActorSpec

class NewRemoteActorSpec extends MultiNodeSpec(NewRemoteActorMultiJvmSpec)
  with STMultiNodeSpec with ImplicitSender with DefaultTimeout {
  import NewRemoteActorMultiJvmSpec._

  def initialParticipants = roles.size

  // ensure that system shutdown is successful
  override def verifySystemShutdown = true
  
  "A new NATed remote actor" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" taggedAs NatRemotingTest in {

      runOn(master) {
        val actor = system.actorOf(Props[SomeActor], "service-hello")
        actor.isInstanceOf[RemoteActorRef] must be(true)
        
        actor.path.address must be(slavesUriAddress)
        actor.path.address must be(node(slave).address)
        
        val slaveAddress = testConductor.getAddressFor(slave).await

        actor ! "identify"
       val expected = expectMsgType[ActorRef].path.address
       expected must equal(slaveAddress)
       expected must equal(slavesUriAddress)
      }

      enterBarrier("done")
    }

    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef (with deployOnAll)" taggedAs NatRemotingTest in {

      runOn(master) {
        val actor = system.actorOf(Props[SomeActor], "service-hello2")
        actor.isInstanceOf[RemoteActorRef] must be(true)
        
        actor.path.address must be(slavesUriAddress)
        actor.path.address must be(node(slave).address)

        val slaveAddress = testConductor.getAddressFor(slave).await
        actor ! "identify"
        val expected = expectMsgType[ActorRef].path.address
       expected must equal(slaveAddress)
       expected must equal(slavesUriAddress)
      }

      enterBarrier("done")
    }

    "be able to shutdown system when using remote deployed actor" taggedAs NatRemotingTest in within(10 seconds) {
      runOn(master) {
        val actor = system.actorOf(Props[SomeActor], "service-hello3")
        actor.isInstanceOf[RemoteActorRef] must be(true)
         actor.path.address must be(slavesUriAddress)
        actor.path.address must be(node(slave).address)
        watch(actor)

        enterBarrier("deployed")

        // master system is supposed to be shutdown after slave
        // this should be triggered by slave system shutdown
        expectMsgPF(remaining) { 
          case Terminated(`actor`) ⇒ true;
          }
      }

      runOn(slave) {
        enterBarrier("deployed")
      }

      // Important that this is the last test.
      // There must not be any barriers here.
      // verifySystemShutdown = true will ensure that system shutdown is successful
    }
  }
}
