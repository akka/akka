
.. _serialization-scala:

#######################
 Serialization (Scala)
#######################

.. sidebar:: Contents

   .. contents:: :local:

Module stability: **SOLID**


Serialization of ActorRef
=========================

An Actor can be serialized in two different ways:

* Serializable RemoteActorRef - Serialized to an immutable, network-aware Actor
  reference that can be freely shared across the network. They "remember" and
  stay mapped to their original Actor instance and host node, and will always
  work as expected.

* Serializable LocalActorRef - Serialized by doing a deep copy of both the
  ActorRef and the Actor instance itself. Can be used to physically move an
  Actor from one node to another and continue the execution there.

Both of these can be sent as messages over the network and/or store them to
disk, in a persistent storage backend etc.

Actor serialization in Akka is implemented through a type class ``Format[T <:
Actor]`` which publishes the ``fromBinary`` and ``toBinary`` methods for
serialization. Here's the complete definition of the type class::

  /**
   * Type class definition for Actor Serialization
   */
  trait FromBinary[T <: Actor] {
    def fromBinary(bytes: Array[Byte], act: T): T
  }

  trait ToBinary[T <: Actor] {
    def toBinary(t: T): Array[Byte]
  }

  // client needs to implement Format[] for the respective actor
  trait Format[T <: Actor] extends FromBinary[T] with ToBinary[T]


Deep serialization of an Actor and ActorRef
-------------------------------------------

You can serialize the whole actor deeply, e.g. both the ``ActorRef`` and then
instance of its ``Actor``. This can be useful if you want to move an actor from
one node to another, or if you want to store away an actor, with its state, into
a database.

Here is an example of how to serialize an Actor.

Step 1: Define the actor::

  class MyActor extends Actor {
    var count = 0

    def receive = {
      case "hello" =>
        count = count + 1
        self.reply("world " + count)
    }
  }

Step 2: Implement the type class for the actor. ProtobufProtocol.Counter is
something you need to define yourself, as explained in the Protobuf section::

  import akka.serialization.{Serializer, Format}
  import akka.actor.Actor
  import akka.actor.Actor._

  object BinaryFormatMyActor {
    implicit object MyActorFormat extends Format[MyActor] {
      def fromBinary(bytes: Array[Byte], act: MyActor) = {
        val p = Serializer.Protobuf.fromBinary(bytes, Some(classOf[ProtobufProtocol.Counter])).asInstanceOf[ProtobufProtocol.Counter]
        act.count = p.getCount
        act
      }
      def toBinary(ac: MyActor) =
        ProtobufProtocol.Counter.newBuilder.setCount(ac.count).build.toByteArray
    }
  }

Step 3: Import the type class module definition and serialize / de-serialize::

  it("should be able to serialize and de-serialize a stateful actor") {
    import akka.serialization.ActorSerialization._
    import BinaryFormatMyActor._

    val actor1 = actorOf[MyActor]
    (actor1 ? "hello").as[String].getOrElse("_") should equal("world 1")
    (actor1 ? "hello").as[String].getOrElse("_") should equal("world 2")

    val bytes = toBinary(actor1)
    val actor2 = fromBinary(bytes)
    (actor2 ? "hello").as[String].getOrElse("_") should equal("world 3")
  }

Helper Type Class for Stateless Actors
--------------------------------------

If your actor is stateless, then you can use the helper trait that Akka provides
to serialize / de-serialize. Here's the definition::

  trait StatelessActorFormat[T <: Actor] extends Format[T] {
    def fromBinary(bytes: Array[Byte], act: T) = act
    def toBinary(ac: T) = Array.empty[Byte]
  }

Then you use it as follows::

  class MyStatelessActor extends Actor {
    def receive = {
      case "hello" =>
        self.reply("world")
    }
  }

Just create an object for the helper trait for your actor::

  object BinaryFormatMyStatelessActor {
    implicit object MyStatelessActorFormat extends StatelessActorFormat[MyStatelessActor]
  }

