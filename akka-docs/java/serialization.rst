Serialization (Java)
====================

Akka serialization module has been documented extensively under the Scala API section. In this section we will point out the different APIs that are available in Akka for Java based serialization of ActorRefs. The Scala APIs of ActorSerialization has implicit Format objects that set up the type class based serialization. In the Java API, the Format objects need to be specified explicitly.

Serialization of ActorRef
=========================

The following are the Java APIs for serialization of local ActorRefs:

.. code-block:: scala

  /**
   * Module for local actor serialization.
   */
  object ActorSerialization {
    // wrapper for implicits to be used by Java
    def fromBinaryJ[T <: Actor](bytes: Array[Byte], format: Format[T]): ActorRef =
      fromBinary(bytes)(format)

    // wrapper for implicits to be used by Java
    def toBinaryJ[T <: Actor](a: ActorRef, format: Format[T], srlMailBox: Boolean = true): Array[Byte] =
      toBinary(a, srlMailBox)(format)
  }

The following steps describe the procedure for serializing an Actor and ActorRef.

Serialization of a Stateless Actor
==================================

Step 1: Define the Actor
------------------------

.. code-block:: scala

  public class SerializationTestActor extends UntypedActor {
      public void onReceive(Object msg) {
          getContext().replySafe("got it!");
      }
  }

Step 2: Define the typeclass instance for the actor
---------------------------------------------------

Note how the generated Java classes are accessed using the $class based naming convention of the Scala compiler.

.. code-block:: scala

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

**Step 3: Serialize and de-serialize**

The following JUnit snippet first creates an actor using the default constructor. The actor is, as we saw above a stateless one. Then it is serialized and de-serialized to get back the original actor. Being stateless, the de-serialized version behaves in the same way on a message as the original actor.

.. code-block:: java

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

Serialization of a Stateful Actor
=================================

Let's now have a look at how to serialize an actor that carries a state with it. Here the expectation is that the serialization of the actor will also persist the state information. And after de-serialization we will get back the state with which it was serialized.

Step 1: Define the Actor
------------------------

Here we consider an actor defined in Scala. We will however serialize using the Java APIs.

.. code-block:: scala

  class MyUntypedActor extends UntypedActor {
    var count = 0
    def onReceive(message: Any): Unit = message match {
      case m: String if m == "hello" =>
        count = count + 1
        getContext.replyUnsafe("world " + count)
      case m: String =>
        count = count + 1
        getContext.replyUnsafe("hello " + m + " " + count)
      case _ =>
        throw new Exception("invalid message type")
    }
  }

Note the actor has a state in the form of an Integer. And every message that the actor receives, it replies with an addition to the integer member.

Step 2: Define the instance of the typeclass
--------------------------------------------

.. code-block:: java

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

Note the usage of Protocol Buffers to serialize the state of the actor.

Step 3: Serialize and de-serialize
----------------------------------

.. code-block:: java

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

Note how the de-serialized version starts with the state value with which it was earlier serialized.
