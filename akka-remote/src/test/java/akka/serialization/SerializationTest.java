package akka.serialization;

import org.junit.Test;
import akka.actor.*;
import akka.actor.serialization.*;
import static org.junit.Assert.*;
import static akka.serialization.ActorSerialization.*;

class SerializationTestActorFormat implements StatelessActorFormat<SerializationTestActor>  {
    @Override
    public SerializationTestActor fromBinary(byte[] bytes, SerializationTestActor act) {
        return (SerializationTestActor) StatelessActorFormat$class.fromBinary(this, bytes, act);
    }

    @Override
    public byte[] toBinary(SerializationTestActor ac) {
        return StatelessActorFormat$class.toBinary(this, ac);
    }
}

class MyUntypedActorFormat implements Format<MyUntypedActor> {
    @Override
    public MyUntypedActor fromBinary(byte[] bytes, MyUntypedActor act) {
      ProtobufProtocol.Counter p =
          (ProtobufProtocol.Counter) new SerializerFactory().getProtobuf().fromBinary(bytes, ProtobufProtocol.Counter.class);
      act.count_$eq(p.getCount());
      return act;
    }

    @Override
    public byte[] toBinary(MyUntypedActor ac) {
      return ProtobufProtocol.Counter.newBuilder().setCount(ac.count()).build().toByteArray();
    }
  }


public class SerializationTest {
/*
  @Test public void mustBeAbleToSerializeAfterCreateActorRefFromClass() {
      ActorRef ref = Actors.actorOf(SerializationTestActor.class);
      assertNotNull(ref);
      ref.start();
      try {
          Object result = ref.sendRequestReply("Hello");
          assertEquals("got it!", result);
      } catch (ActorTimeoutException ex) {
          fail("actor should not time out");
      }

      Format<SerializationTestActor> f = new SerializationTestActorFormat();
      byte[] bytes = toBinaryJ(ref, f, false);
      ActorRef r = fromBinaryJ(bytes, f);
      assertNotNull(r);
      r.start();
      try {
          Object result = r.sendRequestReply("Hello");
          assertEquals("got it!", result);
      } catch (ActorTimeoutException ex) {
          fail("actor should not time out");
      }
      ref.stop();
      r.stop();
  }

  @Test public void mustBeAbleToSerializeAfterCreateActorRefFromFactory() {
      ActorRef ref = Actors.actorOf(new UntypedActorFactory() {
          public Actor create() {
              return new SerializationTestActor();
          }
      });
      assertNotNull(ref);
      ref.start();
      try {
          Object result = ref.sendRequestReply("Hello");
          assertEquals("got it!", result);
      } catch (ActorTimeoutException ex) {
          fail("actor should not time out");
      }

      Format<SerializationTestActor> f = new SerializationTestActorFormat();
      byte[] bytes = toBinaryJ(ref, f, false);
      ActorRef r = fromBinaryJ(bytes, f);
      assertNotNull(r);
      r.start();
      try {
          Object result = r.sendRequestReply("Hello");
          assertEquals("got it!", result);
      } catch (ActorTimeoutException ex) {
          fail("actor should not time out");
      }
      ref.stop();
      r.stop();
  }

  @Test public void mustBeAbleToSerializeAStatefulActor() {
      ActorRef ref = Actors.actorOf(MyUntypedActor.class);
      assertNotNull(ref);
      ref.start();
      try {
          Object result = ref.sendRequestReply("hello");
          assertEquals("world 1", result);
          result = ref.sendRequestReply("hello");
          assertEquals("world 2", result);
      } catch (ActorTimeoutException ex) {
          fail("actor should not time out");
      }

      Format<MyUntypedActor> f = new MyUntypedActorFormat();
      byte[] bytes = toBinaryJ(ref, f, false);
      ActorRef r = fromBinaryJ(bytes, f);
      assertNotNull(r);
      r.start();
      try {
          Object result = r.sendRequestReply("hello");
          assertEquals("world 3", result);
          result = r.sendRequestReply("hello");
          assertEquals("world 4", result);
      } catch (ActorTimeoutException ex) {
          fail("actor should not time out");
      }
      ref.stop();
      r.stop();
  }
  */
}
