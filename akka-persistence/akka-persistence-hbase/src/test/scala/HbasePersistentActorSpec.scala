package se.scalablesolutions.akka.persistence.hbase

import se.scalablesolutions.akka.actor.{ Actor, ActorRef, Transactor }
import Actor._

import org.junit.Test
import org.junit.Assert._
import org.junit.BeforeClass
import org.junit.Before
import org.junit.AfterClass
import org.junit.After

import org.scalatest.junit.JUnitSuite
import org.scalatest.BeforeAndAfterAll
import org.apache.hadoop.hbase.HBaseTestingUtility

case class GetMapState(key: String)
case object GetVectorState
case object GetVectorSize
case object GetRefState

case class SetMapState(key: String, value: String)
case class SetVectorState(key: String)
case class SetRefState(key: String)
case class Success(key: String, value: String)
case class Failure(key: String, value: String, failer: ActorRef)

case class SetMapStateOneWay(key: String, value: String)
case class SetVectorStateOneWay(key: String)
case class SetRefStateOneWay(key: String)
case class SuccessOneWay(key: String, value: String)
case class FailureOneWay(key: String, value: String, failer: ActorRef)

class HbasePersistentActor extends Transactor {
  self.timeout = 100000

  private lazy val mapState = HbaseStorage.newMap
  private lazy val vectorState = HbaseStorage.newVector
  private lazy val refState = HbaseStorage.newRef

  def receive = {
    case GetMapState(key) =>
      self.reply(mapState.get(key.getBytes("UTF-8")).get)
    case GetVectorSize =>
      self.reply(vectorState.length.asInstanceOf[AnyRef])
    case GetRefState =>
      self.reply(refState.get.get)
    case SetMapState(key, msg) =>
      mapState.put(key.getBytes("UTF-8"), msg.getBytes("UTF-8"))
      self.reply(msg)
    case SetVectorState(msg) =>
      vectorState.add(msg.getBytes("UTF-8"))
      self.reply(msg)
    case SetRefState(msg) =>
      refState.swap(msg.getBytes("UTF-8"))
      self.reply(msg)
    case Success(key, msg) =>
      mapState.put(key.getBytes("UTF-8"), msg.getBytes("UTF-8"))
      vectorState.add(msg.getBytes("UTF-8"))
      refState.swap(msg.getBytes("UTF-8"))
      self.reply(msg)
    case Failure(key, msg, failer) =>
      mapState.put(key.getBytes("UTF-8"), msg.getBytes("UTF-8"))
      vectorState.add(msg.getBytes("UTF-8"))
      refState.swap(msg.getBytes("UTF-8"))
      failer !! "Failure"
      self.reply(msg)
  }
}

@serializable
class PersistentFailerActor extends Transactor {
  def receive = {
    case "Failure" =>
      throw new RuntimeException("Expected exception; to test fault-tolerance")
  }
}

class HbasePersistentActorSpec extends JUnitSuite with BeforeAndAfterAll {

  val testUtil = new HBaseTestingUtility

  override def beforeAll {
    testUtil.startMiniCluster
  }
  
  override def afterAll {
    testUtil.shutdownMiniCluster
  }

  @Before
  def beforeEach {
    HbaseStorageBackend.drop
  }
  
  @After
  def afterEach {
    HbaseStorageBackend.drop
  }

  @Test
  def testMapShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = actorOf[HbasePersistentActor]
    stateful.start
    stateful !! SetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    val result = (stateful !! GetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess")).as[Array[Byte]].get
    assertEquals("new state", new String(result, 0, result.length, "UTF-8"))
  }

  @Test
  def testMapShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = actorOf[HbasePersistentActor]
    stateful.start
    stateful !! SetMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init") // set init state
    val failer = actorOf[PersistentFailerActor]
    failer.start
    try {
      stateful !! Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
      fail("should have thrown an exception")
    } catch { case e: RuntimeException => {} }
    val result = (stateful !! GetMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")).as[Array[Byte]].get
    assertEquals("init", new String(result, 0, result.length, "UTF-8")) // check that state is == init state
  }

  @Test
  def testVectorShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = actorOf[HbasePersistentActor]
    stateful.start
    stateful !! SetVectorState("init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    assertEquals(2, (stateful !! GetVectorSize).get.asInstanceOf[java.lang.Integer].intValue)
  }

  @Test
  def testVectorShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = actorOf[HbasePersistentActor]
    stateful.start
    stateful !! SetVectorState("init") // set init state
    val failer = actorOf[PersistentFailerActor]
    failer.start
    try {
      stateful !! Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
      fail("should have thrown an exception")
    } catch { case e: RuntimeException => {} }
    assertEquals(1, (stateful !! GetVectorSize).get.asInstanceOf[java.lang.Integer].intValue)
  }

  @Test
  def testRefShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = actorOf[HbasePersistentActor]
    stateful.start
    stateful !! SetRefState("init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    val result = (stateful !! GetRefState).as[Array[Byte]].get
    assertEquals("new state", new String(result, 0, result.length, "UTF-8"))
  }

  @Test
  def testRefShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = actorOf[HbasePersistentActor]
    stateful.start
    stateful !! SetRefState("init") // set init state
    val failer = actorOf[PersistentFailerActor]
    failer.start
    try {
      stateful !! Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
      fail("should have thrown an exception")
    } catch { case e: RuntimeException => {} }
    val result = (stateful !! GetRefState).as[Array[Byte]].get
    assertEquals("init", new String(result, 0, result.length, "UTF-8")) // check that state is == init state
  }

}
