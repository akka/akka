/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.values;

import akka.http.javadsl.model.HttpCharsets;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.server.RequestVal;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import akka.japi.Pair;
import org.junit.Test;

import java.util.Optional;

public class FormFieldsTest extends JUnitRouteTest {
    static FormField<String> stringParam = FormFields.stringValue("stringParam");
    static FormField<Byte> byteParam = FormFields.byteValue("byteParam");
    static FormField<Short> shortParam = FormFields.shortValue("shortParam");
    static FormField<Integer> intParam = FormFields.intValue("intParam");
    static FormField<Long> longParam = FormFields.longValue("longParam");
    static FormField<Float> floatParam = FormFields.floatValue("floatParam");
    static FormField<Double> doubleParam = FormFields.doubleValue("doubleParam");

    static FormField<Byte> hexByteParam = FormFields.hexByteValue("hexByteParam");
    static FormField<Short> hexShortParam = FormFields.hexShortValue("hexShortParam");
    static FormField<Integer> hexIntParam = FormFields.hexIntValue("hexIntParam");
    static FormField<Long> hexLongParam = FormFields.hexLongValue("hexLongParam");

    static RequestVal<String> nameWithDefault = FormFields.stringValue("nameWithDefault").withDefault("John Doe");
    static RequestVal<Optional<Integer>> optionalIntParam = FormFields.intValue("optionalIntParam").optional();

    private Pair<String, String> param(String name, String value) {
        return Pair.create(name, value);
    }
    @SafeVarargs
    final private HttpRequest urlEncodedRequest(Pair<String, String>... params) {
        StringBuilder sb = new StringBuilder();
        boolean next = false;
        for (Pair<String, String> param: params) {
            if (next) {
                sb.append('&');
            }
            next = true;
            sb.append(param.first());
            sb.append('=');
            sb.append(param.second());
        }

        return
            HttpRequest.POST("/test")
                .withEntity(MediaTypes.APPLICATION_X_WWW_FORM_URLENCODED.toContentType(HttpCharsets.UTF_8), sb.toString());
    }
    private HttpRequest singleParameterUrlEncodedRequest(String name, String value) {
        return urlEncodedRequest(param(name, value));
    }

    @Test
    public void testStringFormFieldExtraction() {
        TestRoute route = testRoute(completeWithValueToString(stringParam));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(400)
            .assertEntity("Request is missing required form field 'stringParam'");

        route
            .run(singleParameterUrlEncodedRequest("stringParam", "john"))
            .assertStatusCode(200)
            .assertEntity("john");
    }

    @Test
    public void testByteFormFieldExtraction() {
        TestRoute route = testRoute(completeWithValueToString(byteParam));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(400)
            .assertEntity("Request is missing required form field 'byteParam'");

        route
            .run(singleParameterUrlEncodedRequest("byteParam", "test"))
            .assertStatusCode(400)
            .assertEntity("The form field 'byteParam' was malformed:\n'test' is not a valid 8-bit signed integer value");

        route
            .run(singleParameterUrlEncodedRequest("byteParam", "1000"))
            .assertStatusCode(400)
            .assertEntity("The form field 'byteParam' was malformed:\n'1000' is not a valid 8-bit signed integer value");

        route
            .run(singleParameterUrlEncodedRequest("byteParam", "48"))
            .assertStatusCode(200)
            .assertEntity("48");
    }

    @Test
    public void testShortFormFieldExtraction() {
        TestRoute route = testRoute(completeWithValueToString(shortParam));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(400)
            .assertEntity("Request is missing required form field 'shortParam'");

        route
            .run(singleParameterUrlEncodedRequest("shortParam", "test"))
            .assertStatusCode(400)
            .assertEntity("The form field 'shortParam' was malformed:\n'test' is not a valid 16-bit signed integer value");

        route
            .run(singleParameterUrlEncodedRequest("shortParam", "100000"))
            .assertStatusCode(400)
            .assertEntity("The form field 'shortParam' was malformed:\n'100000' is not a valid 16-bit signed integer value");

        route
            .run(singleParameterUrlEncodedRequest("shortParam", "1234"))
            .assertStatusCode(200)
            .assertEntity("1234");
    }

    @Test
    public void testIntegerFormFieldExtraction() {
        TestRoute route = testRoute(completeWithValueToString(intParam));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(400)
            .assertEntity("Request is missing required form field 'intParam'");

        route
            .run(singleParameterUrlEncodedRequest("intParam", "test"))
            .assertStatusCode(400)
            .assertEntity("The form field 'intParam' was malformed:\n'test' is not a valid 32-bit signed integer value");

        route
            .run(singleParameterUrlEncodedRequest("intParam", "48"))
            .assertStatusCode(200)
            .assertEntity("48");
    }

    @Test
    public void testLongFormFieldExtraction() {
        TestRoute route = testRoute(completeWithValueToString(longParam));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(400)
            .assertEntity("Request is missing required form field 'longParam'");

        route
            .run(singleParameterUrlEncodedRequest("longParam", "test"))
            .assertStatusCode(400)
            .assertEntity("The form field 'longParam' was malformed:\n'test' is not a valid 64-bit signed integer value");

        route
            .run(singleParameterUrlEncodedRequest("longParam", "123456"))
            .assertStatusCode(200)
            .assertEntity("123456");
    }