and use it for serialization::

  it("should be able to serialize and de-serialize a stateless actor") {
    import akka.serialization.ActorSerialization._
    import BinaryFormatMyStatelessActor._

    val actor1 = actorOf[MyStatelessActor]
    (actor1 ? "hello").as[String].getOrElse("_") should equal("world")
    (actor1 ? "hello").as[String].getOrElse("_") should equal("world")

    val bytes = toBinary(actor1)
    val actor2 = fromBinary(bytes)
    (actor2 ? "hello").as[String].getOrElse("_") should equal("world")
  }


Helper Type Class for actors with external serializer
-----------------------------------------------------

Use the trait ``SerializerBasedActorFormat`` for specifying serializers::

  trait SerializerBasedActorFormat[T <: Actor] extends Format[T] {
    val serializer: Serializer
    def fromBinary(bytes: Array[Byte], act: T) = serializer.fromBinary(bytes, Some(act.self.actorClass)).asInstanceOf[T]
    def toBinary(ac: T) = serializer.toBinary(ac)
  }

For a Java serializable actor::

  class MyJavaSerializableActor extends Actor with scala.Serializable {
    var count = 0

    def receive = {
      case "hello" =>
        count = count + 1
        self.reply("world " + count)
    }
  }

Create a module for the type class::

  import akka.serialization.{SerializerBasedActorFormat, Serializer}

  object BinaryFormatMyJavaSerializableActor {
    implicit object MyJavaSerializableActorFormat extends SerializerBasedActorFormat[MyJavaSerializableActor] {
      val serializer = Serializer.Java
    }
  }

and serialize / de-serialize::

  it("should be able to serialize and de-serialize a stateful actor with a given serializer") {
    import akka.actor.Actor._
    import akka.serialization.ActorSerialization._
    import BinaryFormatMyJavaSerializableActor._

    val actor1 = actorOf[MyJavaSerializableActor]
    (actor1 ? "hello").as[String].getOrElse("_") should equal("world 1")
    (actor1 ? "hello").as[String].getOrElse("_") should equal("world 2")

    val bytes = toBinary(actor1)
    val actor2 = fromBinary(bytes)
    (actor2 ? "hello").as[String].getOrElse("_") should equal("world 3")
  }


Serialization of a RemoteActorRef
=================================

You can serialize an ``ActorRef`` to an immutable, network-aware Actor reference
that can be freely shared across the network, a reference that "remembers" and
stay mapped to its original Actor instance and host node, and will always work
as expected.

The ``RemoteActorRef`` serialization is based upon Protobuf (Google Protocol
Buffers) and you don't need to do anything to use it, it works on any
``ActorRef``.

Currently Akka will **not** autodetect an ``ActorRef`` as part of your message
and serialize it for you automatically, so you have to do that manually or as
part of your custom serialization mechanisms.

Here is an example of how to serialize an Actor::

  import akka.serialization.RemoteActorSerialization._

  val actor1 = actorOf[MyActor]

  val bytes = toRemoteActorRefProtocol(actor1).toByteArray

To deserialize the ``ActorRef`` to a ``RemoteActorRef`` you need to use the
``fromBinaryToRemoteActorRef(bytes: Array[Byte])`` method on the ``ActorRef``
companion object::

  import akka.serialization.RemoteActorSerialization._
  val actor2 = fromBinaryToRemoteActorRef(bytes)

You can also pass in a class loader to load the ``ActorRef`` class and
dependencies from::

  import akka.serialization.RemoteActorSerialization._
  val actor2 = fromBinaryToRemoteActorRef(bytes, classLoader)


Deep serialization of a TypedActor
==================================

Serialization of typed actors works almost the same way as untyped actors. You
can serialize the whole actor deeply, e.g. both the 'proxied ActorRef' and the
instance of its ``TypedActor``.

Here is the example from above implemented as a TypedActor.


