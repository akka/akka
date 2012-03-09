
.. _spring-module:

####################
 Spring Integration
####################

Akkas integration with the `Spring Framework <http://www.springsource.org>`_ supplies the Spring way of using the Typed Actor Java API and for CamelService configuration for :ref:`camel-spring-applications`. It uses Spring's custom namespaces to create Typed Actors, supervisor hierarchies and a CamelService in a Spring environment.

To use the custom name space tags for Akka you have to add the XML schema definition to your spring configuration. It is available at `http://repo.akka.io/akka-1.0.xsd <http://repo.akka.io/akka.xsd>`_. The namespace for Akka is:

.. code-block:: xml

  xmlns:akka="http://repo.akka.io/schema/akka"

Example header for Akka Spring configuration:

.. code-block:: xml

  <?xml version="1.0" encoding="UTF-8"?>
  <beans xmlns="http://www.springframework.org/schema/beans"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xmlns:akka="http://repo.akka.io/schema/akka"
         xsi:schemaLocation="
         http://www.springframework.org/schema/beans
         http://www.springframework.org/schema/beans/spring-beans-3.0.xsd
         http://repo.akka.io/schema/akka
         http://repo.akka.io/akka-1.0.xsd">

-

Actors
------

Actors in Java are created by extending the 'UntypedActor' class and implementing the 'onReceive' method.

Example how to create Actors with the Spring framework:

.. code-block:: xml

  <akka:untyped-actor id="myActor"
                      implementation="com.biz.MyActor"
                      scope="singleton"
                      autostart="false"
                      depends-on="someBean"> <!-- or a comma-separated list of beans -->
          <property name="aProperty" value="somePropertyValue"/>
          <property name="aDependency" ref="someBeanOrActorDependency"/>
  </akka:untyped-actor>

Supported scopes are singleton and prototype. Dependencies and properties are set with Springs ``<property/>`` element.
A dependency can be either a ``<akka:untyped-actor/>`` or a regular ``<bean/>``.

Get the Actor from the Spring context:

.. code-block:: java

  ApplicationContext context = new ClassPathXmlApplicationContext("akka-spring-config.xml");
  ActorRef actorRef = (ActorRef) context.getBean("myActor");

Typed Actors
------------

Here are some examples how to create Typed Actors with the Spring framework:

Creating a Typed Actor:
^^^^^^^^^^^^^^^^^^^^^^^

.. code-block:: xml

  <beans>
    <akka:typed-actor id="myActor"
                      interface="com.biz.MyPOJO"
                      implementation="com.biz.MyPOJOImpl"
                      transactional="true"
                      timeout="1000"
                      scope="singleton"
                      depends-on="someBean"> <!-- or a comma-separated list of beans -->
         <property name="aProperty" value="somePropertyValue"/>
         <property name="aDependency" ref="someBeanOrActorDependency"/>
    </akka:typed-actor>
  </beans>

Supported scopes are singleton and prototype. Dependencies and properties are set with Springs ``<property/>`` element.
A dependency can be either a ``<akka:typed-actor/>`` or a regular ``<bean/>``.

Get the Typed Actor from the Spring context:

.. code-block:: java

  ApplicationContext context = new ClassPathXmlApplicationContext("akka-spring-config.xml");
  MyPojo myPojo = (MyPojo) context.getBean("myActor");

Remote Actors
-------------

For details on server managed and client managed remote actors see Remote Actor documentation.

Configuration for a client managed remote Actor
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

::

  <akka:untyped-actor id="remote-untyped-actor"
                      implementation="com.biz.MyActor"
                      timeout="2000">
      <akka:remote host="localhost" port="9992" managed-by="client"/>
  </akka:untyped-actor>

The default for 'managed-by' is "client", so in the above example it could be left out.

Configuration for a server managed remote Actor
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Server side
***********

::

  <akka:untyped-actor id="server-managed-remote-untyped-actor"
                      implementation="com.biz.MyActor">
      <akka:remote host="localhost" port="9990" managed-by="server"/>
  </akka:untyped-actor>

  <!-- register with custom service name -->
  <akka:untyped-actor id="server-managed-remote-untyped-actor-custom-id"
                      implementation="com.biz.MyActor">
      <akka:remote host="localhost" port="9990" service-name="my-service"/>
  </akka:untyped-actor>

If the server specified by 'host' and 'port' does not exist it will not be registered.

Client side
***********

::

  <!-- service-name could be custom name or class name -->
  <akka:actor-for id="client-1" host="localhost" port="9990" service-name="my-service"/>


Configuration for a client managed remote Typed Actor
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. code-block:: xml

  <akka:typed-actor id="remote-typed-actor"
                    interface="com.biz.MyPojo"
                    implementation="com.biz.MyPojoImpl"
                    timeout="2000">
      <akka:remote host="localhost" port="9999" />
  </akka:typed-actor>

Configuration for a server managed remote Typed Actor
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Sever side setup
****************

::

  <akka:typed-actor id="server-managed-remote-typed-actor-custom-id"
                    interface="com.biz.IMyPojo"
                    implementation="com.biz.MyPojo"
                    timeout="2000">
       <akka:remote host="localhost" port="9999" service-name="mypojo-service"/>
  </akka:typed-actor>

Client side setup
*****************

