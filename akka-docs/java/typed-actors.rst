Typed Actors (Java)
===================

.. sidebar:: Contents

   .. contents:: :local:
   
Module stability: **SOLID**

The Typed Actors are implemented through `Typed Actors <http://en.wikipedia.org/wiki/Active_object>`_. It uses AOP through `AspectWerkz <http://aspectwerkz.codehaus.org/>`_ to turn regular POJOs into asynchronous non-blocking Actors with semantics of the Actor Model. Each method dispatch is turned into a message that is put on a queue to be processed by the Typed Actor sequentially one by one.

If you are using the `Spring Framework <http://springsource.org>`_ then take a look at Akka's `Spring integration <spring-integration>`_.

** WARNING: ** Do not configure to use a WorkStealingDispatcher with your TypedActors, it just isn't safe with how TypedActors currently are implemented. This limitation will most likely be removed in the future.

Creating Typed Actors
---------------------

**IMPORTANT:** The Typed Actors class must have access modifier 'public' and can't be a non-static inner class.

Akka turns POJOs with interface and implementation into asynchronous (Typed) Actors. Akka is using `AspectWerkz’s Proxy <http://blogs.codehaus.org/people/jboner/archives/000914_awproxy_proxy_on_steriods.html>`_ implementation, which is the `most performant <http://docs.codehaus.org/display/AW/AOP+Benchmark>`_ proxy implementation there exists.

In order to create a Typed Actor you have to subclass the TypedActor base class.

Here is an example.

If you have a POJO with an interface implementation separation like this:

.. code-block:: java

  interface RegistrationService {
    void register(User user, Credentials cred);
    User getUserFor(String username);
  }

.. code-block:: java

  import akka.actor.TypedActor;
  
  public class RegistrationServiceImpl extends TypedActor implements RegistrationService {
    public void register(User user, Credentials cred) {
      ... // register user
    }

    public User getUserFor(String username) {
      ... // fetch user by username
     return user;
    }
  }

Then you can create an Typed Actor out of it by creating it through the 'TypedActor' factory like this:

.. code-block:: java

  RegistrationService service =
    (RegistrationService) TypedActor.newInstance(RegistrationService.class, RegistrationServiceImpl.class, 1000);
  // The last parameter defines the timeout for Future calls

Creating Typed Actors with non-default constructor
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

To create a typed actor that takes constructor arguments use a variant of 'newInstance' or 'newRemoteInstance' that takes an instance of a 'TypedActorFactory' in which you can create the TypedActor in any way you like. If you use this method then make sure that no one can get a reference to the actor instance. Touching actor state directly is bypassing the whole actor dispatching mechanism and create race conditions which can lead to corrupt data.

Here is an example:

.. code-block:: java

  Service service = TypedActor.newInstance(classOf[Service], new TypedActorFactory() {
    public TypedActor create() {
      return new ServiceWithConstructorArgsImpl("someString", 500L));
  });

Configuration factory class
^^^^^^^^^^^^^^^^^^^^^^^^^^^

Using a configuration object:

.. code-block:: java

  import static java.util.concurrent.TimeUnit.MILLISECONDS;
  import akka.actor.TypedActorConfiguration;
  import akka.util.FiniteDuration;

  TypedActorConfiguration config = new TypedActorConfiguration()
      .timeout(new FiniteDuration(3000, MILLISECONDS));

  RegistrationService service = (RegistrationService) TypedActor.newInstance(RegistrationService.class, config);

However, often you will not use these factory methods but declaratively define the Typed Actors as part of a supervisor hierarchy. More on that in the :ref:`fault-tolerance-java` section.

Sending messages
----------------

Messages are sent simply by invoking methods on the POJO, which is proxy to the "real" POJO now. The arguments to the method are bundled up atomically into an message and sent to the receiver (the actual POJO instance).

One-way message send
^^^^^^^^^^^^^^^^^^^^

Methods that return void are turned into ‘fire-and-forget’ semantics by asynchronously firing off the message and return immediately. In the example above it would be the 'register' method, so if this method is invoked then it returns immediately:

