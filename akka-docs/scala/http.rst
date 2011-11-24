.. _http-module:

HTTP
====

.. sidebar:: Contents

   .. contents:: :local:

Module stability: **SOLID**

When deploying in a servlet container:
--------------------------------------------

If you deploy Akka in a JEE container, don't forget to create an Akka initialization and cleanup hook:

.. code-block:: scala

  package com.my //<--- your own package
  import akka.util.AkkaLoader
  import akka.cluster.BootableRemoteActorService
  import akka.actor.BootableActorLoaderService
  import javax.servlet.{ServletContextListener, ServletContextEvent}

   /**
    * This class can be added to web.xml mappings as a listener to start and postStop Akka.
    *<web-app>
    * ...
    *  <listener>
    *    <listener-class>com.my.Initializer</listener-class>
    *  </listener>
    * ...
    *</web-app>
    */
  class Initializer extends ServletContextListener {
     lazy val loader = new AkkaLoader
     def contextDestroyed(e: ServletContextEvent): Unit = loader.shutdown
     def contextInitialized(e: ServletContextEvent): Unit =
       loader.boot(true, new BootableActorLoaderService with BootableRemoteActorService) //<--- Important
  //     loader.boot(true, new BootableActorLoaderService {}) // If you don't need akka-remote
   }

For Java users, it's currently only possible to use BootableActorLoaderService, but you'll need to use: akka.actor.DefaultBootableActorLoaderService


Then you just declare it in your web.xml:

.. code-block:: xml

  <web-app>
  ...
    <listener>
      <listener-class>your.package.Initializer</listener-class>
    </listener>
  ...
  </web-app>

Adapting your own Akka Initializer for the Servlet Container
------------------------------------------------------------

If you want to use akka-camel or any other modules that have their own "Bootable"'s you'll need to write your own Initializer, which is _ultra_ simple, see below for an example on how to include Akka-camel.

.. code-block:: scala

  package com.my //<--- your own package
  import akka.cluster.BootableRemoteActorService
  import akka.actor.BootableActorLoaderService
  import akka.camel.CamelService
  import javax.servlet.{ServletContextListener, ServletContextEvent}

   /**
    * This class can be added to web.xml mappings as a listener to start and postStop Akka.
    *<web-app>
    * ...
    *  <listener>
    *    <listener-class>com.my.Initializer</listener-class>
    *  </listener>
    * ...
    *</web-app>
    */
  class Initializer extends ServletContextListener {
     lazy val loader = new AkkaLoader
     def contextDestroyed(e: ServletContextEvent): Unit = loader.shutdown
     def contextInitialized(e: ServletContextEvent): Unit =
       loader.boot(true, new BootableActorLoaderService with BootableRemoteActorService with CamelService) //<--- Important
   }

Using Akka with the Pinky REST/MVC framework
--------------------------------------------

Pinky has a slick Akka integration. Read more `here <http://wiki.github.com/pk11/pinky/release-13>`_

jetty-run in SBT
----------------

If you want to use jetty-run in SBT you need to exclude the version of Jetty that is bundled in akka-http:

.. code-block:: scala

  override def ivyXML =
    <dependencies>
      <dependency org="com.typesafe.akka" name="akka-http" rev="AKKA_VERSION_GOES_HERE">
        <exclude module="jetty"/>
      </dependency>
    </dependencies>

Mist - Lightweight Asynchronous HTTP
------------------------------------

The *Mist* layer was developed to provide a direct connection between the servlet container and Akka actors with the goal of handling the incoming HTTP request as quickly as possible in an asynchronous manner. The motivation came from the simple desire to treat REST calls as completable futures, that is, effectively passing the request along an actor message chain to be resumed at the earliest possible time. The primary constraint was to not block any existing threads and secondarily, not create additional ones. Mist is very simple and works both with Jetty Continuations as well as with Servlet API 3.0 (tested using Jetty-8.0.0.M1). When the servlet handles a request, a message is created typed to represent the method (e.g. Get, Post, etc.), the request is suspended and the message is sent (fire-and-forget) to the *root endpoint* actor. That's it. There are no POJOs required to host the service endpoints and the request is treated as any other. The message can be resumed (completed) using a number of helper methods that set the proper HTTP response status code.

Complete runnable example can be found here: `<https://github.com/buka/akka-mist-sample>`_