Step 1: Define the actor::

  import akka.actor.TypedActor

  trait MyTypedActor {
    def requestReply(s: String) : String
    def oneWay() : Unit
  }

  class MyTypedActorImpl extends TypedActor with MyTypedActor {
    var count = 0

    override def requestReply(message: String) : String = {
      count = count + 1
      "world " + count
    }

    override def oneWay() {
      count = count + 1
    }
  }

Step 2: Implement the type class for the actor::

  import akka.serialization.{Serializer, Format}

  class MyTypedActorFormat extends Format[MyTypedActorImpl] {
    def fromBinary(bytes: Array[Byte], act: MyTypedActorImpl) = {
      val p = Serializer.Protobuf.fromBinary(bytes, Some(classOf[ProtobufProtocol.Counter])).asInstanceOf[ProtobufProtocol.Counter]
      act.count = p.getCount
      act
    }
    def toBinary(ac: MyTypedActorImpl) = ProtobufProtocol.Counter.newBuilder.setCount(ac.count).build.toByteArray
  }

Step 3: Import the type class module definition and serialize / de-serialize::

  import akka.serialization.TypedActorSerialization._

  val typedActor1 = TypedActor.newInstance(classOf[MyTypedActor], classOf[MyTypedActorImpl], 1000)

  val f = new MyTypedActorFormat
  val bytes = toBinaryJ(typedActor1, f)

  val typedActor2: MyTypedActor = fromBinaryJ(bytes, f)   //type hint needed
  typedActor2.requestReply("hello")



Serialization of a remote typed ActorRef
========================================

To deserialize the TypedActor to a ``RemoteTypedActorRef`` (an aspectwerkz proxy
to a RemoteActorRef) you need to use the
``fromBinaryToRemoteTypedActorRef(bytes: Array[Byte])`` method on
``RemoteTypedActorSerialization`` object::

  import akka.serialization.RemoteTypedActorSerialization._
  val typedActor = fromBinaryToRemoteTypedActorRef(bytes)

  // you can also pass in a class loader
  val typedActor2 = fromBinaryToRemoteTypedActorRef(bytes, classLoader)


Compression
===========

Akka has a helper class for doing compression of binary data. This can be useful
for example when storing data in one of the backing storages. It currently
supports LZF which is a very fast compression algorithm suited for runtime
dynamic compression.

Here is an example of how it can be used:::

  import akka.serialization.Compression

  val bytes: Array[Byte] = ...
  val compressBytes = Compression.LZF.compress(bytes)
  val uncompressBytes = Compression.LZF.uncompress(compressBytes)


Using the Serializable trait and Serializer class for custom serialization
==========================================================================

If you are sending messages to a remote Actor and these messages implement one
of the predefined interfaces/traits in the ``akka.serialization.Serializable.*``
object, then Akka will transparently detect which serialization format it should
use as wire protocol and will automatically serialize and deserialize the
message according to this protocol.

Each serialization interface/trait in ``akka.serialization.Serializable.*`` has
a matching serializer in ``akka.serialization.Serializer.*``.

Note however that if you are using one of the Serializable interfaces then you
don’t have to do anything else in regard to sending remote messages.

The ones currently supported are (besides the default which is regular Java
serialization):

- ScalaJSON (Scala only)
- JavaJSON (Java but some Scala structures)
- Protobuf (Scala and Java)

Apart from the above, Akka also supports Scala object serialization through
`SJSON <http://github.com/debasishg/sjson/tree/master>`_ that implements APIs
similar to ``akka.serialization.Serializer.*``. See the section on SJSON below
for details.


Protobuf
========

Akka supports using `Google Protocol Buffers`_ to serialize your
objects. Protobuf is a very efficient network serialization protocol which is
also used internally by Akka. The remote actors understand Protobuf messages so
if you just send them as they are they will be correctly serialized and
unserialized.

.. _Google Protocol Buffers: http://code.google.com/p/protobuf