.. code-block:: java

  // method invocation returns immediately and method is invoke asynchronously using the Actor Model semantics
  service.register(user, creds);

Request-reply message send
^^^^^^^^^^^^^^^^^^^^^^^^^^

Methods that return something (e.g. non-void methods) are turned into ‘send-and-receive-eventually’ semantics by asynchronously firing off the message and wait on the reply using a Future.

.. code-block:: java

  // method invocation is asynchronously dispatched using the Actor Model semantics,
  // but it blocks waiting on a Future to be resolved in the background
  User user =  service.getUser(username);

Generally it is preferred to use fire-forget messages as much as possible since they will never block, e.g. consume a resource by waiting. But sometimes they are neat to use since they:

# Simulates standard Java method dispatch, which is more intuitive for most Java developers
# Are a neat to model request-reply
# Are useful when you need to do things in a defined order

The same holds for the 'request-reply-with-future' described below.

Request-reply-with-future message send
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Methods that return a 'akka.dispatch.Future<TYPE>' are turned into ‘send-and-receive-with-future’ semantics by asynchronously firing off the message and returns immediately with a Future. You need to use the 'future(...)' method in the TypedActor base class to resolve the Future that the client code is waiting on.

Here is an example:

.. code-block:: java

  public class MathTypedActorImpl extends TypedActor implements MathTypedActor {
   public Future<Integer> square(int value) {
      return future(value * value);
    }
  }

  MathTypedActor math = TypedActor.actorOf(MathTypedActor .class, MathTypedActorImpl.class);

  // This method will return immediately when called, caller should wait on the Future for the result
  Future<Integer> future = math.square(10);
  future.await();
  Integer result = future.get();

Stopping Typed Actors
---------------------

Once Typed Actors have been created with one of the TypedActor.newInstance methods they need to be stopped with TypedActor.stop to free resources allocated by the created Typed Actor (this is not needed when the Typed Actor is supervised).

.. code-block:: java

  // Create Typed Actor
  RegistrationService service = (RegistrationService) TypedActor.newInstance(RegistrationService.class);

  // ...

  // Free Typed Actor resources
  TypedActor.stop(service);

When the Typed Actor defines a shutdown callback method (:ref:`fault-tolerance-java`) it will be invoked on TypedActor.stop.

How to use the TypedActorContext for runtime information access
---------------------------------------------------------------

The 'akka.actor.TypedActorContext' class Holds 'runtime type information' (RTTI) for the Typed Actor. This context is a member field in the TypedActor base class and holds for example the current sender reference, the current sender future etc.

Here is an example how you can use it to in a 'void' (e.g. fire-forget) method to implement request-reply by using the sender reference:

.. code-block:: java

  class PingImpl implements Ping extends TypedActor {

    public void hit(int count) {
      Pong pong = (Pong) getContext().getSender();
      pong.hit(count++);
    }
  }

If the sender, sender future etc. is not available, then these methods will return 'null' so you should have a way of dealing with that scenario.

Messages and immutability
-------------------------

**IMPORTANT**: Messages can be any kind of object but have to be immutable (there is a workaround, see next section). Java or Scala can’t enforce immutability (yet) so this has to be by convention. Primitives like String, int, Long are always immutable. Apart from these you have to create your own immutable objects to send as messages. If you pass on a reference to an instance that is mutable then this instance can be modified concurrently by two different Typed Actors and the Actor model is broken leaving you with NO guarantees and most likely corrupt data.

Akka can help you in this regard. It allows you to turn on an option for serializing all messages, e.g. all parameters to the Typed Actor effectively making a deep clone/copy of the parameters. This will make sending mutable messages completely safe. This option is turned on in the :ref:`configuration` file like this:

.. code-block:: ruby

  akka {
    actor {
      serialize-messages = on  # does a deep clone of messages to ensure immutability
    }
  }

This will make a deep clone (using Java serialization) of all parameters.

