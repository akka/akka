/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.util.duration._

object NodeUpMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig))
}

class NodeUpMultiJvmNode1 extends NodeUpSpec
class NodeUpMultiJvmNode2 extends NodeUpSpec

abstract class NodeUpSpec
  extends MultiNodeSpec(NodeUpMultiJvmSpec)
  with MultiNodeClusterSpec {

  import NodeUpMultiJvmSpec._

  override def initialParticipants = 2

  "A cluster node that is joining another cluster" must {
    "be moved to UP by the leader after a convergence" taggedAs LongRunningTest in {

      awaitClusterUp(first, second)

      testConductor.enter("after")
    }
  }
}
