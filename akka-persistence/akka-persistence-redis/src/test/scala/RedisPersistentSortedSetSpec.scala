package akka.persistence.redis

import org.scalatest.Spec
import org.scalatest.Assertions
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import akka.actor.{Actor, ActorRef, Transactor}
import Actor._

/**
 * A persistent actor based on Redis sortedset storage.
 * <p/>
 * Needs a running Redis server.
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */

trait ZScorable {
  def zscore: Float
}

case class Hacker(name: String, birth: String) extends ZScorable {
  def zscore = birth.toFloat
}

class SetThresholdViolationException extends RuntimeException

// add hacker to the set
case class ADD(h: Hacker)

// remove hacker from set
case class REMOVE(h: Hacker)

// size of the set
case object SIZE

// zscore of the hacker
case class SCORE(h: Hacker)

// zrange
case class RANGE(start: Int, end: Int)

// add and remove subject to the condition that there will be at least 3 hackers
case class MULTI(add: List[Hacker], rem: List[Hacker], failer: ActorRef)

case class MULTIRANGE(add: List[Hacker])

class SortedSetActor extends Transactor {
  self.timeout = 100000
  private lazy val hackers = RedisStorage.newSortedSet

  def receive = {
    case ADD(h) =>
      hackers.+(h.name.getBytes, h.zscore)
      self.reply(true)

    case REMOVE(h) =>
      hackers.-(h.name.getBytes)
      self.reply(true)

    case SIZE =>
      self.reply(hackers.size)

    case SCORE(h) =>
      self.reply(hackers.zscore(h.name.getBytes))

    case RANGE(s, e) =>
      self.reply(hackers.zrange(s, e))

    case MULTI(a, r, failer) =>
      a.foreach{ h: Hacker =>
        hackers.+(h.name.getBytes, h.zscore)
      }
      try {
        r.foreach { h =>
          if (hackers.size <= 3)
            throw new SetThresholdViolationException
          hackers.-(h.name.getBytes)
        }
      } catch {
        case e: Exception =>
          failer !! "Failure"
      }
      self.reply((a.size, r.size))

    case MULTIRANGE(hs) =>
      hs.foreach{ h: Hacker =>
        hackers.+(h.name.getBytes, h.zscore)
      }
      self.reply(hackers.zrange(0, -1))
  }
}

import RedisStorageBackend._

@RunWith(classOf[JUnitRunner])
class RedisPersistentSortedSetSpec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterAll {

  override def beforeAll {
    flushDB
    println("** destroyed database")
  }

  override def afterAll {
    flushDB
    println("** destroyed database")
  }

  val h1 = Hacker("Alan kay", "1940")
  val h2 = Hacker("Richard Stallman", "1953")
  val h3 = Hacker("Yukihiro Matsumoto", "1965")
  val h4 = Hacker("Claude Shannon", "1916")
  val h5 = Hacker("Linus Torvalds", "1969")
  val h6 = Hacker("Alan Turing", "1912")

  describe("Add and report cardinality of the set") {
    val qa = actorOf[SortedSetActor]
    qa.start

    it("should enter 6 hackers") {
      qa !! ADD(h1)
      qa !! ADD(h2)
      qa !! ADD(h3)
      qa !! ADD(h4)
      qa !! ADD(h5)
      qa !! ADD(h6)
      (qa !! SIZE).get.asInstanceOf[Int] should equal(6)
    }

    it("should fetch correct scores for hackers") {
      (qa !! SCORE(h1)).get.asInstanceOf[Float] should equal(1940.0f)
      (qa !! SCORE(h5)).get.asInstanceOf[Float] should equal(1969.0f)
      (qa !! SCORE(h6)).get.asInstanceOf[Float] should equal(1912.0f)
    }

    it("should fetch proper range") {
      (qa !! RANGE(0, 4)).get.asInstanceOf[List[_]].size should equal(5)
      (qa !! RANGE(0, 6)).get.asInstanceOf[List[_]].size should equal(6)
    }

    it("should remove and throw exception for removing non-existent hackers") {
      qa !! REMOVE(h2)
      (qa !! SIZE).get.asInstanceOf[Int] should equal(5)
      qa !! REMOVE(h3)
      (qa !! SIZE).get.asInstanceOf[Int] should equal(4)
      val h7 = Hacker("Paul Snively", "1952")
      try {
        qa !! REMOVE(h7)
      }
      catch {
        case e: NoSuchElementException =>
          e.getMessage should endWith("not present")
      }
    }

    it("should change score for entering the same hacker name with diff score") {
      (qa !! SIZE).get.asInstanceOf[Int] should equal(4)

      // same name as h6
      val h7 = Hacker("Alan Turing", "1992")
      qa !! ADD(h7)

      // size remains same
      (qa !! SIZE).get.asInstanceOf[Int] should equal(4)

      // score updated
      (qa !! SCORE(h7)).get.asInstanceOf[Float] should equal(1992.0f)
    }
  }

