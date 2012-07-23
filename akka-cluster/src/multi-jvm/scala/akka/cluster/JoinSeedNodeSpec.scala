/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import scala.concurrent.util.duration._

object JoinSeedNodeMultiJvmSpec extends MultiNodeConfig {
  val seed1 = role("seed1")
  val seed2 = role("seed2")
  val ordinary1 = role("ordinary1")
  val ordinary2 = role("ordinary2")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("akka.cluster.auto-join = on")).
    withFallback(MultiNodeClusterSpec.clusterConfig))
}

class JoinSeedNodeMultiJvmNode1 extends JoinSeedNodeSpec with FailureDetectorPuppetStrategy
class JoinSeedNodeMultiJvmNode2 extends JoinSeedNodeSpec with FailureDetectorPuppetStrategy
class JoinSeedNodeMultiJvmNode3 extends JoinSeedNodeSpec with FailureDetectorPuppetStrategy
class JoinSeedNodeMultiJvmNode4 extends JoinSeedNodeSpec with FailureDetectorPuppetStrategy

abstract class JoinSeedNodeSpec
  extends MultiNodeSpec(JoinSeedNodeMultiJvmSpec)
  with MultiNodeClusterSpec {

  import JoinSeedNodeMultiJvmSpec._

  override def seedNodes = IndexedSeq(seed1, seed2)

  "A cluster with configured seed nodes" must {
    "start the seed nodes sequentially" taggedAs LongRunningTest in {
      // without looking up the addresses first there might be
      // [akka://JoinSeedNodeSpec/user/TestConductorClient] cannot write GetAddress(RoleName(seed2)) while waiting for seed1
      roles foreach address

      runOn(seed1) {
        startClusterNode()
      }
      enterBarrier("seed1-started")

      runOn(seed2) {
        startClusterNode()
      }
      enterBarrier("seed2-started")

      runOn(seed1, seed2) {
        awaitUpConvergence(2)
      }
      enterBarrier("after-1")
    }

    "join the seed nodes at startup" taggedAs LongRunningTest in {

      startClusterNode()
      enterBarrier("all-started")

      awaitUpConvergence(4)

      enterBarrier("after-2")
    }
  }
}
