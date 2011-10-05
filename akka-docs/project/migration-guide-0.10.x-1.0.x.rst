Migration Guide 0.10.x to 1.0.x
====================================

Akka & Akka Modules separated into two different repositories and distributions
-------------------------------------------------------------------------------

Akka is split up into two different parts:
* Akka - Reflects all the sections under 'Scala API' and 'Java API' in the navigation bar.
* Akka Modules - Reflects all the sections under 'Add-on modules' in the navigation bar.

Download the release you need (Akka core or Akka Modules) from `<http://akka.io/downloads>`_ and unzip it.

----

Changed Akka URI
----------------

http://akkasource.org changed to http://akka.io

Reflects XSDs, Maven repositories, ScalaDoc etc.

----

Removed 'se.scalablesolutions' prefix
-------------------------------------

We have removed some boilerplate by shortening the Akka package from
**se.scalablesolutions.akka** to just **akka** so just do a search-replace in your project,
we apologize for the inconvenience, but we did it for our users.

----

Akka-core is no more
--------------------

Akka-core has been split into akka-actor, akka-stm, akka-typed-actor & akka-remote this means that you need to update any deps you have on akka-core.

----

Config
------

Turning on/off modules
^^^^^^^^^^^^^^^^^^^^^^

All the 'service = on' elements for turning modules on and off have been replaced by a top-level list of the enabled services.

Services available for turning on/off are:
* "remote"
* "http"
* "camel"

**All** services are **OFF** by default. Enable the ones you are using.

.. code-block:: ruby

  akka {
     enabled-modules = [] # Comma separated list of the enabled modules. Options: ["remote", "camel", "http"]
  }

Renames
^^^^^^^

* 'rest' section - has been renamed to 'http' to align with the module name 'akka-http'.
* 'storage' section - has been renamed to 'persistence' to align with the module name 'akka-persistence'.

.. code-block:: ruby

  akka {
    http {
       ..
    }

    persistence {
      ..
    }
  }

----

Important changes from RC2-RC3
------------------------------

**akka.config.SupervisionSupervise**

**Scala**

.. code-block:: scala

  def apply(actorRef: ActorRef, lifeCycle: LifeCycle, registerAsRemoteService: Boolean = false)

- boolean instead of remoteAddress, registers that actor with it's id as service name on the local server

**akka.actor.Actors now is the API for Java to interact with Actors, Remoting and ActorRegistry:**

**Java**

.. code-block:: java

  import static akka.actor.Actors.*; // <-- The important part

  actorOf();
  remote().actorOf();
  registry().actorsFor("foo");

***akka.actor.Actor now is the API for Scala to interact with Actors, Remoting and ActorRegistry:***

**Scala**

.. code-block:: scala

  import akka.actor.Actor._ // <-- The important part

  actorOf().method
  remote.actorOf()
  registry.actorsFor("foo")

**object UntypedActor has been deleted and replaced with akka.actor.Actors/akka.actor.Actor (Java/Scala)**

- UntypedActor.actorOf -> Actors.actorOf (Java) or Actor.actorOf (Scala)

**object ActorRegistry has been deleted and replaced with akka.actor.Actors.registry()/akka.actor.Actor.registry (Java/Scala)**

- ActorRegistry. -> Actors.registry(). (Java) or Actor.registry. (Scala)

**object RemoteClient has been deleted and replaced with akka.actor.Actors.remote()/akka.actor.Actor.remote (Java/Scala)**

- RemoteClient -> Actors.remote() (Java) or Actor.remote (Scala)

**object RemoteServer has been deleted and replaced with akka.actor.Actors.remote()/akka.actor.Actor.remote (Java/Scala)**

- RemoteServer - deleted -> Actors.remote() (Java) or Actor.remote (Scala)

**classes RemoteActor, RemoteUntypedActor and RemoteUntypedConsumerActors has been deleted and replaced with akka.actor.Actors.remote().actorOf(x, host port)/akka.actor.Actor.remote.actorOf(x, host, port)**

- RemoteActor, RemoteUntypedActor - deleted, use: remote().actorOf(YourActor.class, host, port) (Java) or remote.actorOf[YourActor](host, port)

**Remoted spring-actors now default to spring id as service-name, use "service-name" attribute on "remote"-tag to override**

**Listeners for RemoteServer and RemoteClient** are now registered on Actors.remote().addListener (Java) or Actor.remote.addListener (Scala), this means that all listeners get all remote events, both remote server evens and remote client events, **so adjust your code accordingly.**

**ActorRef.startLinkRemote has been removed since one specified on creation wether the actor is client-managed or not.**

Important change from RC3 to RC4
--------------------------------

The Akka-Spring namespace has changed from akkasource.org and scalablesolutions.se to http://akka.io/schema and http://akka.io/akka-<version>.xsd

Module akka-actor
-----------------

