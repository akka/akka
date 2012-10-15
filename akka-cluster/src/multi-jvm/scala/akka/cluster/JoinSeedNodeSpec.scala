/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import scala.concurrent.duration._
import akka.actor.Address

object JoinSeedNodeMultiJvmSpec extends MultiNodeConfig {
  val seed1 = role("seed1")
  val seed2 = role("seed2")
  val seed3 = role("seed3")
  val ordinary1 = role("ordinary1")
  val ordinary2 = role("ordinary2")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("akka.cluster.auto-join = off")).
    withFallback(MultiNodeClusterSpec.clusterConfig))
}

class JoinSeedNodeMultiJvmNode1 extends JoinSeedNodeSpec
class JoinSeedNodeMultiJvmNode2 extends JoinSeedNodeSpec
class JoinSeedNodeMultiJvmNode3 extends JoinSeedNodeSpec
class JoinSeedNodeMultiJvmNode4 extends JoinSeedNodeSpec
class JoinSeedNodeMultiJvmNode5 extends JoinSeedNodeSpec

abstract class JoinSeedNodeSpec
  extends MultiNodeSpec(JoinSeedNodeMultiJvmSpec)
  with MultiNodeClusterSpec {

  import JoinSeedNodeMultiJvmSpec._

  def seedNodes: IndexedSeq[Address] = IndexedSeq(seed1, seed2, seed3)

  "A cluster with seed nodes" must {
    "be able to start the seed nodes concurrently" taggedAs LongRunningTest in {

      runOn(seed1) {
        // test that first seed doesn't have to be started first
        Thread.sleep(3000)
      }

      runOn(seed1, seed2, seed3) {
        cluster.joinSeedNodes(seedNodes)
        awaitUpConvergence(3)
      }
      enterBarrier("after-1")
    }

    "join the seed nodes" taggedAs LongRunningTest in {
      runOn(ordinary1, ordinary2) {
        cluster.joinSeedNodes(seedNodes)
      }
      awaitUpConvergence(roles.size)
      enterBarrier("after-2")
    }
  }
}
