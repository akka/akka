Akka Servlet
============

=

Module stability: **STABLE**

Akka has a servlet; ‘se.scalablesolutions.akka.comet.AkkaServlet’ that can use to deploy your Akka-based application in an external Servlet container. All you need to do is to add the servlet to the ‘web.xml’, set ‘$AKKA_HOME’ to the root of the distribution (needs the ‘$AKKA_HOME/config/*’ files) and add the JARs in the ‘$AKKA_HOME/lib’ to your classpath (or put them in the ‘WEB-INF/lib’ directory in the WAR file).

Also, you need to add the Akka initialize/cleanup listener in web.xml

.. code-block:: xml

  <web-app>
  ...
    <listener>
      <listener-class>se.scalablesolutions.akka.servlet.Initializer</listener-class>
    </listener>
  ...
  </web-app>

And to support REST actors and/or comet actors, you need to add the following servlet declaration:

`<code format="xml">`_
<web-app>
...
  <servlet>
    <servlet-name>Akka</servlet-name>
    <!-- Both Comet + REST -->
    <servlet-class>se.scalablesolutions.akka.comet.AkkaServlet</servlet-class>
    <!-- Or if you don't want to use comet, but only REST -->
    <servlet-class>se.scalablesolutions.akka.rest.AkkaServlet</servlet-class>
  </servlet>
  <servlet-mapping>
   <url-pattern>*</url-pattern>
   <servlet-name>Akka</servlet-name>
  </servlet-mapping>
...
</web-app>

`<code>`_
