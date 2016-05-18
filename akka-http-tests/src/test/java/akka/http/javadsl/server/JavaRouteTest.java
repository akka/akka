/*
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http.javadsl.server;

import static akka.http.javadsl.server.PathMatchers.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import akka.http.javadsl.model.*;
import akka.http.javadsl.model.headers.Accept;
import akka.http.javadsl.model.headers.RawHeader;
import org.junit.Test;

import akka.http.javadsl.testkit.JUnitRouteTest;
//FIXME discuss how to provide a javadsl.CustomHeader where render() is either pre-implemented or trivial to write in Java
import akka.http.scaladsl.model.headers.CustomHeader;
import akka.japi.pf.PFBuilder;
import akka.util.ByteString;

public class JavaRouteTest extends JUnitRouteTest {
  private final Route route = getRoute();
  private static final Unmarshaller<String, BigDecimal> BIG_DECIMAL_PARAM =
    Unmarshaller.sync(BigDecimal::new);

  private final Unmarshaller<HttpEntity, BigDecimal> BIG_DECIMAL_BODY =
    Unmarshaller.entityToString().thenApply(BigDecimal::new);

  private final Unmarshaller<HttpEntity, UUID> UUID_FROM_JSON_BODY =
    Unmarshaller.forMediaType(MediaTypes.APPLICATION_JSON,
      Unmarshaller.entityToString())
      .thenApply(s -> {
        // just a fake JSON parser, assuming it's {"id":"..."}
        // A real implementation could easily invoke Jackson here instead.
        Pattern regex = Pattern.compile("\"id\":\"(.+)\"");
        Matcher matcher = regex.matcher(s);
        matcher.find();
        return UUID.fromString(matcher.group(1));
      });

  private final Unmarshaller<HttpEntity, UUID> UUID_FROM_XML_BODY =
    Unmarshaller.forMediaTypes(Arrays.asList(MediaTypes.TEXT_XML, MediaTypes.APPLICATION_XML),
      Unmarshaller.entityToString())
      .thenApply(s -> {
        // just a fake XML parser, assuming it's <id>...</id>
        // A real implementation could easily invoke JAXB here instead.
        Pattern regex = Pattern.compile("<id>(.+)</id>");
        Matcher matcher = regex.matcher(s);
        matcher.find();
        return UUID.fromString(matcher.group(1));
      });

  private final Unmarshaller<HttpEntity, UUID> UUID_FROM_BODY =
    Unmarshaller.firstOf(UUID_FROM_JSON_BODY, UUID_FROM_XML_BODY);

  private final Marshaller<UUID, RequestEntity> UUID_TO_JSON = Marshaller.wrapEntity(
    (UUID u) -> "{\"id\":\"" + u + "\"}",
    Marshaller.stringToEntity(),
    MediaTypes.APPLICATION_JSON
  );

  private Marshaller<UUID, RequestEntity> UUID_TO_XML(ContentType xmlType) {
    return Marshaller.byteStringMarshaller(xmlType).compose(
      (UUID u) -> ByteString.fromString("<id>" + u + "</id>"));
  }

  private final Marshaller<UUID, RequestEntity> UUID_TO_ENTITY =
    Marshaller.oneOf(
      UUID_TO_JSON,
      UUID_TO_XML(MediaTypes.APPLICATION_XML.toContentType(HttpCharsets.UTF_8)),
      UUID_TO_XML(MediaTypes.TEXT_XML.toContentType(HttpCharsets.UTF_8))
    );

  private static boolean isUUID(String s) {
    try {
      UUID.fromString(s);
      return true;
    } catch (IllegalArgumentException x) {
      return false;
    }
  }

  private Route uuidHeaderValue(Function<UUIDHeader, Route> inner) {
    return headerValueByName("UUID", value -> {
      return isUUID(value) ? inner.apply(new UUIDHeader(UUID.fromString(value)))
        : reject(Rejections.malformedHeader("UUID", "must be a valid UUID"));
    });
  }

  private class UUIDHeader extends CustomHeader {
    private final UUID value;

    public UUIDHeader(UUID value) {
      this.value = value;
    }

    @Override
    public String name() {
      return "UUID";
    }

    @Override
    public String value() {
      return value.toString();
    }

    public UUID uuid() {
      return value;
    }

    @Override
    public boolean renderInRequests() {
      return true;
    }

    @Override
    public boolean renderInResponses() {
      return true;
    }
  }

  @Test
  public void pathCanMatchUuid() {
    runRoute(route, HttpRequest.GET("/documents/359e4920-a6a2-4614-9355-113165d600fb"))
      .assertEntity("document 359e4920-a6a2-4614-9355-113165d600fb");
  }

  @Test
  public void pathCanMatchElement() {
    runRoute(route, HttpRequest.GET("/people/john"))
      .assertEntity("person john");
  }

  @Test
  public void paramIsExtracted() {
    runRoute(route, HttpRequest.GET("/cookies?amount=5"))
      .assertEntity("cookies 5");
  }

  @Test
  public void requiredParamCausesRejectionWhenMissing() {
    // The rejection on "amount" appears twice because we have two route alternatives both requiring it.
    runRouteUnSealed(route, HttpRequest.GET("/cookies"))
      .assertRejections(Rejections.missingQueryParam("amount"), Rejections.missingQueryParam("amount"));
  }

  @Test
  public void wrongParamTypeCausesNextRouteToBeEvaluated() {
    runRoute(route, HttpRequest.GET("/cookies?amount=one"))
      .assertEntity("cookies (string) one");
  }

  @Test
  public void requiredParamCauses404OnSealedRoute() {
    runRoute(route, HttpRequest.GET("/cookies"))
      .assertStatusCode(StatusCodes.NOT_FOUND)
      .assertEntity("Request is missing required query parameter 'amount'");
  }

  @Test
  public void customParamTypeCanBeExtracted() {
    runRoute(route, HttpRequest.GET("/cakes?amount=5"))
      .assertEntity("cakes 5");
  }

  @Test
  public void entityCanBeUnmarshalled() {
    runRoute(route, HttpRequest.POST("/bigdecimal").withEntity("1234"))
      .assertEntity("body 1234");
  }

  @Test
  public void entityCanBeUnmarshalledWhenPickingJsonUnmarshaller() {
    runRoute(route, HttpRequest
      .PUT("/uuid")
      .withEntity(ContentTypes.create(MediaTypes.APPLICATION_JSON),
        "{\"id\":\"76b38659-1dec-4ee6-86d0-9ca787bf578c\"}"))
      .assertEntity("uuid 76b38659-1dec-4ee6-86d0-9ca787bf578c");
  }

  @Test
  public void entityCanBeUnmarshalledWhenPickingXmlUnmarshaller() {
    runRoute(route, HttpRequest
      .PUT("/uuid")
      .withEntity(
        ContentTypes.create(MediaTypes.APPLICATION_XML, HttpCharsets.UTF_8),
        "<?xml version=\"1.0\"?><id>76b38659-1dec-4ee6-86d0-9ca787bf578c</id>"))
      .assertEntity("uuid 76b38659-1dec-4ee6-86d0-9ca787bf578c");
  }

  @Test
  public void entityCanBeMarshalledWhenJsonIsAccepted() {
    runRoute(route, HttpRequest.GET("/uuid").addHeader(Accept.create(MediaRanges.create(MediaTypes.APPLICATION_JSON))))
      .assertEntity("{\"id\":\"80a05eee-652e-4458-9bee-19b69dbe1dee\"}")
      .assertContentType(MediaTypes.APPLICATION_JSON.toContentType());
  }

  @Test
  public void entityCanBeMarshalledWhenXmlIsAccepted() {
    runRoute(route, HttpRequest.GET("/uuid").addHeader(Accept.create(MediaRanges.create(MediaTypes.TEXT_XML))))
      .assertEntity("<id>80a05eee-652e-4458-9bee-19b69dbe1dee</id>")
      .assertContentType(MediaTypes.TEXT_XML.toContentType(HttpCharsets.UTF_8));
  }

  @Test
  public void requestIsRejectedIfNoMarshallerFitsAcceptedType() {
    runRoute(route, HttpRequest.GET("/uuid").addHeader(Accept.create(MediaRanges.create(MediaTypes.TEXT_PLAIN))))
      .assertStatusCode(StatusCodes.NOT_ACCEPTABLE);
  }

  @Test
  public void firstMarshallerIsPickedAndStatusCodeAppliedIfNoAcceptHeaderPresent() {
    runRoute(route, HttpRequest.GET("/uuid"))
      .assertContentType(MediaTypes.APPLICATION_JSON.toContentType())
      .assertStatusCode(StatusCodes.FOUND);
  }

  @Test
  public void exceptionHandlersAreAppliedEvenIfTheRouteThrowsInFuture() {
    runRoute(route, HttpRequest.GET("/shouldnotfail"))
      .assertEntity("no problem!");
  }

  @Test
  public void routeWithRequiredHeaderFailsWhenHeaderIsAbsent() {
    runRouteUnSealed(route, HttpRequest.GET("/requiredheader"))
      .assertRejections(Rejections.missingHeader("UUID"));
  }

  @Test
  public void routeWithRequiredHeaderFailsWhenHeaderIsMalformed() {
    runRouteUnSealed(route, HttpRequest.GET("/requiredheader").addHeader(RawHeader.create("UUID", "monkeys")))
      .assertRejections(Rejections.malformedHeader("UUID", "must be a valid UUID"));
  }

  @Test
  public void routeWithRequiredHeaderSucceedsWhenHeaderIsPresentAsRawHeader() {
    runRoute(route, HttpRequest.GET("/requiredheader").addHeader(RawHeader.create("UUID", "98610fcb-7b19-4639-8dfa-08db8ac19320")))
      .assertEntity("has header: 98610fcb-7b19-4639-8dfa-08db8ac19320");
  }

  @Test
  public void routeWithRequiredHeaderSucceedsWhenHeaderIsPresentAsCustomType() {
    runRoute(route, HttpRequest.GET("/requiredheader").addHeader(new UUIDHeader(UUID.fromString("98610fcb-7b19-4639-8dfa-08db8ac19320"))))
      .assertEntity("has header: 98610fcb-7b19-4639-8dfa-08db8ac19320");
  }

  private final ExceptionHandler xHandler = ExceptionHandler.of(new PFBuilder<Throwable, Route>()
    .match(IllegalArgumentException.class, x -> complete("no problem!"))
    .build());

  private CompletionStage<Integer> throwExceptionInFuture() {
    return CompletableFuture.supplyAsync(() -> {
      throw new IllegalArgumentException("always failing");
    });
  }


  public Route getRoute() {
    return route(
      path(segment("hello").slash("world"), () ->
        complete("hello, world")
      ),
      path(segment("number").slash(integerSegment()), i ->
        complete("you said: " + (i * i))
      ),
      pathPrefix("documents", () ->
        path(uuidSegment(), id ->
          complete("document " + id)
        )
      ),
      pathPrefix("people", () ->
        path(name ->
          complete("person " + name)
        )
      ),
      path("notreally", () ->
        reject(Rejections.missingFormField("always failing"))
      ),
      path("shouldnotfail", () ->
        handleExceptions(xHandler, () ->
          onSuccess(() -> throwExceptionInFuture(), value ->
            complete("never reaches here")
          )
        )
      ),
      path("cookies", () ->
        parameter(StringUnmarshallers.INTEGER, "amount", amount ->
          complete("cookies " + amount)
        )
      ),
      path("cookies", () ->
        parameter("amount", (String amount) ->
          complete("cookies (string) " + amount)
        )
      ),
      path("custom_response", () ->
        complete(HttpResponse.create().withStatus(StatusCodes.ACCEPTED))
      ),
      path("bigdecimal", () ->
        entity(BIG_DECIMAL_BODY, value ->
          complete("body " + value)
        )
      ),
      path("uuid", () -> route(
        put(() ->
          entity(UUID_FROM_BODY, value ->
            complete("uuid " + value)
          )
        ),
        get(() -> {
          UUID id = UUID.fromString("80a05eee-652e-4458-9bee-19b69dbe1dee");
          return complete(StatusCodes.FOUND, id, UUID_TO_ENTITY);
        })
      )),
      path("cakes", () ->
        parameter(BIG_DECIMAL_PARAM, "amount", amount ->
          complete("cakes " + amount)
        )
      ),
      path("requiredheader", () ->
        uuidHeaderValue(h ->
          complete("has header: " + h.uuid())
        )
      )
    );
  }
}