The Actor.init callback has been renamed to "preStart" to align with the general callback naming and is more clear about when it's called.

The Actor.shutdown callback has been renamed to "postStop" to align with the general callback naming and is more clear about when it's called.

The Actor.initTransactionalState callback has been removed, logic should be moved to preStart and be wrapped in an atomic block

**se.scalablesolutions.akka.config.ScalaConfig** and **se.scalablesolutions.akka.config.JavaConfig** have been merged into **akka.config.Supervision**

**RemoteAddress** has moved from **se.scalablesolutions.akka.config.ScalaConfig** to **akka.config**

The ActorRef.lifeCycle has changed signature from Option[LifeCycle] to LifeCycle, this means you need to change code that looks like this:
**self.lifeCycle = Some(LifeCycle(Permanent))** to **self.lifeCycle = Permanent**

The equivalent to **self.lifeCycle = None** is **self.lifeCycle = UndefinedLifeCycle**
**LifeCycle(Permanent)** becomes **Permanent**
**new LifeCycle(permanent())** becomes **permanent()** (need to do: import static se.scalablesolutions.akka.config.Supervision.*; first)

**JavaConfig.Component** and **ScalaConfig.Component** have been consolidated and renamed as **Supervision.SuperviseTypedActor**

**self.trapExit** has been moved into the FaultHandlingStrategy, and **ActorRef.faultHandler** has switched type from Option[FaultHandlingStrategy]
to FaultHandlingStrategy:

**Scala**

.. code-block:: scala

  import akka.config.Supervision._

  self.faultHandler = OneForOneStrategy(List(classOf[Exception]), 3, 5000)

**Java**

.. code-block:: java

  import static akka.Supervision.*;

  getContext().setFaultHandler(new OneForOneStrategy(new Class[] { Exception.class },50,1000))

**RestartStrategy, AllForOne, OneForOne** have been replaced with **AllForOneStrategy** and **OneForOneStrategy** in **se.scalablesolutions.akka.config.Supervision**

**Scala**

.. code-block:: scala

  import akka.config.Supervision._
  SupervisorConfig(
    OneForOneStrategy(List(classOf[Exception]), 3, 5000),
      Supervise(pingpong1,Permanent) :: Nil
  )

**Java**

.. code-block:: java

  import static akka.Supervision.*;

  new SupervisorConfig(
    new OneForOneStrategy(new Class[] { Exception.class },50,1000),
    new Server[] { new Supervise(pingpong1, permanent()) }
  )

***We have removed the following factory methods:***

**Actor.actor { case foo => bar }**
**Actor.transactor { case foo => bar }**
**Actor.temporaryActor { case foo => bar }**
**Actor.init {} receive { case foo => bar }**

They started the actor and no config was possible, it was inconsistent and irreparable.

replace with your own factories, or:

**Scala**

.. code-block:: scala

  actorOf( new Actor { def receive = { case foo => bar } } ).start
  actorOf( new Actor { self.lifeCycle = Temporary; def receive = { case foo => bar } } ).start

ReceiveTimeout is now rescheduled after every message, before there was only an initial timeout.
To stop rescheduling of ReceiveTimeout, set **receiveTimeout = None**

HotSwap
-------

HotSwap does no longer use behavior stacking by default, but that is an option to both "become" and HotSwap.

HotSwap now takes for Scala a Function from ActorRef to a Receive, the ActorRef passed in is the reference to self, so you can do self.reply() etc.

----

Module akka-stm
---------------

The STM stuff is now in its own module. This means that there is no support for transactions or transactors in akka-actor.

Local and global
^^^^^^^^^^^^^^^^

The **local/global** distinction has been dropped. This means that if the following general import was being used:

**Scala**

.. code-block:: scala

  import akka.stm.local._

this is now just:

**Scala**

.. code-block:: scala

  import akka.stm._

Coordinated is the new global
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

There is a new explicit mechanism for coordinated transactions. See the `Scala Transactors <transactors-scala>`_ and `Java Transactors <transactors-java>`_ documentation for more information. Coordinated transactions and transactors are found in the ``akka.transactor`` package now. The usage of transactors has changed.

Agents
^^^^^^

Agent is now in the akka-stm module and has moved to the ``akka.agent`` package. The implementation has been reworked and is now closer to Clojure agents. There is not much difference in general usage, the main changes involve interaction with the STM.

While updates to Agents are asynchronous, the state of an Agent is always immediately available for reading by any thread. Agents are integrated with the STM - any dispatches made in a transaction are held until that transaction commits, and are discarded if it is retried or aborted. There is a new ``sendOff`` method for long-running or blocking update functions.

----

Module akka-camel
-----------------

Access to the CamelService managed by CamelServiceManager has changed:

* Method service renamed to mandatoryService (Scala)
* Method service now returns Option[CamelService] (Scala)
* Introduced method getMandatoryService() (Java)
* Introduced method getService() (Java)

**Scala**

