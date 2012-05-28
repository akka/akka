/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.actor

//#cache-actor
import akka.util.NonFatal
import akka.util.Duration
import akka.actor.Actor

object RefreshCache

class CacheRefreshException(msg: String, thr: Throwable) extends RuntimeException(msg, thr)

class CacheRefresher(cache: RefreshableCache, refreshEvery: Duration) extends Actor {

  override def preStart() {
    self ! RefreshCache
  }

  protected def receive = {
    case RefreshCache => try {
      cache.refresh()

      context.system.scheduler.scheduleOnce(refreshEvery, self, RefreshCache)
    } catch {
      case NonFatal(ex) => throw new CacheRefreshException("Unable to refresh cache!", ex)
    }
  }
}

//#cache-actor

//#refreshable-cache-trait

import java.util.concurrent.atomic.AtomicReference

trait RefreshableCache { 
  def refresh()
}

//#refreshable-cache-trait

//#example-cache
trait DataCache {
  def isValid(valueToCheck: String): Boolean
}

class FromDbDataCache extends RefreshableCache with DataCache {

  private val mockData = Set("valid") // just for our example

  private val cached = new AtomicReference[Set[String]](mockData)

  override def refresh() {
    val updatedValues = { Thread.sleep(300) /* act busy */; mockData }
    cached set updatedValues
  }

  override def isValid(valueToCheck: String) = cached.get contains valueToCheck
}

//#example-cache

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll

import akka.actor.ActorSystem
import akka.testkit.TestKit

class FromDbDataCacheSpec(_system: ActorSystem) extends TestKit(_system)
  with FlatSpec with ShouldMatchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("FromDbDataCacheSpec"))

  override def afterAll() {    
    system.shutdown()
  }

  it should "validate expected values" in {
    //#cache-actor-setup
    import akka.actor.Props

    // given
    val cache = new FromDbDataCache

    import akka.util.duration._
    system.actorOf(
      Props(new CacheRefresher(cache, refreshEvery = 60.seconds)),
      name = "data-cache-refresher"
    )
    
    // when
    val anything = cache.isValid("anything")
    val valid = cache.isValid("valid")

    // then
    anything should be (false)
    valid should be (true)

    //#cache-actor-setup
  }
}
