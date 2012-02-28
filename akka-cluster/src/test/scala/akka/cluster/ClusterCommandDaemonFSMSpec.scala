/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.testkit._
import akka.actor.Address

class ClusterCommandDaemonFSMSpec extends AkkaSpec(
  """
  akka {
    actor {
      provider = akka.remote.RemoteActorRefProvider
    }
  }
  """) with ImplicitSender {

  "A ClusterCommandDaemon FSM" must {

    "start in Joining" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon(system, system.node))
      fsm.stateName must be(MemberStatus.Joining)
    }

    "be able to switch from Joining to Up" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon(system, system.node))
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! LeaderAction.Up
      fsm.stateName must be(MemberStatus.Up)
    }

    "be able to switch from Up to Down" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon(system, system.node))
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! LeaderAction.Up
      fsm.stateName must be(MemberStatus.Up)
      fsm ! UserAction.Down
      fsm.stateName must be(MemberStatus.Down)
    }

    "be able to switch from Up to Leaving" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon(system, system.node))
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! LeaderAction.Up
      fsm.stateName must be(MemberStatus.Up)
      fsm ! UserAction.Leave
      fsm.stateName must be(MemberStatus.Leaving)
    }

    "be able to switch from Up to Exiting" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon(system, system.node))
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! LeaderAction.Up
      fsm.stateName must be(MemberStatus.Up)
      fsm ! UserAction.Exit
      fsm.stateName must be(MemberStatus.Exiting)
    }

    "be able to switch from Up to Removed" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon(system, system.node))
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! LeaderAction.Up
      fsm.stateName must be(MemberStatus.Up)
      fsm ! LeaderAction.Remove
      fsm.stateName must be(MemberStatus.Removed)
    }

    "be able to switch from Leaving to Down" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon(system, system.node))
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! LeaderAction.Up
      fsm.stateName must be(MemberStatus.Up)
      fsm ! UserAction.Leave
      fsm.stateName must be(MemberStatus.Leaving)
      fsm ! UserAction.Down
      fsm.stateName must be(MemberStatus.Down)
    }

    "be able to switch from Leaving to Removed" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon(system, system.node))
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! LeaderAction.Up
      fsm.stateName must be(MemberStatus.Up)
      fsm ! UserAction.Leave
      fsm.stateName must be(MemberStatus.Leaving)
      fsm ! LeaderAction.Remove
      fsm.stateName must be(MemberStatus.Removed)
    }

    "be able to switch from Exiting to Removed" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon(system, system.node))
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! LeaderAction.Up
      fsm.stateName must be(MemberStatus.Up)
      fsm ! UserAction.Exit
      fsm.stateName must be(MemberStatus.Exiting)
      fsm ! LeaderAction.Remove
      fsm.stateName must be(MemberStatus.Removed)
    }

    "be able to switch from Down to Removed" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon(system, system.node))
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! LeaderAction.Up
      fsm.stateName must be(MemberStatus.Up)
      fsm ! UserAction.Down
      fsm.stateName must be(MemberStatus.Down)
      fsm ! LeaderAction.Remove
      fsm.stateName must be(MemberStatus.Removed)
    }

    "not be able to switch from Removed to any other state" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon(system, system.node))
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! LeaderAction.Up
      fsm.stateName must be(MemberStatus.Up)
      fsm ! LeaderAction.Remove
      fsm.stateName must be(MemberStatus.Removed)
      fsm ! LeaderAction.Up
      fsm.stateName must be(MemberStatus.Removed)
      fsm ! UserAction.Leave
      fsm.stateName must be(MemberStatus.Removed)
      fsm ! UserAction.Down
      fsm.stateName must be(MemberStatus.Removed)
      fsm ! UserAction.Exit
      fsm.stateName must be(MemberStatus.Removed)
      fsm ! LeaderAction.Remove
      fsm.stateName must be(MemberStatus.Removed)
    }

    "remain in the same state when receiving a Join command" in {
      val address = Address("akka", system.name)

      val fsm = TestFSMRef(new ClusterCommandDaemon(system, system.node))
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! UserAction.Join(address)
      fsm.stateName must be(MemberStatus.Joining)

      fsm ! LeaderAction.Up
      fsm.stateName must be(MemberStatus.Up)
      fsm ! UserAction.Join(address)
      fsm.stateName must be(MemberStatus.Up)

      fsm ! UserAction.Leave
      fsm.stateName must be(MemberStatus.Leaving)
      fsm ! UserAction.Join(address)
      fsm.stateName must be(MemberStatus.Leaving)

      fsm ! UserAction.Down
      fsm.stateName must be(MemberStatus.Down)
      fsm ! UserAction.Join(address)
      fsm.stateName must be(MemberStatus.Down)
    }
  }
}