Here is an example.

Let's say you have this Protobuf message specification that you want to use as
message between remote actors. First you need to compiled it with 'protoc'
compiler::

  message ProtobufPOJO {
    required uint64 id = 1;
    required string name = 2;
    required bool status = 3;
  }

When you compile the spec you will among other things get a message builder. You
then use this builder to create the messages to send over the wire::

  val resultFuture = remoteActor ? ProtobufPOJO.newBuilder
      .setId(11)
      .setStatus(true)
      .setName("Coltrane")
      .build

The remote Actor can then receive the Protobuf message typed as-is::

  class MyRemoteActor extends Actor {
    def receive = {
      case pojo: ProtobufPOJO =>
       val id = pojo.getId
       val status = pojo.getStatus
       val name = pojo.getName
        ...
    }
  }


JSON: Scala
===========

Use the ``akka.serialization.Serializable.ScalaJSON`` base class with its toJSON
method. Akka’s Scala JSON is based upon the SJSON library.

For your POJOs to be able to serialize themselves you have to extend the
ScalaJSON[] trait as follows. JSON serialization is based on a type class
protocol which you need to define for your own abstraction. The instance of the
type class is defined as an implicit object which is used for serialization and
de-serialization. You also need to implement the methods in terms of the APIs
which sjson publishes.

.. code-block:: scala

  import akka.serialization._
  import akka.serialization.Serializable.ScalaJSON
  import akka.serialization.JsonSerialization._
  import akka.serialization.DefaultProtocol._

  case class MyMessage(val id: String, val value: Tuple2[String, Int]) extends ScalaJSON[MyMessage] {
    // type class instance
    implicit val MyMessageFormat: sjson.json.Format[MyMessage] =
      asProduct2("id", "value")(MyMessage)(MyMessage.unapply(_).get)

    def toJSON: String = JsValue.toJson(tojson(this))
    def toBytes: Array[Byte] = tobinary(this)
    def fromBytes(bytes: Array[Byte]) = frombinary[MyMessage](bytes)
    def fromJSON(js: String) = fromjson[MyMessage](Js(js))
  }

  // sample test case
  it("should be able to serialize and de-serialize MyMessage") {
    val s = MyMessage("Target", ("cooker", 120))
    s.fromBytes(s.toBytes) should equal(s)
    s.fromJSON(s.toJSON) should equal(s)
  }

Use akka.serialization.Serializers.ScalaJSON to do generic JSON serialization,
e.g. serialize object that does not extend ScalaJSON using the JSON
serializer. Serialization using Serializer can be done in two ways :-

1. Type class based serialization (recommended)
2. Reflection based serialization

We will discuss both of these techniques in this section. For more details refer
to the discussion in the next section SJSON: Scala.


Serializer API using type classes
---------------------------------

Here are the steps that you need to follow:

1. Define your class::

      case class MyMessage(val id: String, val value: Tuple2[String, Int])

2. Define the type class instance::

     import akka.serialization.DefaultProtocol._
     implicit val MyMessageFormat: sjson.json.Format[MyMessage] =
       asProduct2("id", "value")(MyMessage)(MyMessage.unapply(_).get)

3. Serialize::

     import akka.serialization.Serializer.ScalaJSON
     import akka.serialization.JsonSerialization._

     val o = MyMessage("dg", ("akka", 100))
     fromjson[MyMessage](tojson(o)) should equal(o)
     frombinary[MyMessage](tobinary(o)) should equal(o)


Serializer API using reflection
-------------------------------