.. code-block:: scala

  import se.scalablesolutions.akka.camel.CamelServiceManager._
  import se.scalablesolutions.akka.camel.CamelService

  val o: Option[CamelService] = service
  val s: CamelService = mandatoryService

**Java**

.. code-block:: java

  import se.scalablesolutions.akka.camel.CamelService;
  import se.scalablesolutions.akka.japi.Option;
  import static se.scalablesolutions.akka.camel.CamelServiceManager.*;

  Option<CamelService> o = getService();
  CamelService s = getMandatoryService();

Access to the CamelContext and ProducerTemplate managed by CamelContextManager has changed:

* Method context renamed to mandatoryContext (Scala)
* Method template renamed to mandatoryTemplate (Scala)
* Method service now returns Option[CamelContext] (Scala)
* Method template now returns Option[ProducerTemplate] (Scala)
* Introduced method getMandatoryContext() (Java)
* Introduced method getContext() (Java)
* Introduced method getMandatoryTemplate() (Java)
* Introduced method getTemplate() (Java)

**Scala**

.. code-block:: scala

  import org.apache.camel.CamelContext
  import org.apache.camel.ProducerTemplate

  import se.scalablesolutions.akka.camel.CamelContextManager._

  val co: Option[CamelContext] = context
  val to: Option[ProducerTemplate] = template

  val c: CamelContext = mandatoryContext
  val t: ProducerTemplate = mandatoryTemplate

**Java**

.. code-block:: java

  import org.apache.camel.CamelContext;
  import org.apache.camel.ProducerTemplate;

  import se.scalablesolutions.akka.japi.Option;
  import static se.scalablesolutions.akka.camel.CamelContextManager.*;

  Option<CamelContext> co = getContext();
  Option<ProducerTemplate> to = getTemplate();

  CamelContext c = getMandatoryContext();
  ProducerTemplate t = getMandatoryTemplate();

The following methods have been renamed on class se.scalablesolutions.akka.camel.Message:

* bodyAs(Class) has been renamed to getBodyAs(Class)
* headerAs(String, Class) has been renamed to getHeaderAs(String, Class)

The API for waiting for consumer endpoint activation and de-activation has been changed

* CamelService.expectEndpointActivationCount has been removed and replaced by CamelService.awaitEndpointActivation
* CamelService.expectEndpointDeactivationCount has been removed and replaced by CamelService.awaitEndpointDeactivation

**Scala**

.. code-block:: scala

  import se.scalablesolutions.akka.actor.Actor
  import se.scalablesolutions.akka.camel.CamelServiceManager._

  val s = startCamelService
  val actor = Actor.actorOf[SampleConsumer]

  // wait for 1 consumer being activated
  s.awaitEndpointActivation(1) {
    actor.start
  }

  // wait for 1 consumer being de-activated
  s.awaitEndpointDeactivation(1) {
    actor.stop
  }

  s.stop

**Java**

.. code-block:: java

  import java.util.concurrent.TimeUnit;
  import se.scalablesolutions.akka.actor.ActorRef;
  import se.scalablesolutions.akka.actor.Actors;
  import se.scalablesolutions.akka.camel.CamelService;
  import se.scalablesolutions.akka.japi.SideEffect;
  import static se.scalablesolutions.akka.camel.CamelServiceManager.*;

  CamelService s = startCamelService();
  final ActorRef actor = Actors.actorOf(SampleUntypedConsumer.class);

  // wait for 1 consumer being activated
  s.awaitEndpointActivation(1, new SideEffect() {
    public void apply() {
        actor.start();
    }
  });

  // wait for 1 consumer being de-activated
  s.awaitEndpointDeactivation(1, new SideEffect() {
    public void apply() {
        actor.stop();
    }
  });

  s.stop();

Module Akka-Http
----------------

Atmosphere support has been removed. If you were using akka.comet.AkkaServlet for Jersey support only,
you can switch that to: akka.http.AkkaRestServlet and it should work just like before.

Atmosphere has been removed because we have a new async http support in the form of Akka Mist, a very thin bridge
between Servlet3.0/JettyContinuations and Actors, enabling Http-as-messages, read more about it here:
http://doc.akka.io/http#Mist%20-%20Lightweight%20Asynchronous%20HTTP

If you really need Atmosphere support, you can add it yourself by following the steps listed at the start of:
http://doc.akka.io/comet

Module akka-spring
------------------

The Akka XML schema URI has changed to http://akka.io/schema/akka

.. code-block:: xml

  <beans xmlns="http://www.springframework.org/schema/beans"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xmlns:akka="http://akka.io/schema/akka"
         xsi:schemaLocation="
  http://www.springframework.org/schema/beans
  http://www.springframework.org/schema/beans/spring-beans-3.0.xsd
  http://akka.io/schema/akka
  http://akka.io/akka-1.0.xsd">

  <!-- ... -->

  </beans>
