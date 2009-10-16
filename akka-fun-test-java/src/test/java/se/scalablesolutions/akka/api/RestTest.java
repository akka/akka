/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.api;

import com.sun.grizzly.http.SelectorThread;
import com.sun.grizzly.http.servlet.ServletAdapter;
import com.sun.grizzly.tcp.Adapter;
import com.sun.grizzly.standalone.StaticStreamAlgorithm;

import javax.ws.rs.core.UriBuilder;
import javax.servlet.Servlet;

import junit.framework.TestCase;
import org.junit.*;

import java.io.IOException;
import java.net.URI;

import se.scalablesolutions.akka.config.*;
import static se.scalablesolutions.akka.config.JavaConfig.*;


public class RestTest extends TestCase {

  private static int PORT = 9998;
  private static URI URI = UriBuilder.fromUri("http://localhost/").port(PORT).build();
  private static SelectorThread selector = null;
  private static ActiveObjectConfigurator conf = new ActiveObjectConfigurator();

  @BeforeClass
  protected void setUp() {
    conf.configure(
        new RestartStrategy(new AllForOne(), 3, 5000),
        new Component[] {
          new Component(
              JerseyFoo.class,
              new LifeCycle(new Permanent(), 1000),
              10000000)
          }).inject().supervise();
    selector = startJersey();
  }

  public void testSimpleRequest() {
    assertTrue(true);
  }

/*

  @Test
  public void testSimpleRequest() throws IOException, InstantiationException {
    selector.listen();
    Client client = Client.create();
    WebResource webResource = client.resource(URI);
    String responseMsg = webResource.path("/foo").get(String.class);
    assertEquals("hello foo", responseMsg);
    selector.stopEndpoint();
  }
*/
  private static SelectorThread startJersey() {
    try {
      Servlet servlet = new se.scalablesolutions.akka.rest.AkkaServlet();
      ServletAdapter adapter = new ServletAdapter();
      adapter.setServletInstance(servlet);
      adapter.setContextPath(URI.getPath());
      return createGrizzlySelector(adapter, URI, PORT);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static SelectorThread createGrizzlySelector(Adapter adapter, URI uri, int port) throws IOException, InstantiationException {
    final String scheme = uri.getScheme();
    if (!scheme.equalsIgnoreCase("http"))
      throw new IllegalArgumentException("The URI scheme, of the URI " + uri + ", must be equal (ignoring case) to 'http'");
    final SelectorThread selectorThread = new SelectorThread();
    selectorThread.setAlgorithmClassName(StaticStreamAlgorithm.class.getName());
    selectorThread.setPort(port);
    selectorThread.setAdapter(adapter);
    return selectorThread;
  }
}

