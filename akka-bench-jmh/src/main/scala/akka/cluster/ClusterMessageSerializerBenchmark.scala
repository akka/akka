package akka.cluster

import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.duration._

import collection.immutable.SortedSet
import com.typesafe.config.ConfigFactory
import akka.actor._
import akka.cluster.protobuf.ClusterMessageSerializer
import akka.cluster.protobuf.msg.{ ClusterMessages => cm }
import org.openjdk.jmh.annotations.{ Scope => JmhScope, _ }

import scala.concurrent.duration.Deadline

@Fork(2)
@State(JmhScope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@Warmup(iterations = 4)
@Measurement(iterations = 5)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class ClusterMessageSerializerBenchmark {
  val system = ActorSystem("test", ConfigFactory.parseString("akka.actor.provider = cluster"))
  val serializer = new ClusterMessageSerializer(system.asInstanceOf[ExtendedActorSystem])

  val a1 = TestMember(Address("akka.tcp", "sys", "a", 2552), MemberStatus.Joining, Set.empty)
  val d1 = TestMember(Address("akka.tcp", "sys", "d", 2552), MemberStatus.Exiting, Set("r1", "team-foo"))
  val env = GossipEnvelope(a1.uniqueAddress, d1.uniqueAddress, Gossip(SortedSet(a1, d1)))
  val blob = serializer.toBinary(env)

  val cmGossip = gossipFromEnvelope(cm.GossipEnvelope.parseFrom(blob))

  @Benchmark
  def deserialize = {
    //    serializer.gossipEnvelopeFromBinary(blob)
    //        serializer.fromBinary(blob, env.getClass).asInstanceOf[GossipEnvelope]
    serializer.gossipFromProto(cmGossip)
    //  : GossipEnvelope =
  }

  @TearDown
  def tearDown: Unit =
    Await.result(system.terminate(), 30.seconds)

  private def gossipFromEnvelope(envelope: cm.GossipEnvelope): cm.Gossip = {
    val serializedGossip = envelope.getSerializedGossip
    cm.Gossip.parseFrom(serializer.decompress(serializedGossip.toByteArray))
  }

}
