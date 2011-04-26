Web Framework Integrations
==========================

Play Framework
==============

Home page: `<http://www.playframework.org/>`_
Akka Play plugin: `<https://github.com/dwhitney/akka>`_
Read more here: `<http://www.playframework.org/modules/akka>`_

Lift Web Framework
==================

Home page: `<http://liftweb.net>`_

In order to use Akka with Lift you basically just have to do one thing, add the 'AkkaServlet' to your 'web.xml'.

web.xml
-------

.. code-block:: xml

  <web-app>
    <!-- Akka specific stuff -->
    <servlet>
      <servlet-name>AkkaServlet</servlet-name>
      <servlet-class>akka.comet.AkkaServlet</servlet-class>
    </servlet>
    <servlet-mapping>
      <servlet-name>AkkaServlet</servlet-name>
      <url-pattern>/*</url-pattern>
    </servlet-mapping>

    <!-- Lift specific stuff -->
    <filter>
      <filter-name>LiftFilter</filter-name>
      <display-name>Lift Filter</display-name>
      <description>The Filter that intercepts lift calls</description>
      <filter-class>net.liftweb.http.LiftFilter</filter-class>
    </filter>
    <filter-mapping>
      <filter-name>LiftFilter</filter-name>
      <url-pattern>/*</url-pattern>
    </filter-mapping>
  </web-app>

Boot class
----------

Lift bootstrap happens in the Lift 'Boot' class. Here is a good place to add Akka specific initialization. For example add declarative supervisor configuration to wire up the initial Actors.
Here is a full example taken from the Akka sample code, found here `<http://github.com/jboner/akka/tree/master/akka-samples/akka-sample-lift/>`_.

If a request is processed by Liftweb filter, Akka will not process the request. To disable processing of a request by the Lift filter :
* append partial function to LiftRules.liftRequest and return *false* value to disable processing of matching request
* use LiftRules.passNotFoundToChain to chain the request to the Akka filter

Example of Boot class source code :
`<code format="scala">`_
class Boot {
  def boot {
    // where to search snippet
    LiftRules.addToPackages("sample.lift")

    LiftRules.httpAuthProtectedResource.prepend {
      case (ParsePath("liftpage" :: Nil, _, _, _)) => Full(AuthRole("admin"))
    }

    LiftRules.authentication = HttpBasicAuthentication("lift") {
      case ("someuser", "1234", req) => {
        Log.info("You are now authenticated !")
        userRoles(AuthRole("admin"))
        true
      }
    }

    LiftRules.liftRequest.append {
      case Req("liftcount" :: _, _, _) => false
      case Req("persistentliftcount" :: _, _, _) => false
    }
    LiftRules.passNotFoundToChain = true

    // Akka supervisor configuration wiring up initial Actor services
    val supervisor = Supervisor(
      SupervisorConfig(
        RestartStrategy(OneForOne, 3, 100, List(classOf[Exception])),
        Supervise(
          actorOf[SimpleService],
          LifeCycle(Permanent)) ::
        Supervise(
          actorOf[PersistentSimpleService],
          LifeCycle(Permanent)) ::
        Nil))

    // Build SiteMap
    // val entries = Menu(Loc("Home", List("index"), "Home")) :: Nil
    // LiftRules.setSiteMap(SiteMap(entries:_*))
  }
}
`<code>`_