Endpoints
^^^^^^^^^

Endpoints are actors that handle request messages. Minimally there must be an instance of the *RootEndpoint* and then at least one more (to implement your services).

Preparations
^^^^^^^^^^^^

In order to use Mist you have to register the MistServlet in *web.xml* or do the analogous for the embedded server if running in Akka Microkernel:

.. code-block:: xml

  <servlet>
    <servlet-name>akkaMistServlet</servlet-name>
    <servlet-class>akka.http.AkkaMistServlet</servlet-class>
    <init-param> <!-- Optional, if empty or omitted, it will use the default in the akka.conf -->
      <param-name>root-endpoint</param-name>
      <param-value>address_of_root_endpoint_actor</param-value>
    </init-param>
    <!-- <async-supported>true</async-supported> Enable this for Servlet 3.0 support -->
  </servlet>

  <servlet-mapping>
    <servlet-name>akkaMistServlet</servlet-name>
    <url-pattern>/*</url-pattern>
  </servlet-mapping>

Then you also have to add the following dependencies to your SBT build definition:

.. code-block:: scala

  val jettyWebapp = "org.eclipse.jetty" % "jetty-webapp" % "8.0.0.M2" % "test"
  val javaxServlet30 = "org.mortbay.jetty" % "servlet-api" % "3.0.20100224" % "provided"

Attention: You have to use SBT 0.7.5.RC0 or higher in order to be able to work with that Jetty version.

An Example
^^^^^^^^^^

Startup
*******

In this example, we'll use the built-in *RootEndpoint* class and implement our own service from that. Here the services are started in the boot loader and attached to the top level supervisor.

.. code-block:: scala

  class Boot {
    val factory = SupervisorFactory(
      SupervisorConfig(
        OneForOneStrategy(List(classOf[Exception]), 3, 100),
          //
          // in this particular case, just boot the built-in default root endpoint
          //
        Supervise(
          actorOf[RootEndpoint],
          Permanent) ::
        Supervise(
          actorOf[SimpleAkkaAsyncHttpService],
          Permanent)
        :: Nil))
    factory.newInstance.start
  }

**Defining the Endpoint**
The service is an actor that mixes in the *Endpoint* trait. Here the dispatcher is taken from the Akka configuration file which allows for custom tuning of these actors, though naturally, any dispatcher can be used.

URI Handling
************

Rather than use traditional annotations to pair HTTP request and class methods, Mist uses hook and provide functions. This offers a great deal of flexibility in how a given endpoint responds to a URI. A hook function is simply a filter, returning a Boolean to indicate whether or not the endpoint will handle the URI. This can be as simple as a straight match or as fancy as you need. If a hook for a given URI returns true, the matching provide function is called to obtain an actor to which the message can be delivered. Notice in the example below, in one case, the same actor is returned and in the other, a new actor is created and returned. Note that URI hooking is non-exclusive and a message can be delivered to multiple actors (see next example).

Plumbing
********

Hook and provider functions are attached to a parent endpoint, in this case the root, by sending it the **Endpoint.Attach** message.
Finally, bind the *handleHttpRequest* function of the *Endpoint* trait to the actor's *receive* function and we're done.

.. code-block:: scala

  class SimpleAkkaAsyncHttpService extends Actor with Endpoint {
    final val ServiceRoot = "/simple/"
    final val ProvideSameActor = ServiceRoot + "same"
    final val ProvideNewActor = ServiceRoot + "new"

      //
      // use the configurable dispatcher
      //
    self.dispatcher = Endpoint.Dispatcher

      //
      // there are different ways of doing this - in this case, we'll use a single hook function
      //  and discriminate in the provider; alternatively we can pair hooks & providers
      //
    def hook(uri: String): Boolean = ((uri == ProvideSameActor) || (uri == ProvideNewActor))
    def provide(uri: String): ActorRef = {
      if (uri == ProvideSameActor) same
      else actorOf[BoringActor]
    }

      //
      // this is where you want attach your endpoint hooks
      //
    override def preStart() = {
        //
        // we expect there to be one root and that it's already been started up
        // obviously there are plenty of other ways to obtaining this actor
        //  the point is that we need to attach something (for starters anyway)
        //  to the root
        //
        val root = Actor.registry.actorsFor(classOf[RootEndpoint]).head
        root ! Endpoint.Attach(hook, provide)
      }

      //
      // since this actor isn't doing anything else (i.e. not handling other messages)
      //  just assign the receive func like so...
      // otherwise you could do something like:
      //  def myrecv = {...}
      //  def receive = myrecv orElse _recv
      //
    def receive = handleHttpRequest

    //
    // this will be our "same" actor provided with ProvideSameActor endpoint is hit
    //
    lazy val same = actorOf[BoringActor]
  }

Handling requests
*****************

Messages are handled just as any other that are received by your actor. The servlet requests and response are not hidden and can be accessed directly as shown below.

.. code-block:: scala

  /**
   * Define a service handler to respond to some HTTP requests
   */
  class BoringActor extends Actor {
    import java.util.Date
    import javax.ws.rs.core.MediaType

    var gets = 0
    var posts = 0
    var lastget: Option[Date] = None
    var lastpost: Option[Date] = None

    def receive = {
      // handle a get request
      case get: Get =>
        // the content type of the response.
        // similar to @Produces annotation
        get.response.setContentType(MediaType.TEXT_HTML)

        //
        // "work"
        //
        gets += 1
        lastget = Some(new Date)

        //
        // respond
        //
        val res = "<p>Gets: "+gets+" Posts: "+posts+"</p><p>Last Get: "+lastget.getOrElse("Never").toString+" Last Post: "+lastpost.getOrElse("Never").toString+"</p>"
        get.OK(res)

      // handle a post request
      case post:Post =>
        // the expected content type of the request
        // similar to @Consumes
        if (post.request.getContentType startsWith MediaType.APPLICATION_FORM_URLENCODED) {
          // the content type of the response.
          // similar to @Produces annotation
          post.response.setContentType(MediaType.TEXT_HTML)

          // "work"
          posts += 1
          lastpost = Some(new Date)

          // respond
          val res = "<p>Gets: "+gets+" Posts: "+posts+"</p><p>Last Get: "+lastget.getOrElse("Never").toString+" Last Post: "+lastpost.getOrElse("Never").toString+"</p>"
          post.OK(res)
        } else {
          post.UnsupportedMediaType("Content-Type request header missing or incorrect (was '" + post.request.getContentType + "' should be '" + MediaType.APPLICATION_FORM_URLENCODED + "')")
        }
      }

      case other: RequestMethod =>
        other.NotAllowed("Invalid method for this endpoint")
    }
  }

