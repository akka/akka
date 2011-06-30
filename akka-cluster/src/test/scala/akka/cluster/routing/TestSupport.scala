package akka.cluster.routing

import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import akka.cluster.Cluster

class MasterNode extends WordSpec with MustMatchers with BeforeAndAfterAll {

  override def beforeAll() {
    Cluster.startLocalCluster()
  }

  override def afterAll() {
    Cluster.shutdownLocalCluster()
  }
}

class SlaveNode extends WordSpec with MustMatchers with BeforeAndAfterAll {

}
