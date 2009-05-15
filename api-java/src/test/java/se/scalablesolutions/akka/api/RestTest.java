/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.api;

import com.sun.jersey.api.container.grizzly.GrizzlyWebContainerFactory;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.WebResource;
import com.sun.grizzly.http.SelectorThread;

import javax.ws.rs.core.UriBuilder;

import org.junit.*;
import static org.junit.Assert.*;

import java.util.Map;
import java.io.IOException;
import java.net.URI;

import junit.framework.TestSuite;

public class RestTest extends TestSuite {

  private static URI URI = UriBuilder.fromUri("http://localhost/").port(9998).build();
  private static SelectorThread selector = null;

  @BeforeClass
  public static void initialize() throws IOException {
    selector = startJersey();
  }

  @AfterClass
  public static void cleanup() throws IOException {
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

  private static SelectorThread startJersey() throws IOException {
    Map initParams = new java.util.HashMap<String, String>();
    initParams.put("com.sun.jersey.config.property.packages", "se.scalablesolutions.akka.api");
    return GrizzlyWebContainerFactory.create(URI, initParams);
  }
}