**Timeouts**
Messages will expire according to the default timeout (specified in akka.conf). Individual messages can also be updated using the *timeout* method. One thing that may seem unexpected is that when an expired request returns to the caller, it will have a status code of OK (200). Mist will add an HTTP header to such responses to help clients, if applicable. By default, the header will be named "Async-Timeout" with a value of "expired" - both of which are configurable.

Another Example - multiplexing handlers
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

As noted above, hook functions are non-exclusive. This means multiple actors can handle the same request if desired. In this next example, the hook functions are identical (yes, the same one could have been reused) and new instances of both A and B actors will be created to handle the Post. A third mediator is inserted to coordinate the results of these actions and respond to the caller.

.. code-block:: scala

  package sample.mist

  import akka.actor._
  import akka.actor.Actor._
  import akka.http._

  import javax.servlet.http.HttpServletResponse

  class InterestingService extends Actor with Endpoint {
    final val ServiceRoot = "/interesting/"
    final val Multi = ServiceRoot + "multi/"
    // use the configurable dispatcher
    self.dispatcher = Endpoint.Dispatcher

    //
    // The "multi" endpoint shows forking off multiple actions per request
    // It is triggered by POSTing to http://localhost:9998/interesting/multi/{foo}
    //  Try with/without a header named "Test-Token"
    //  Try with/without a form parameter named "Data"
    def hookMultiActionA(uri: String): Boolean = uri startsWith Multi
    def provideMultiActionA(uri: String): ActorRef = actorOf(new ActionAActor(complete))

    def hookMultiActionB(uri: String): Boolean = uri startsWith Multi
    def provideMultiActionB(uri: String): ActorRef = actorOf(new ActionBActor(complete))

      //
      // this is where you want attach your endpoint hooks
      //
    override def preStart() = {
      //
      // we expect there to be one root and that it's already been started up
      // obviously there are plenty of other ways to obtaining this actor
      //  the point is that we need to attach something (for starters anyway)
      //  to the root
      //
      val root = Actor.registry.actorsFor(classOf[RootEndpoint]).head
      root ! Endpoint.Attach(hookMultiActionA, provideMultiActionA)
      root ! Endpoint.Attach(hookMultiActionB, provideMultiActionB)
    }

    //
    // since this actor isn't doing anything else (i.e. not handling other messages)
    //  just assign the receive func like so...
    // otherwise you could do something like:
    //  def myrecv = {...}
    //  def receive = myrecv orElse handleHttpRequest
    //
    def receive = handleHttpRequest

    //
    // this guy completes requests after other actions have occurred
    //
    lazy val complete = actorOf[ActionCompleteActor]
  }

  class ActionAActor(complete:ActorRef) extends Actor {
    import javax.ws.rs.core.MediaType

    def receive = {
      // handle a post request
      case post: Post =>
        // the expected content type of the request
        // similar to @Consumes
        if (post.request.getContentType startsWith MediaType.APPLICATION_FORM_URLENCODED) {
          // the content type of the response.
          // similar to @Produces annotation
          post.response.setContentType(MediaType.TEXT_HTML)

          // get the resource name
          val name = post.request.getRequestURI.substring("/interesting/multi/".length)
          if (name.length % 2 == 0) post.response.getWriter.write("<p>Action A verified request.</p>")
          else post.response.getWriter.write("<p>Action A could not verify request.</p>")

          // notify the next actor to coordinate the response
          complete ! post
        } else post.UnsupportedMediaType("Content-Type request header missing or incorrect (was '" + post.request.getContentType + "' should be '" + MediaType.APPLICATION_FORM_URLENCODED + "')")
      }
    }
  }

  class ActionBActor(complete:ActorRef) extends Actor {
    import javax.ws.rs.core.MediaType

    def receive = {
      // handle a post request
      case post: Post =>
        // the expected content type of the request
        // similar to @Consumes
        if (post.request.getContentType startsWith MediaType.APPLICATION_FORM_URLENCODED) {
          // pull some headers and form params
          def default(any: Any): String = ""

          val token = post.getHeaderOrElse("Test-Token", default)
          val data = post.getParameterOrElse("Data", default)

          val (resp, status) = (token, data) match {
            case ("", _) => ("No token provided", HttpServletResponse.SC_FORBIDDEN)
            case (_, "") => ("No data", HttpServletResponse.SC_ACCEPTED)
            case _ => ("Data accepted", HttpServletResponse.SC_OK)
          }

          // update the response body
          post.response.getWriter.write(resp)

          // notify the next actor to coordinate the response
          complete ! (post, status)
        } else post.UnsupportedMediaType("Content-Type request header missing or incorrect (was '" + post.request.getContentType + "' should be '" + MediaType.APPLICATION_FORM_URLENCODED + "')")
      }

      case other: RequestMethod =>
        other.NotAllowed("Invalid method for this endpoint")
    }
  }

  class ActionCompleteActor extends Actor {
    import collection.mutable.HashMap

    val requests = HashMap.empty[Int, Int]

    def receive = {
      case req: RequestMethod =>
        if (requests contains req.hashCode) complete(req)
        else requests += (req.hashCode -> 0)

      case t: Tuple2[RequestMethod, Int] =>
        if (requests contains t._1.hashCode) complete(t._1)
        else requests += (t._1.hashCode -> t._2)
    }

    def complete(req: RequestMethod) = requests.remove(req.hashCode) match {
        case Some(HttpServletResponse.SC_FORBIDDEN) => req.Forbidden("")
        case Some(HttpServletResponse.SC_ACCEPTED) => req.Accepted("")
        case Some(_) => req.OK("")
        case _ => {}
    }
  }

Examples
^^^^^^^^

Using the Akka Mist module with OAuth
*************************************

`<https://gist.github.com/759501>`_

Using the Akka Mist module with the Facebook Graph API and WebGL
****************************************************************

Example project using Akka Mist with the Facebook Graph API and WebGL
`<https://github.com/buka/fbgl1>`_

Using Akka Mist on Amazon ElasticBeanstalk
******************************************

`<https://groups.google.com/group/akka-user/browse_thread/thread/ab7b5432f2fc4153>`_