You can also use the Serializer abstraction to serialize using the reflection
based serialization API of sjson. But we recommend using the type class based
one, because reflection based serialization has limitations due to type
erasure. Here's an example of reflection based serialization::

  import scala.reflect.BeanInfo
  import akka.serialization.Serializer

  @BeanInfo case class Foo(name: String) {
    def this() = this(null)  // default constructor is necessary for deserialization
  }

  val foo = Foo("bar")

  val json = Serializer.ScalaJSON.toBinary(foo)

  val fooCopy = Serializer.ScalaJSON.fromBinary(json) // returns a JsObject as an AnyRef

  val fooCopy2 = Serializer.ScalaJSON.fromJSON(new String(json)) // can also take a string as input

  val fooCopy3 = Serializer.ScalaJSON.fromBinary[Foo](json).asInstanceOf[Foo]

Classes without a @BeanInfo annotation cannot be serialized as JSON.
So if you see something like that::

  scala> Serializer.ScalaJSON.out(bar)
  Serializer.ScalaJSON.out(bar)
  java.lang.UnsupportedOperationException: Class class Bar not supported for conversion
          at sjson.json.JsBean$class.toJSON(JsBean.scala:210)
          at sjson.json.Serializer$SJSON$.toJSON(Serializer.scala:107)
          at sjson.json.Serializer$SJSON$class.out(Serializer.scala:37)
          at sjson.json.Serializer$SJSON$.out(Serializer.scala:107)
          at akka.serialization.Serializer$ScalaJSON...

it means, that you haven't got a @BeanInfo annotation on your class.

You may also see this exception when trying to serialize a case class without
any attributes, like this::

  @BeanInfo case class Empty() // cannot be serialized


SJSON: Scala
============

SJSON supports serialization of Scala objects into JSON. It implements support
for built in Scala structures like List, Map or String as well as custom
objects. SJSON is available as an Apache 2 licensed project on Github `here
<http://github.com/debasishg/sjson/tree/master>`_.

Example: I have a Scala object as::

  val addr = Address("Market Street", "San Francisco", "956871")

where Address is a custom class defined by the user. Using SJSON, I can store it
as JSON and retrieve as plain old Scala object. Here’s the simple assertion that
validates the invariant. Note that during de-serialziation, the class name is
specified. Hence what it gives back is an instance of Address::

  val serializer = sjson.json.Serializer.SJSON

  addr should equal(
    serializer.in[Address](serializer.out(addr)))

Note, that the class needs to have a default constructor. Otherwise the
deserialization into the specified class will fail.

There are situations, particularly when writing generic persistence libraries in
Akka, when the exact class is not known during de-serialization. Using SJSON I
can get it as AnyRef or Nothing::

  serializer.in[AnyRef](serializer.out(addr))

or just as::

  serializer.in(serializer.out(addr))

What you get back from is a JsValue, an abstraction of the JSON object
model. For details of JsValueimplementation, refer to `dispatch-json
<http://databinder.net/dispatch/About>`_ that SJSON uses as the underlying JSON
parser implementation. Once I have the JsValue model, I can use use extractors
to get back individual attributes::

  val serializer = sjson.json.Serializer.SJSON

  val a = serializer.in[AnyRef](serializer.out(addr))

  // use extractors
  val c = 'city ? str
  val c(_city) = a
  _city should equal("San Francisco")

  val s = 'street ? str
  val s(_street) = a
  _street should equal("Market Street")

  val z = 'zip ? str
  val z(_zip) = a
  _zip should equal("956871")


Serialization of Embedded Objects
---------------------------------

SJSON supports serialization of Scala objects that have other embedded
objects. Suppose you have the following Scala classes .. Here Contact has an
embedded Address Map::

  @BeanInfo
  case class Contact(name: String,
                     @(JSONTypeHint @field)(value = classOf[Address])
                     addresses: Map[String, Address]) {

    override def toString = "name = " + name + " addresses = " +
      addresses.map(a => a._1 + ":" + a._2.toString).mkString(",")
  }

  @BeanInfo
  case class Address(street: String, city: String, zip: String) {
    override def toString = "address = " + street + "/" + city + "/" + zip
  }

