/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.api;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.WebResource;
import com.sun.grizzly.http.SelectorThread;
import com.sun.grizzly.http.servlet.ServletAdapter;
import com.sun.grizzly.tcp.Adapter;
import com.sun.grizzly.standalone.StaticStreamAlgorithm;

import javax.ws.rs.core.UriBuilder;
import javax.servlet.Servlet;

import org.junit.*;
import static org.junit.Assert.*;

import java.io.IOException;
import java.net.URI;

import junit.framework.TestSuite;
import se.scalablesolutions.akka.kernel.config.ActiveObjectGuiceConfiguratorForJava;
import se.scalablesolutions.akka.kernel.config.JavaConfig;

public class RestTest extends TestSuite {

  private static int PORT = 9998;
  private static URI URI = UriBuilder.fromUri("http://localhost/").port(PORT).build();
  private static SelectorThread selector = null;
  private static ActiveObjectGuiceConfiguratorForJava conf = new ActiveObjectGuiceConfiguratorForJava();

  @BeforeClass
  public static void initialize() throws IOException {
    conf.configureActiveObjects(
        new JavaConfig.RestartStrategy(new JavaConfig.AllForOne(), 3, 5000),
        new JavaConfig.Component[] {
          new JavaConfig.Component(
              JerseyFoo.class,
              new JavaConfig.LifeCycle(new JavaConfig.Permanent(), 1000), 10000000)
          }).inject().supervise();
    selector = startJersey();
  }

  @AfterClass
  public static void cleanup() throws IOException {
    conf.stop();
    selector.stopEndpoint();
    System.exit(0);
  }

  @Test
  public void simpleRequest() throws IOException, InstantiationException {
    selector.start();
    Client client = Client.create();
    WebResource webResource = client.resource(URI);
    String responseMsg = webResource.path("/foo").get(String.class);
    assertEquals("hello foo", responseMsg);
    selector.stopEndpoint();
  }

  private static SelectorThread startJersey() {
    try {
      ServletAdapter adapter = new ServletAdapter();
      Servlet servlet = se.scalablesolutions.akka.kernel.jersey.AkkaServlet.class.newInstance();
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
    selectorThread.listen();
    return selectorThread;
  }
}