    @Test
    public void testFloatFormFieldExtraction() {
        TestRoute route = testRoute(completeWithValueToString(floatParam));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(400)
            .assertEntity("Request is missing required form field 'floatParam'");

        route
            .run(singleParameterUrlEncodedRequest("floatParam", "test"))
            .assertStatusCode(400)
            .assertEntity("The form field 'floatParam' was malformed:\n'test' is not a valid 32-bit floating point value");

        route
            .run(singleParameterUrlEncodedRequest("floatParam", "48.123"))
            .assertStatusCode(200)
            .assertEntity("48.123");
    }

    @Test
    public void testDoubleFormFieldExtraction() {
        TestRoute route = testRoute(completeWithValueToString(doubleParam));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(400)
            .assertEntity("Request is missing required form field 'doubleParam'");

        route
            .run(singleParameterUrlEncodedRequest("doubleParam", "test"))
            .assertStatusCode(400)
            .assertEntity("The form field 'doubleParam' was malformed:\n'test' is not a valid 64-bit floating point value");

        route
            .run(singleParameterUrlEncodedRequest("doubleParam", "0.234213235987"))
            .assertStatusCode(200)
            .assertEntity("0.234213235987");
    }

    @Test
    public void testHexByteFormFieldExtraction() {
        TestRoute route = testRoute(completeWithValueToString(hexByteParam));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(400)
            .assertEntity("Request is missing required form field 'hexByteParam'");

        route
            .run(singleParameterUrlEncodedRequest("hexByteParam", "test"))
            .assertStatusCode(400)
            .assertEntity("The form field 'hexByteParam' was malformed:\n'test' is not a valid 8-bit hexadecimal integer value");

        route
            .run(singleParameterUrlEncodedRequest("hexByteParam", "1000"))
            .assertStatusCode(400)
            .assertEntity("The form field 'hexByteParam' was malformed:\n'1000' is not a valid 8-bit hexadecimal integer value");

        route
            .run(singleParameterUrlEncodedRequest("hexByteParam", "48"))
            .assertStatusCode(200)
            .assertEntity(Integer.toString(0x48));
    }

    @Test
    public void testHexShortFormFieldExtraction() {
        TestRoute route = testRoute(completeWithValueToString(hexShortParam));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(400)
            .assertEntity("Request is missing required form field 'hexShortParam'");

        route
            .run(singleParameterUrlEncodedRequest("hexShortParam", "test"))
            .assertStatusCode(400)
            .assertEntity("The form field 'hexShortParam' was malformed:\n'test' is not a valid 16-bit hexadecimal integer value");

        route
            .run(singleParameterUrlEncodedRequest("hexShortParam", "100000"))
            .assertStatusCode(400)
            .assertEntity("The form field 'hexShortParam' was malformed:\n'100000' is not a valid 16-bit hexadecimal integer value");

        route
            .run(singleParameterUrlEncodedRequest("hexShortParam", "1234"))
            .assertStatusCode(200)
            .assertEntity(Integer.toString(0x1234));
    }

    @Test
    public void testHexIntegerFormFieldExtraction() {
        TestRoute route = testRoute(completeWithValueToString(hexIntParam));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(400)
            .assertEntity("Request is missing required form field 'hexIntParam'");

        route
            .run(singleParameterUrlEncodedRequest("hexIntParam", "test"))
            .assertStatusCode(400)
            .assertEntity("The form field 'hexIntParam' was malformed:\n'test' is not a valid 32-bit hexadecimal integer value");

        route
            .run(singleParameterUrlEncodedRequest("hexIntParam", "12345678"))
            .assertStatusCode(200)
            .assertEntity(Integer.toString(0x12345678));
    }

    @Test
    public void testHexLongFormFieldExtraction() {
        TestRoute route = testRoute(completeWithValueToString(hexLongParam));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(400)
            .assertEntity("Request is missing required form field 'hexLongParam'");

        route
            .run(singleParameterUrlEncodedRequest("hexLongParam", "test"))
            .assertStatusCode(400)
            .assertEntity("The form field 'hexLongParam' was malformed:\n'test' is not a valid 64-bit hexadecimal integer value");

        route
            .run(singleParameterUrlEncodedRequest("hexLongParam", "123456789a"))
            .assertStatusCode(200)
            .assertEntity(Long.toString(0x123456789aL));
    }

    @Test
    public void testOptionalIntFormFieldExtraction() {
        TestRoute route = testRoute(completeWithValueToString(optionalIntParam));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(200)
            .assertEntity("None");

        route
            .run(singleParameterUrlEncodedRequest("optionalIntParam", "23"))
            .assertStatusCode(200)
            .assertEntity("Some(23)");
    }

    @Test
    public void testStringFormFieldExtractionWithDefaultValue() {
        TestRoute route = testRoute(completeWithValueToString(nameWithDefault));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(200)
            .assertEntity("John Doe");

        route
            .run(singleParameterUrlEncodedRequest("nameWithDefault", "paul"))
            .assertStatusCode(200)
            .assertEntity("paul");
    }
}
