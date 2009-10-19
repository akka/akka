package se.scalablesolutions.akka

import akka.state.{MongoStorageSpec, MongoPersistentActorSpec, CassandraPersistentActorSpec}
import junit.framework.Test
import junit.framework.TestCase
import junit.framework.TestSuite

object AllTest extends TestCase {
  def suite(): Test = {
    val suite = new TestSuite("All Scala tests")
    suite.addTestSuite(classOf[CassandraPersistentActorSpec])
    suite.addTestSuite(classOf[MongoPersistentActorSpec])
    suite.addTestSuite(classOf[MongoStorageSpec])
    suite
  }

  def main(args: Array[String]) = junit.textui.TestRunner.run(suite)
}