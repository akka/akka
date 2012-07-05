/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.matchers.MustMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import akka.config.TypedActorConfigurator
import akka.dispatch.Dispatchers
import ticket855.{ Ticket855ServiceImpl, Ticket855Service }
import akka.routing.CyclicIterator
import org.scalatest.{ BeforeAndAfterAll, WordSpec, BeforeAndAfterEach }
import akka.util.Duration

@RunWith(classOf[JUnitRunner])
class Issue855Spec extends WordSpec with MustMatchers with BeforeAndAfterEach with BeforeAndAfterAll {

  import akka.actor.Actor._

  override def afterEach {
    registry.shutdownAll()
    remote.shutdown
  }

  "TypedActors with Future return types" must {
    "must work for Remote Typed Actors" in {
      remote.start
      Thread.sleep(500)
      val configuration = new TypedActorConfiguration

      configuration.timeout(Duration(6600L, "ms"))

      configuration.dispatcher(Dispatchers.newExecutorBasedEventDrivenDispatcher("pooled-dispatcher")
        .withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity
        .setCorePoolSize(60)
        .setMaxPoolSize(60)
        .build)

      for (i ← 0 to 60)
        remote.registerTypedActor("ticket855-" + i,
          TypedActor.newInstance(classOf[Ticket855Service], classOf[Ticket855ServiceImpl], configuration))

      val address = remote.address

      val iterator = new CyclicIterator(for (i ← 1 to 60) yield remote.typedActorFor(classOf[Ticket855Service], "ticket855-" + i, 6600L, address.getAddress.getHostName, address.getPort))

      val results = for (i ← 1 to 120) yield (i, iterator.next.callAndWait("ping - " + i, 200L))

      for ((i, r) ← results) assert(r.get === "x: ping - " + i)
    }

    "must work for Local Typed Actors" in {

      val configuration = new TypedActorConfiguration

      configuration.timeout(Duration(6600L, "ms"))

      configuration.dispatcher(Dispatchers.newExecutorBasedEventDrivenDispatcher("pooled-dispatcher")
        .withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity
        .setCorePoolSize(60)
        .setMaxPoolSize(60)
        .build)

      val iterator = new CyclicIterator(for (i ← 1 to 60) yield TypedActor.newInstance(classOf[Ticket855Service], classOf[Ticket855ServiceImpl], configuration))

      val results = for (i ← 1 to 120) yield (i, iterator.next.callAndWait("ping - " + i, 200L))

      for ((i, r) ← results) assert(r.get === "x: ping - " + i)
    }
  }
}