::

  <!-- always specify the interface for typed actor -->
  <akka:actor-for id="typed-client"
                  interface="com.biz.MyPojo"
                  host="localhost"
                  port="9999"
                  service-name="mypojo-service"/>

Dispatchers
-----------

Configuration for a Typed Actor or Untyped Actor with a custom dispatcher
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

If you don't want to use the default dispatcher you can define your own dispatcher in the spring configuration. For more information on dispatchers have a look at Dispatchers documentation.

.. code-block:: xml

  <akka:typed-actor id="remote-typed-actor"
                    interface="com.biz.MyPOJO"
                    implementation="com.biz.MyPOJOImpl"
                    timeout="2000">
    <akka:dispatcher id="my-dispatcher" type="executor-based-event-driven" name="myDispatcher">
      <akka:thread-pool queue="unbounded-linked-blocking-queue" capacity="100" />
    </akka:dispatcher>
  </akka:typed-actor>

  <akka:untyped-actor id="untyped-actor-with-thread-based-dispatcher"
                      implementation="com.biz.MyActor">
        <akka:dispatcher type="thread-based" name="threadBasedDispatcher"/>
  </akka:untyped-actor>

If you want to or have to share the dispatcher between Actors you can define a dispatcher and reference it from the Typed Actor configuration:

.. code-block:: xml

  <akka:dispatcher id="dispatcher-1"
                   type="executor-based-event-driven"
                   name="myDispatcher">
    <akka:thread-pool queue="bounded-array-blocking-queue"
                      capacity="100"
                      fairness="true"
                      core-pool-size="1"
                      max-pool-size="20"
                      keep-alive="3000"
                      rejection-policy="caller-runs-policy"/>
  </akka:dispatcher>

  <akka:typed-actor id="typed-actor-with-dispatcher-ref"
                    interface="com.biz.MyPOJO"
                    implementation="com.biz.MyPOJOImpl"
                    timeout="1000">
      <akka:dispatcher ref="dispatcher-1"/>
  </akka:typed-actor>

The following dispatcher types are available in spring configuration:

* executor-based-event-driven
* executor-based-event-driven-work-stealing
* thread-based

The following queue types are configurable for dispatchers using thread pools:

* bounded-linked-blocking-queue
* unbounded-linked-blocking-queue
* synchronous-queue
* bounded-array-blocking-queue

If you have set up your IDE to be XSD-aware you can easily write your configuration through auto-completion.

Stopping Typed Actors and Untyped Actors
----------------------------------------

Actors with scope singleton are stopped when the application context is closed. Actors with scope prototype must be stopped by the application.

Supervisor Hierarchies
----------------------

The supervisor configuration in Spring follows the declarative configuration for the Java API. Have a look at Akka's approach to fault tolerance.

Example spring supervisor configuration
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. code-block:: xml

  <beans>
    <akka:supervision id="my-supervisor">

      <akka:restart-strategy failover="AllForOne"
                             retries="3"
                             timerange="1000">
        <akka:trap-exits>
          <akka:trap-exit>java.io.IOException</akka:trap-exit>
        </akka:trap-exits>
      </akka:restart-strategy>

      <akka:typed-actors>
        <akka:typed-actor interface="com.biz.MyPOJO"
                          implementation="com.biz.MyPOJOImpl"
                          lifecycle="permanent"
                          timeout="1000"/>
        <akka:typed-actor interface="com.biz.AnotherPOJO"
                          implementation="com.biz.AnotherPOJOImpl"
                          lifecycle="temporary"
                          timeout="1000"/>
        <akka:typed-actor interface ="com.biz.FooBar"
                          implementation ="com.biz.FooBarImpl"
                          lifecycle="permanent"
                          transactional="true"
                          timeout="1000" />
      </akka:typed-actors>
    </akka:supervision>

    <akka:supervision id="supervision-untyped-actors">
      <akka:restart-strategy failover="AllForOne" retries="3" timerange="1000">
        <akka:trap-exits>
          <akka:trap-exit>java.io.IOException</akka:trap-exit>
          <akka:trap-exit>java.lang.NullPointerException</akka:trap-exit>
        </akka:trap-exits>
      </akka:restart-strategy>
      <akka:untyped-actors>
        <akka:untyped-actor implementation="com.biz.PingActor"
                          lifecycle="permanent"/>
        <akka:untyped-actor implementation="com.biz.PongActor"
                          lifecycle="permanent"/>
        </akka:untyped-actors>
    </akka:supervision>

  </beans>

Get the TypedActorConfigurator from the Spring context
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. code-block:: java

  TypedActorConfigurator myConfigurator = (TypedActorConfigurator) context.getBean("my-supervisor");
  MyPojo myPojo = (MyPOJO) myConfigurator.getInstance(MyPojo.class);

Property Placeholders
---------------------

The Akka configuration can be made available as property placeholders by using a custom property placeholder configurer for Configgy:

::

  <akka:property-placeholder location="akka.conf"/>

  <akka:untyped-actor id="actor-1" implementation="com.biz.MyActor" timeout="${akka.actor.timeout}">
    <akka:remote host="${akka.remote.server.hostname}" port="${akka.remote.server.port}"/>
  </akka:untyped-actor>

Camel configuration
-------------------

For details refer to the :ref:`camel-module` documentation:

* CamelService configuration for :ref:`camel-spring-applications`
* Access to Typed Actors :ref:`camel-typed-actors-using-spring`
