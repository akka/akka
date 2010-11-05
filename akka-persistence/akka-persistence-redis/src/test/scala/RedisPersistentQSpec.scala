package akka.persistence.redis

import org.junit.{Test, Before}
import org.junit.Assert._

import akka.actor.{Actor, ActorRef}
import Actor._
import akka.stm.local._

/**
 * A persistent actor based on Redis queue storage.
 * <p/>
 * Needs a running Redis server.
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */

case class NQ(accountNo: String)
case object DQ
case class MNDQ(accountNos: List[String], noOfDQs: Int)
case object SZ

class QueueActor extends Actor {
  private val accounts = RedisStorage.newQueue

  def receive = { case message => atomic { atomicReceive(message) } }

  def atomicReceive: Receive = {
    // enqueue
    case NQ(accountNo) =>
      accounts.enqueue(accountNo.getBytes)
      self.reply(true)

    // dequeue
    case DQ =>
      val d = new String(accounts.dequeue)
      self.reply(d)

    // multiple NQ and DQ
    case MNDQ(enqs, no) =>
      accounts.enqueue(enqs.map(_.getBytes): _*)
      try {
        (1 to no).foreach(e => accounts.dequeue)
      } catch {
        case e: Exception => fail
      }
      self.reply(true)

    // size
    case SZ =>
      self.reply(accounts.size)
  }

  def fail = throw new RuntimeException("Expected exception; to test fault-tolerance")
}

import org.scalatest.junit.JUnitSuite
class RedisPersistentQSpec extends JUnitSuite {
  @Test
  def testSuccessfulNQ = {
    val qa = actorOf(new QueueActor)
    qa.start
    qa !! NQ("a-123")
    qa !! NQ("a-124")
    qa !! NQ("a-125")
    val t = (qa !! SZ).as[Int].get
    assertTrue(3 == t)
  }

  @Test
  def testSuccessfulDQ = {
    val qa = actorOf[QueueActor]
    qa.start
    qa !! NQ("a-123")
    qa !! NQ("a-124")
    qa !! NQ("a-125")
    val s = (qa !! SZ).as[Int].get
    assertTrue(3 == s)
    assertEquals("a-123", (qa !! DQ).get)
    assertEquals("a-124", (qa !! DQ).get)
    assertEquals("a-125", (qa !! DQ).get)
    val t = (qa !! SZ).as[Int].get
    assertTrue(0 == t)
  }

  @Test
  def testSuccessfulMNDQ = {
    val qa = actorOf[QueueActor]
    qa.start

    qa !! NQ("a-123")
    qa !! NQ("a-124")
    qa !! NQ("a-125")
    val t = (qa !! SZ).as[Int].get
    assertTrue(3 == t)
    assertEquals("a-123", (qa !! DQ).get)
    val s = (qa !! SZ).as[Int].get
    assertTrue(2 == s)
    qa !! MNDQ(List("a-126", "a-127"), 2)
    val u = (qa !! SZ).as[Int].get
    assertTrue(2 == u)
  }

  @Test
  def testMixedMNDQ = {
    val qa = actorOf[QueueActor]
    qa.start

    // 3 enqueues
    qa !! NQ("a-123")
    qa !! NQ("a-124")
    qa !! NQ("a-125")

    val t = (qa !! SZ).as[Int].get
    assertTrue(3 == t)

    // dequeue 1
    assertEquals("a-123", (qa !! DQ).get)

    // size == 2
    val s = (qa !! SZ).as[Int].get
    assertTrue(2 == s)

    // enqueue 2, dequeue 2 => size == 2
    qa !! MNDQ(List("a-126", "a-127"), 2)
    val u = (qa !! SZ).as[Int].get
    assertTrue(2 == u)

    // enqueue 2 => size == 4
    qa !! NQ("a-128")
    qa !! NQ("a-129")
    val v = (qa !! SZ).as[Int].get
    assertTrue(4 == v)

    // enqueue 1 => size 5
    // dequeue 6 => fail transaction
    // size should remain 4
    try {
      qa !! MNDQ(List("a-130"), 6)
    } catch { case e: Exception => {} }

    val w = (qa !! SZ).as[Int].get
    assertTrue(4 == w)
  }
}
