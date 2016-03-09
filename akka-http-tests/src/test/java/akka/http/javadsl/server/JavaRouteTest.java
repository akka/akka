/*
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http.javadsl.server;

import static akka.http.javadsl.server.PathMatcher.segment;
import static akka.http.javadsl.server.PathMatchers.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import akka.http.javadsl.model.ContentType;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpCharsets;
import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.MediaRanges;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.model.RequestEntity;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.Accept;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.javadsl.testkit.JUnitRouteTest;
//FIXME discuss how to provide a javadsl.CustomHeader where render() is either pre-implemented or trivial to write in Java
import akka.http.scaladsl.model.headers.CustomHeader;
import akka.japi.pf.PFBuilder;
import akka.util.ByteString;

public class JavaRouteTest extends JUnitRouteTest {
    private final Route route = getRoute();
    private static final Unmarshaller<String,BigDecimal> BIG_DECIMAL_PARAM = Unmarshaller.sync(s -> new BigDecimal(s));
    
    private final Unmarshaller<HttpEntity,BigDecimal> BIG_DECIMAL_BODY =
        Unmarshaller.entityToString()
                    .map(s -> new BigDecimal(s));
    
    private final Unmarshaller<HttpEntity,UUID> UUID_FROM_JSON_BODY =
        Unmarshaller.forMediaType(MediaTypes.APPLICATION_JSON,
                         Unmarshaller.entityToString())
                    .map(s -> {
                        // just a fake JSON parser, assuming it's {"id":"..."}
                    	// A real implementation could easily invoke Jackson here instead.
                        Pattern regex = Pattern.compile("\"id\":\"(.+)\"");
                        Matcher matcher = regex.matcher(s);
                        matcher.find();
                        return UUID.fromString(matcher.group(1));
                    });
    
    private final Unmarshaller<HttpEntity,UUID> UUID_FROM_XML_BODY =
        Unmarshaller.forMediaTypes(Arrays.asList(MediaTypes.TEXT_XML, MediaTypes.APPLICATION_XML),
                        Unmarshaller.entityToString())
                    .map(s -> {
                        // just a fake XML parser, assuming it's <id>...</id>
                    	// A real implementation could easily invoke JAXB here instead.
                        Pattern regex = Pattern.compile("<id>(.+)</id>");
                        Matcher matcher = regex.matcher(s);
                        matcher.find();
                        return UUID.fromString(matcher.group(1));
                    });
    
    private final Unmarshaller<HttpEntity,UUID> UUID_FROM_BODY = 
        Unmarshaller.firstOf(UUID_FROM_JSON_BODY, UUID_FROM_XML_BODY);
    
    private final Marshaller<UUID, RequestEntity> UUID_TO_JSON = Marshaller.wrapEntity(
        (UUID u) -> "{\"id\":\"" + u + "\"}",
        Marshaller.stringToEntity(), 
        MediaTypes.APPLICATION_JSON 
    );
    
    private final Marshaller<UUID, RequestEntity> UUID_TO_XML(ContentType xmlType) { 
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
    public void path_can_match_uuid() {
    	runRoute(route, HttpRequest.GET("/documents/359e4920-a6a2-4614-9355-113165d600fb"))
    		.assertEntity("document 359e4920-a6a2-4614-9355-113165d600fb");
    }

    @Test
    public void path_can_match_element() {
    	runRoute(route, HttpRequest.GET("/people/john"))
    		.assertEntity("person john");
    }

    @Test
    public void param_is_extracted() {
    	runRoute(route, HttpRequest.GET("/cookies?amount=5"))
    		.assertEntity("cookies 5");            
    }
    
    @Test
    public void required_param_causes_rejection_when_missing() {
    	// The rejection on "amount" appears twice because we have two route alternatives both requiring it.
    	runRouteUnSealed(route, HttpRequest.GET("/cookies"))
    		.assertRejections(Rejections.missingQueryParam("amount"), Rejections.missingQueryParam("amount"));            
    }
    
    @Test
    public void wrong_param_type_causes_next_route_to_be_evaluated() {
    	runRoute(route, HttpRequest.GET("/cookies?amount=one"))
            .assertEntity("cookies (string) one");            
    }
    
    @Test
    public void required_param_causes_404_on_sealed_route() {
        runRoute(route, HttpRequest.GET("/cookies"))
        	.assertStatusCode(StatusCodes.NOT_FOUND)
            .assertEntity("Request is missing required query parameter 'amount'");
    }
    
    @Test
    public void custom_param_type_can_be_extracted() {
        runRoute(route, HttpRequest.GET("/cakes?amount=5"))
            .assertEntity("cakes 5");            
    }
    
    @Test
    public void entity_can_be_unmarshalled() {
    	runRoute(route, HttpRequest.POST("/bigdecimal").withEntity("1234"))
            .assertEntity("body 1234");            
    }
    
    @Test
    public void entity_can_be_unmarshalled_when_picking_json_unmarshaller() {
        runRoute(route, HttpRequest
        		.PUT("/uuid")
        		.withEntity(ContentTypes.create(MediaTypes.APPLICATION_JSON), 
        				"{\"id\":\"76b38659-1dec-4ee6-86d0-9ca787bf578c\"}"))
            .assertEntity("uuid 76b38659-1dec-4ee6-86d0-9ca787bf578c");            
    }
    
    @Test
    public void entity_can_be_unmarshalled_when_picking_xml_unmarshaller() {
        runRoute(route, HttpRequest
        		.PUT("/uuid")
        		.withEntity(ContentTypes.create(MediaTypes.APPLICATION_XML, HttpCharsets.UTF_8), 
        				"<id>76b38659-1dec-4ee6-86d0-9ca787bf578c</id>"))
            .assertEntity("uuid 76b38659-1dec-4ee6-86d0-9ca787bf578c");
    }
    
	@Test
    public void entity_can_be_marshalled_when_json_is_accepted() {
    	runRoute(route, HttpRequest.GET("/uuid").addHeader(Accept.create(MediaRanges.create(MediaTypes.APPLICATION_JSON))))
            .assertEntity("{\"id\":\"80a05eee-652e-4458-9bee-19b69dbe1dee\"}")            
            .assertContentType(MediaTypes.APPLICATION_JSON.toContentType());
    }
    
    @Test
    public void entity_can_be_marshalled_when_xml_is_accepted() {
        runRoute(route, HttpRequest.GET("/uuid").addHeader(Accept.create(MediaRanges.create(MediaTypes.TEXT_XML))))
            .assertEntity("<id>80a05eee-652e-4458-9bee-19b69dbe1dee</id>")
            .assertContentType(MediaTypes.TEXT_XML.toContentType(HttpCharsets.UTF_8));
    }
    
    @Test
    public void request_is_rejected_if_no_marshaller_fits_accepted_type() {
        runRoute(route, HttpRequest.GET("/uuid").addHeader(Accept.create(MediaRanges.create(MediaTypes.TEXT_PLAIN))))
            .assertStatusCode(StatusCodes.NOT_ACCEPTABLE);
    }

    @Test
    public void first_marshaller_is_picked_and_status_code_applied_if_no_accept_header_present() {
        runRoute(route, HttpRequest.GET("/uuid"))
            .assertContentType(MediaTypes.APPLICATION_JSON.toContentType())
            .assertStatusCode(StatusCodes.FOUND);            
    }
    
    @Test
    public void exception_handlers_are_applied_even_if_the_route_throws_in_future() {
        runRoute(route, HttpRequest.GET("/shouldnotfail"))
            .assertEntity("no problem!");            
    }
    
    @Test
    public void route_with_required_header_fails_when_header_is_absent() {
        runRouteUnSealed(route, HttpRequest.GET("/requiredheader"))
            .assertRejections(Rejections.missingHeader("UUID"));            
    }
    
    @Test
    public void route_with_required_header_fails_when_header_is_malformed() {
        runRouteUnSealed(route, HttpRequest.GET("/requiredheader").addHeader(RawHeader.create("UUID", "monkeys")))
            .assertRejections(Rejections.malformedHeader("UUID", "must be a valid UUID"));            
    }
    
    @Test
    public void route_with_required_header_succeeds_when_header_is_present_as_RawHeader() {
        runRoute(route, HttpRequest.GET("/requiredheader").addHeader(RawHeader.create("UUID", "98610fcb-7b19-4639-8dfa-08db8ac19320")))
            .assertEntity("has header: 98610fcb-7b19-4639-8dfa-08db8ac19320");
    }
    
    @Test
    public void route_with_required_header_succeeds_when_header_is_present_as_custom_type() {
        runRoute(route, HttpRequest.GET("/requiredheader").addHeader(new UUIDHeader(UUID.fromString("98610fcb-7b19-4639-8dfa-08db8ac19320"))))
            .assertEntity("has header: 98610fcb-7b19-4639-8dfa-08db8ac19320");
    }
    
    private final ExceptionHandler xHandler = ExceptionHandler.of(new PFBuilder<Throwable, Route>()
        .match(IllegalArgumentException.class, x -> complete("no problem!"))
        .build());
    
    private CompletionStage<Integer> throwExceptionInFuture() {
    	return CompletableFuture.supplyAsync(() -> { throw new IllegalArgumentException("always failing"); });
    }
    
    
    public Route getRoute() {
        return route(
            path(segment("hello").slash("world"), () ->
                complete("hello, world")
            ),
            path(segment("number").slash(INTEGER_SEGMENT), i ->
                complete("you said: " + (i * i))
            ),
            path("documents", () ->
                path(UUID_SEGMENT, id ->
                    complete("document " + id)
                )
            ),
            path("people", () ->
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
                param(StringUnmarshallers.INTEGER, "amount", amount -> 
                    complete("cookies " + amount)
                )
            ),
            path("cookies", () ->
                param("amount", (String amount) -> 
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
                param(BIG_DECIMAL_PARAM, "amount", amount -> 
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