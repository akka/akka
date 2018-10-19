/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.scaladsl

import scala.concurrent.Future

import akka.Done
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.cluster.typed.Cluster
import akka.cluster.typed.Join
import akka.persistence.typed.ExpectingReply
import akka.persistence.typed.scaladsl.Effect
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object ClusterShardingPersistenceSpec {
  val config = ConfigFactory.parseString(
    """
      akka.actor.provider = cluster

      akka.remote.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
      akka.remote.artery.canonical.hostname = 127.0.0.1

      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    """)

  sealed trait Command
  final case class Add(s: String) extends Command
  final case class AddWithConfirmation(s: String)(override val replyTo: ActorRef[Done]) extends Command with ExpectingReply[Done]
  final case class Get(replyTo: ActorRef[String]) extends Command
  final case object StopPlz extends Command

  val typeKey = EntityTypeKey[Command]("test")

  def persistentEntity(entityId: String): Behavior[Command] =
    PersistentEntity[Command, String, String](
      entityTypeKey = typeKey,
      entityId = entityId,
      emptyState = "",
      commandHandler = (state, cmd) ⇒ cmd match {
        case Add(s) ⇒
          Effect.persist(s)

        case cmd @ AddWithConfirmation(s) ⇒
          Effect.persist(s)
            .thenReply(cmd)(newState ⇒ Done)

        case Get(replyTo) ⇒
          replyTo ! s"$entityId:$state"
          Effect.none
        case StopPlz ⇒ Effect.stop()
      },
      eventHandler = (state, evt) ⇒ if (state.isEmpty) evt else state + "|" + evt)

}

class ClusterShardingPersistenceSpec extends ScalaTestWithActorTestKit(ClusterShardingPersistenceSpec.config) with WordSpecLike {
  import ClusterShardingPersistenceSpec._

  "Typed cluster sharding with persistent actor" must {

    ClusterSharding(system).start(Entity(
      typeKey,
      ctx ⇒ persistentEntity(ctx.entityId),
      StopPlz
    ))

    Cluster(system).manager ! Join(Cluster(system).selfMember.address)

    "start persistent actor" in {
      val p = TestProbe[String]()

      val ref = ClusterSharding(system).entityRefFor(typeKey, "123")
      ref ! Add("a")
      ref ! Add("b")
      ref ! Add("c")
      ref ! Get(p.ref)
      p.expectMessage("123:a|b|c")
    }

    "support ask with thenReply" in {
      val p = TestProbe[String]()

      val ref = ClusterSharding(system).entityRefFor(typeKey, "456")
      val done1 = ref ? AddWithConfirmation("a")
      done1.futureValue should ===(Done)

      val done2: Future[Done] = ref ? AddWithConfirmation("b")
      done2.futureValue should ===(Done)

      ref ! Get(p.ref)
      p.expectMessage("456:a|b")
    }
  }
}
