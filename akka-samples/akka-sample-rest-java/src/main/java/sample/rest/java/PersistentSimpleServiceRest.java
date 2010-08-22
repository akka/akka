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
 * curl http://localhost:9998/persistentjavacount
 * </pre>
 * Or browse to the URL from a web browser.
 */
@Path("/persistentjavacount")
public class PersistentSimpleServiceRest {
  private PersistentSimpleService service = (PersistentSimpleService) Boot.configurator.getInstance(PersistentSimpleService.class);

  @GET
  @Produces({"application/json"})
  public String count() {
    return service.count();
  }
}
