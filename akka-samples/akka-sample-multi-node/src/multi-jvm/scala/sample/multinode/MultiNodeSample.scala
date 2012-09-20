/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
//#package
package sample.multinode
//#package

//#config
import akka.remote.testkit.MultiNodeConfig

object MultiNodeSampleConfig extends MultiNodeConfig {
  val node1 = role("node1")
  val node2 = role("node2")
}
//#config

//#spec
import akka.remote.testkit.MultiNodeSpec
import akka.testkit.ImplicitSender
import akka.actor.{Props, Actor}

class MultiNodeSampleSpecMultiJvmNode1 extends MultiNodeSample
class MultiNodeSampleSpecMultiJvmNode2 extends MultiNodeSample

class MultiNodeSample extends MultiNodeSpec(MultiNodeSampleConfig)
  with STMultiNodeSpec with ImplicitSender {

  import MultiNodeSampleConfig._

  def initialParticipants = 2

  "A MultiNodeSample" must {

    "wait for all nodes to enter a barrier" in {
      enterBarrier("startup")
    }

    "send to and receive from a remote node" in {
      runOn(node1) {
        enterBarrier("deployed")
        val ponger = system.actorFor(node(node2).toString + "user/ponger")
        ponger ! "ping"
        expectMsg("pong")
      }

      runOn(node2) {
        system.actorOf(Props(new Actor {
          def receive = {
            case "ping" => sender ! "pong"
          }
        }), "ponger")
        enterBarrier("deployed")
      }

      enterBarrier("finished")
    }
  }
}
//#spec
