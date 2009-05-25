package se.scalablesolutions.akka.api;

import javax.ws.rs.Path;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;

@Path("/foo")
public class JerseyFoo {
  @GET
  @Produces({"application/json"})
  public String foo() {
    return "hello foo";
  }
}