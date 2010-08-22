/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.rest.java;

import javax.ws.rs.Path;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;

/**
 * Try service out by invoking (multiple times):
 * <pre>
 * curl http://localhost:9998/javacount
 * </pre>
 * Or browse to the URL from a web browser.
 */
@Path("/javacount")
public class SimpleServiceRest {
  private SimpleService service = (SimpleService) Boot.configurator.getInstance(SimpleService.class);

  @GET
  @Produces({"application/json"})
  public String count() {
    return service.count();
  }
}