With SJSON, I can do the following::

  val a1 = Address("Market Street", "San Francisco", "956871")
  val a2 = Address("Monroe Street", "Denver", "80231")
  val a3 = Address("North Street", "Atlanta", "987671")

  val c = Contact("Bob", Map("residence" -> a1, "office" -> a2, "club" -> a3))
  val co = serializer.out(c)

  val serializer = sjson.json.Serializer.SJSON

  // with class specified
  c should equal(serializer.in[Contact](co))

  // no class specified
  val a = serializer.in[AnyRef](co)

  // extract name
  val n = 'name ? str
  val n(_name) = a
  "Bob" should equal(_name)

  // extract addresses
  val addrs = 'addresses ? obj
  val addrs(_addresses) = a

  // extract residence from addresses
  val res = 'residence ? obj
  val res(_raddr) = _addresses

  // make an Address bean out of _raddr
  val address = JsBean.fromJSON(_raddr, Some(classOf[Address]))
  a1 should equal(address)

  object r { def ># [T](f: JsF[T]) = f(a.asInstanceOf[JsValue]) }

  // still better: chain 'em up
  "Market Street" should equal(
    (r ># { ('addresses ? obj) andThen ('residence ? obj) andThen ('street ? str) }))



Changing property names during serialization
--------------------------------------------

.. code-block:: scala

  @BeanInfo
  case class Book(id: Number,
             title: String, @(JSONProperty @getter)(value = "ISBN") isbn: String) {

    override def toString = "id = " + id + " title = " + title + " isbn = " + isbn
  }

When this will be serialized out, the property name will be changed::

  val b = new Book(100, "A Beautiful Mind", "012-456372")
  val jsBook = Js(JsBean.toJSON(b))
  val expected_book_map = Map(
    JsString("id") -> JsNumber(100),
    JsString("title") -> JsString("A Beautiful Mind"),
    JsString("ISBN") -> JsString("012-456372")
  )



Serialization with ignore properties
------------------------------------

When serializing objects, some of the properties can be ignored
declaratively. Consider the following class declaration::

  @BeanInfo
  case class Journal(id: BigDecimal,
                      title: String,
                      author: String,
                      @(JSONProperty @getter)(ignore = true) issn: String) {

  override def toString =
      "Journal: " + id + "/" + title + "/" + author +
        (issn match {
            case null => ""
            case _ => "/" + issn
          })
  }

The annotation ``@JSONProperty`` can be used to selectively ignore fields. When
I serialize a Journal object out and then back in, the content of issn field
will be null::

  val serializer = sjson.json.Serializer.SJSON

  it("should ignore issn field") {
      val j = Journal(100, "IEEE Computer", "Alex Payne", "012-456372")
      serializer.in[Journal](serializer.out(j)).asInstanceOf[Journal].issn should equal(null)
  }

Similarly, we can ignore properties of an object **only** if they are null and
not ignore otherwise. Just specify the annotation ``@JSONProperty`` as
``@JSONProperty {val ignoreIfNull = true}``.



Serialization with Type Hints for Generic Data Members
------------------------------------------------------

Consider the following Scala class::

  @BeanInfo
  case class Contact(name: String,
                     @(JSONTypeHint @field)(value = classOf[Address])
                     addresses: Map[String, Address]) {

    override def toString = "name = " + name + " addresses = " +
      addresses.map(a => a._1 + ":" + a._2.toString).mkString(",")
  }

Because of erasure, you need to add the type hint declaratively through the
annotation @JSONTypeHint that SJSON will pick up during serialization. Now we
can say::

  val serializer = sjson.json.Serializer.SJSON

  val c = Contact("Bob", Map("residence" -> a1, "office" -> a2, "club" -> a3))
  val co = serializer.out(c)

  it("should give an instance of Contact") {
    c should equal(serializer.in[Contact](co))
  }

With optional generic data members, we need to provide the hint to SJSON through
another annotation ``@OptionTypeHint``::

  @BeanInfo
  case class ContactWithOptionalAddr(name: String,
                                @(JSONTypeHint @field)(value = classOf[Address])
                                @(OptionTypeHint @field)(value = classOf[Map[_,_]])
                                addresses: Option[Map[String, Address]]) {

    override def toString = "name = " + name + " " +
      (addresses match {
        case None => ""
        case Some(ad) => " addresses = " + ad.map(a => a._1 + ":" + a._2.toString).mkString(",")
      })
  }

Serialization works ok with optional members annotated as above::

  val serializer = sjson.json.Serializer.SJSON

  describe("Bean with optional bean member serialization") {
    it("should serialize with Option defined") {
      val c = new ContactWithOptionalAddr("Debasish Ghosh",
        Some(Map("primary" -> new Address("10 Market Street", "San Francisco, CA", "94111"),
            "secondary" -> new Address("3300 Tamarac Drive", "Denver, CO", "98301"))))
      c should equal(
        serializer.in[ContactWithOptionalAddr](serializer.out(c)))
    }
  }

You can also specify a custom ClassLoader while using the SJSON serializer::

  object SJSON {
    val classLoader = //.. specify a custom classloader
  }

  import SJSON._
  serializer.out(..)

  //..


Fighting Type Erasure
---------------------

Because of type erasure, it's not always possible to infer the correct type
during de-serialization of objects. Consider the following example::

  abstract class A
  @BeanInfo case class B(param1: String) extends A
  @BeanInfo case class C(param1: String, param2: String) extends A

  @BeanInfo case class D(@(JSONTypeHint @field)(value = classOf[A])param1: List[A])

and the serialization code like the following::

  object TestSerialize{
   def main(args: Array[String]) {
     val test1 = new D(List(B("hello1")))
     val json = sjson.json.Serializer.SJSON.out(test1)
     val res = sjson.json.Serializer.SJSON.in[D](json)
     val res1: D = res.asInstanceOf[D]
     println(res1)
   }
  }

Note that the type hint on class D says A, but the actual instances that have
been put into the object before serialization is one of the derived classes
(B). During de-serialization, we have no idea of what can be inside D. The
serializer.in API will fail since all hint it has is for A, which is
abstract. In such cases, we need to handle the de-serialization by using
extractors over the underlying data structure that we use for storing JSON
objects, which is JsValue. Here's an example::

  val serializer = sjson.json.Serializer.SJSON

  val test1 = new D(List(B("hello1")))
  val json = serializer.out(test1)

  // create a JsValue from the string
  val js = Js(new String(json))

  // extract the named list argument
  val m = (Symbol("param1") ? list)
  val m(_m) = js

  // extract the string within
  val s = (Symbol("param1") ? str)

  // form a list of B's
  val result = _m.map{ e =>
    val s(_s) = e
    B(_s)
  }

  // form a D
  println("result = " + D(result))

The above snippet de-serializes correctly using extractors defined on
JsValue. For more details on JsValue and the extractors, please refer to
`dispatch-json <http://databinder.net/dispatch/About>`_ .

**NOTE**: Serialization with SJSON is based on bean introspection. In the
current version of Scala (2.8.0.Beta1 and 2.7.7) there is a bug where bean
introspection does not work properly for classes enclosed within another
class. Please ensure that the beans are the top level classes in your
application. They can be within objects though. A ticket has been filed in the
Scala Tracker and also fixed in the trunk. Here's the `ticket
<https://lampsvn.epfl.ch/trac/scala/ticket/3080>`_ .


Type class based Serialization
------------------------------

If type erasure hits you, reflection based serialization may not be the right
option. In fact the last section shows some of the scenarios which may not be
possible to handle using reflection based serialization of sjson. sjson also
supports type class based serialization where you can provide a custom protocol
for serialization as part of the type class implementation.

Here's a sample session at the REPL which shows the default serialization
protocol of sjson::

  scala> import sjson.json._
  import sjson.json._

  scala> import DefaultProtocol._
  import DefaultProtocol._

  scala> val str = "debasish"
  str: java.lang.String = debasish

  scala> import JsonSerialization._
  import JsonSerialization._

  scala> tojson(str)
  res0: dispatch.json.JsValue = "debasish"

  scala> fromjson[String](res0)
  res1: String = debasish

You can use serialization of generic data types using the default protocol as
well::

  scala> val list = List(10, 12, 14, 18)
  list: List[Int] = List(10, 12, 14, 18)

  scala> tojson(list)
  res2: dispatch.json.JsValue = [10, 12, 14, 18]

  scala> fromjson[List[Int]](res2)
  res3: List[Int] = List(10, 12, 14, 18)

You can also define your own custom protocol, which as to be an implementation
of the following type class::

  trait Writes[T] {
    def writes(o: T): JsValue
  }

  trait Reads[T] {
    def reads(json: JsValue): T
  }

  trait Format[T] extends Writes[T] with Reads[T]

Consider a case class and a custom protocol to serialize it into JSON. Here's
the type class implementation::

  object Protocols {
    case class Person(lastName: String, firstName: String, age: Int)
    object PersonProtocol extends DefaultProtocol {
      import dispatch.json._
      import JsonSerialization._

      implicit object PersonFormat extends Format[Person] {
        def reads(json: JsValue): Person = json match {
          case JsObject(m) =>
            Person(fromjson[String](m(JsString("lastName"))),
              fromjson[String](m(JsString("firstName"))), fromjson[Int](m(JsString("age"))))
          case _ => throw new RuntimeException("JsObject expected")
        }

        def writes(p: Person): JsValue =
          JsObject(List(
            (tojson("lastName").asInstanceOf[JsString], tojson(p.lastName)),
            (tojson("firstName").asInstanceOf[JsString], tojson(p.firstName)),
            (tojson("age").asInstanceOf[JsString], tojson(p.age)) ))
      }
    }
  }

and the serialization in action in the REPL::

  scala> import sjson.json._
  import sjson.json._

  scala> import Protocols._
  import Protocols._

  scala> import PersonProtocol._
  import PersonProtocol._

  scala> val p = Person("ghosh", "debasish", 20)
  p: sjson.json.Protocols.Person = Person(ghosh,debasish,20)

  scala> import JsonSerialization._
  import JsonSerialization._

  scala> tojson[Person](p)
  res1: dispatch.json.JsValue = {"lastName" : "ghosh", "firstName" : "debasish", "age" : 20}

  scala> fromjson[Person](res1)
  res2: sjson.json.Protocols.Person = Person(ghosh,debasish,20)

There are other nifty ways to implement case class serialization using
sjson. For more details, have a look at the `wiki
<http://wiki.github.com/debasishg/sjson/typeclass-based-json-serialization>`_
for sjson.


JSON: Java
==========

Use the ``akka.serialization.Serializable.JavaJSON`` base class with its
toJSONmethod. Akka’s Java JSON is based upon the Jackson library.

For your POJOs to be able to serialize themselves you have to extend the
JavaJSON base class.

.. code-block:: java

  import akka.serialization.Serializable.JavaJSON;
  import akka.serialization.SerializerFactory;

  class MyMessage extends JavaJSON {
    private String name = null;
    public MyMessage(String name) {
      this.name = name;
    }
    public String getName() {
      return name;
    }
  }

  MyMessage message = new MyMessage("json");
  String json = message.toJSON();
  SerializerFactory factory = new SerializerFactory();
  MyMessage messageCopy = factory.getJavaJSON().in(json);

Use the akka.serialization.SerializerFactory.getJavaJSON to do generic JSON
serialization, e.g. serialize object that does not extend JavaJSON using the
JSON serializer.

.. code-block:: java

  Foo foo = new Foo();
  SerializerFactory factory = new SerializerFactory();
  String json = factory.getJavaJSON().out(foo);
  Foo fooCopy = factory.getJavaJSON().in(json, Foo.class);