  describe("Transaction semantics") {
    it("should rollback on exception") {
      val qa = actorOf[SortedSetActor]
      qa.start

      val failer = actorOf[PersistentFailerActor]
      failer.start

      (qa !! SIZE).get.asInstanceOf[Int] should equal(0)
      val add = List(h1, h2, h3, h4)
      val rem = List(h2)
      (qa !! MULTI(add, rem, failer)).get.asInstanceOf[Tuple2[Int, Int]] should equal((4,1))
      (qa !! SIZE).get.asInstanceOf[Int] should equal(3)
      // size == 3

      // add 2 more
      val add1 = List(h5, h6)

      // remove 3
      val rem1 = List(h1, h3, h4, h5)
      try {
        qa !! MULTI(add1, rem1, failer)
      } catch { case e: RuntimeException => {} }
      (qa !! SIZE).get.asInstanceOf[Int] should equal(3)
    }
  }

  describe("zrange") {
    it ("should report proper range") {
      val qa = actorOf[SortedSetActor]
      qa.start
      qa !! ADD(h1)
      qa !! ADD(h2)
      qa !! ADD(h3)
      qa !! ADD(h4)
      qa !! ADD(h5)
      qa !! ADD(h6)
      (qa !! SIZE).get.asInstanceOf[Int] should equal(6)
      val l = (qa !! RANGE(0, 6)).get.asInstanceOf[List[(Array[Byte], Float)]]
      l.map { case (e, s) => (new String(e), s) }.head should equal(("Alan Turing", 1912.0f))
      val h7 = Hacker("Alan Turing", "1992")
      qa !! ADD(h7)
      (qa !! SIZE).get.asInstanceOf[Int] should equal(6)
      val m = (qa !! RANGE(0, 6)).get.asInstanceOf[List[(Array[Byte], Float)]]
      m.map { case (e, s) => (new String(e), s) }.head should equal(("Claude Shannon", 1916.0f))
    }

    it ("should report proper rge") {
      val qa = actorOf[SortedSetActor]
      qa.start
      qa !! ADD(h1)
      qa !! ADD(h2)
      qa !! ADD(h3)
      qa !! ADD(h4)
      qa !! ADD(h5)
      qa !! ADD(h6)
      (qa !! SIZE).get.asInstanceOf[Int] should equal(6)
      (qa !! RANGE(0, 5)).get.asInstanceOf[List[_]].size should equal(6)
      (qa !! RANGE(0, 6)).get.asInstanceOf[List[_]].size should equal(6)
      (qa !! RANGE(0, 3)).get.asInstanceOf[List[_]].size should equal(4)
      (qa !! RANGE(0, 1)).get.asInstanceOf[List[_]].size should equal(2)
      (qa !! RANGE(0, 0)).get.asInstanceOf[List[_]].size should equal(1)
      (qa !! RANGE(3, 1)).get.asInstanceOf[List[_]].size should equal(0)
      (qa !! RANGE(0, -1)).get.asInstanceOf[List[_]].size should equal(6)
      (qa !! RANGE(0, -2)).get.asInstanceOf[List[_]].size should equal(5)
      (qa !! RANGE(0, -4)).get.asInstanceOf[List[_]].size should equal(3)
      (qa !! RANGE(-4, -1)).get.asInstanceOf[List[_]].size should equal(4)
    }
  }

  describe("zrange with equal values and equal score") {
    it ("should report proper range") {
      val qa = actorOf[SortedSetActor]
      qa.start

      val failer = actorOf[PersistentFailerActor]
      failer.start

      (qa !! SIZE).get.asInstanceOf[Int] should equal(0)
      val add = List(h1, h2, h3, h4, h5, h6)
      val rem = List(h2)
      (qa !! MULTI(add, rem, failer)).get.asInstanceOf[Tuple2[Int, Int]] should equal((6,1))
      (qa !! SIZE).get.asInstanceOf[Int] should equal(5)

      // has equal score as h6
      val h7 = Hacker("Debasish Ghosh", "1912")

      // has equal value as h6
      val h8 = Hacker("Alan Turing", "1992")

      val ret = (qa !! MULTIRANGE(List(h7, h8))).get.asInstanceOf[List[(Array[Byte], Float)]]
      ret.size should equal(6)
      val m = collection.immutable.Map() ++ ret.map(e => (new String(e._1), e._2))
      m("Debasish Ghosh") should equal(1912f)
      m("Alan Turing") should equal(1992f)
    }
  }
}
