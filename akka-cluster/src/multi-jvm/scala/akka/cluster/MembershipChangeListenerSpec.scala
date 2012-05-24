/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.collection.immutable.SortedSet
import org.scalatest.BeforeAndAfter
import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit.ImplicitSender
import akka.testkit.TestLatch

object MembershipChangeListenerMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString("""
    akka.cluster {
      gossip-frequency = 200 ms
      leader-actions-frequency = 200 ms
      periodic-tasks-initial-delay = 300 ms
    }
    """)))

  nodeConfig(first, ConfigFactory.parseString("""
    # FIXME get rid of this hardcoded port
    akka.remote.netty.port=2603
    """))

  nodeConfig(second, ConfigFactory.parseString("""
    # FIXME get rid of this hardcoded host:port
    akka.cluster.node-to-join = "akka://MultiNodeSpec@localhost:2603"
    """))

  nodeConfig(third, ConfigFactory.parseString("""
    # FIXME get rid of this hardcoded host:port
    akka.cluster.node-to-join = "akka://MultiNodeSpec@localhost:2603"
    """))

}

class MembershipChangeListenerMultiJvmNode1 extends MembershipChangeListenerSpec
class MembershipChangeListenerMultiJvmNode2 extends MembershipChangeListenerSpec
class MembershipChangeListenerMultiJvmNode3 extends MembershipChangeListenerSpec

abstract class MembershipChangeListenerSpec extends MultiNodeSpec(MembershipChangeListenerMultiJvmSpec) with ImplicitSender with BeforeAndAfter {
  import MembershipChangeListenerMultiJvmSpec._

  override def initialParticipants = 3

  var node: Cluster = _

  after {
    testConductor.enter("after")
  }

  "A set of connected cluster systems" must {

    val firstAddress = testConductor.getAddressFor(first).await
    val secondAddress = testConductor.getAddressFor(second).await

    "(when two systems) after cluster convergence updates the membership table then all MembershipChangeListeners should be triggered" in {

      runOn(first, second) {
        node = Cluster(system)
        val latch = TestLatch()
        node.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            if (members.size == 2 && members.forall(_.status == MemberStatus.Up))
              latch.countDown()
          }
        })
        latch.await
        node.convergence.isDefined must be(true)
      }

    }

    "(when three systems) after cluster convergence updates the membership table then all MembershipChangeListeners should be triggered" in {

      runOn(third) {
        node = Cluster(system)
      }

      // runOn all
      val latch = TestLatch()
      node.registerListener(new MembershipChangeListener {
        def notify(members: SortedSet[Member]) {
          if (members.size == 3 && members.forall(_.status == MemberStatus.Up))
            latch.countDown()
        }
      })
      latch.await
      node.convergence.isDefined must be(true)

    }
  }

}
