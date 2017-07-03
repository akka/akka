package akka.cluster

import java.util.concurrent.TimeUnit

import collection.immutable.SortedSet

import com.typesafe.config.ConfigFactory
import akka.actor._
import akka.cluster.protobuf.ClusterMessageSerializer

import org.openjdk.jmh.annotations.{ Scope => JmhScope, _ }

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

  @Benchmark
  def deserialize: GossipEnvelope =
    serializer.fromBinary(blob, env.getClass).asInstanceOf[GossipEnvelope]

  @TearDown
  def tearDown: Unit =
    system.terminate()
}
