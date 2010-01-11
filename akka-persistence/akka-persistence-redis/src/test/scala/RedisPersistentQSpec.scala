package se.scalablesolutions.akka.state

import junit.framework.TestCase

import org.junit.{Test, Before}
import org.junit.Assert._

import se.scalablesolutions.akka.actor.{Actor, Transactor}

/**
 * A persistent actor based on Redis queue storage.
 * <p/>
 * Needs a running Redis server.
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */

case class NQ(accountNo: String)
case class DQ
case class MNDQ(accountNos: List[String], noOfDQs: Int, failer: Actor)
case class SZ

class QueueActor extends Transactor {
  private val accounts = RedisStorage.newQueue

  def receive = {
    // enqueue
    case NQ(accountNo) =>
      accounts.enqueue(accountNo.getBytes)
      reply(true)

    // dequeue
    case DQ =>
      val d = new String(accounts.dequeue)
      reply(d)

    // multiple NQ and DQ
    case MNDQ(enqs, no, failer) =>
      accounts.enqueue(enqs.map(_.getBytes): _*)
      try {
        (1 to no).foreach(e => accounts.dequeue)
      } catch {
        case e: Exception => 
          failer !! "Failure"
      }
      reply(true)

    // size
    case SZ =>
      reply(accounts.size)
  }
}

class RedisPersistentQSpec extends TestCase {
  @Test
  def testSuccessfulNQ = {
    val qa = new QueueActor
    qa.start
    qa !! NQ("a-123")
    qa !! NQ("a-124")
    qa !! NQ("a-125")
    assertEquals(3, (qa !! SZ).get)
  }

  @Test
  def testSuccessfulDQ = {
    val qa = new QueueActor
    qa.start
    qa !! NQ("a-123")
    qa !! NQ("a-124")
    qa !! NQ("a-125")
    assertEquals(3, (qa !! SZ).get)
    assertEquals("a-123", (qa !! DQ).get)
    assertEquals("a-124", (qa !! DQ).get)
    assertEquals("a-125", (qa !! DQ).get)
    assertEquals(0, (qa !! SZ).get)
  }

  @Test
  def testSuccessfulMNDQ = {
    val qa = new QueueActor
    qa.start
    val failer = new PersistentFailerActor
    failer.start

    qa !! NQ("a-123")
    qa !! NQ("a-124")
    qa !! NQ("a-125")
    assertEquals(3, (qa !! SZ).get)
    assertEquals("a-123", (qa !! DQ).get)
    assertEquals(2, (qa !! SZ).get)
    qa !! MNDQ(List("a-126", "a-127"), 2, failer)
    assertEquals(2, (qa !! SZ).get)
  }

  @Test
  def testMixedMNDQ = {
    val qa = new QueueActor
    qa.start
    val failer = new PersistentFailerActor
    failer.start

    // 3 enqueues
    qa !! NQ("a-123")
    qa !! NQ("a-124")
    qa !! NQ("a-125")

    assertEquals(3, (qa !! SZ).get)

    // dequeue 1
    assertEquals("a-123", (qa !! DQ).get)

    // size == 2
    assertEquals(2, (qa !! SZ).get)

    // enqueue 2, dequeue 2 => size == 2
    qa !! MNDQ(List("a-126", "a-127"), 2, failer)
    assertEquals(2, (qa !! SZ).get)

    // enqueue 2 => size == 4
    qa !! NQ("a-128")
    qa !! NQ("a-129")
    assertEquals(4, (qa !! SZ).get)

    // enqueue 1 => size 5
    // dequeue 6 => fail transaction
    // size should remain 4
    try {
      qa !! MNDQ(List("a-130"), 6, failer)
    } catch { case e: Exception => {} }

    assertEquals(4, (qa !! SZ).get)
  }
}
