package se.scalablesolutions.akka.persistence.redis

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
case object DQ
case class MNDQ(accountNos: List[String], noOfDQs: Int, failer: Actor)
case object SZ

class QueueActor extends Transactor {
  private lazy val accounts = RedisStorage.newQueue

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
    val t: Int = (qa !! SZ).get
    assertTrue(3 == t)
  }

  @Test
  def testSuccessfulDQ = {
    val qa = new QueueActor
    qa.start
    qa !! NQ("a-123")
    qa !! NQ("a-124")
    qa !! NQ("a-125")
    val s: Int = (qa !! SZ).get
    assertTrue(3 == s)
    assertEquals("a-123", (qa !! DQ).get)
    assertEquals("a-124", (qa !! DQ).get)
    assertEquals("a-125", (qa !! DQ).get)
    val t: Int = (qa !! SZ).get
    assertTrue(0 == t)
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
    val t: Int = (qa !! SZ).get
    assertTrue(3 == t)
    assertEquals("a-123", (qa !! DQ).get)
    val s: Int = (qa !! SZ).get
    assertTrue(2 == s)
    qa !! MNDQ(List("a-126", "a-127"), 2, failer)
    val u: Int = (qa !! SZ).get
    assertTrue(2 == u)
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

    val t: Int = (qa !! SZ).get
    assertTrue(3 == t)

    // dequeue 1
    assertEquals("a-123", (qa !! DQ).get)

    // size == 2
    val s: Int = (qa !! SZ).get
    assertTrue(2 == s)

    // enqueue 2, dequeue 2 => size == 2
    qa !! MNDQ(List("a-126", "a-127"), 2, failer)
    val u: Int = (qa !! SZ).get
    assertTrue(2 == u)

    // enqueue 2 => size == 4
    qa !! NQ("a-128")
    qa !! NQ("a-129")
    val v: Int = (qa !! SZ).get
    assertTrue(4 == v)

    // enqueue 1 => size 5
    // dequeue 6 => fail transaction
    // size should remain 4
    try {
      qa !! MNDQ(List("a-130"), 6, failer)
    } catch { case e: Exception => {} }

    val w: Int = (qa !! SZ).get
    assertTrue(4 == w)
  }
}
