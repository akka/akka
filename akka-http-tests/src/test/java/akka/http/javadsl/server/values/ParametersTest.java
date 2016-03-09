/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.values;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;

import org.junit.Test;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.server.StringUnmarshallers;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;

public class ParametersTest extends JUnitRouteTest {

    @Test
    public void testStringParameterExtraction() {
        TestRoute route = testRoute(param("stringParam", value -> complete(value)));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(404)
            .assertEntity("Request is missing required query parameter 'stringParam'");

        route
            .run(HttpRequest.create().withUri("/abc?stringParam=john"))
            .assertStatusCode(200)
            .assertEntity("john");
    }

    @Test
    public void testByteParameterExtraction() {
        TestRoute route = testRoute(param(StringUnmarshallers.BYTE, "byteParam", value -> complete(value.toString())));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(404)
            .assertEntity("Request is missing required query parameter 'byteParam'");

        route
            .run(HttpRequest.create().withUri("/abc?byteParam=test"))
            .assertStatusCode(400)
            .assertEntity("The query parameter 'byteParam' was malformed:\n'test' is not a valid 8-bit signed integer value");

        route
            .run(HttpRequest.create().withUri("/abc?byteParam=1000"))
            .assertStatusCode(400)
            .assertEntity("The query parameter 'byteParam' was malformed:\n'1000' is not a valid 8-bit signed integer value");

        route
            .run(HttpRequest.create().withUri("/abc?byteParam=48"))
            .assertStatusCode(200)
            .assertEntity("48");
    }

    @Test
    public void testShortParameterExtraction() {
        TestRoute route = testRoute(param(StringUnmarshallers.SHORT, "shortParam", value -> complete(value.toString())));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(404)
            .assertEntity("Request is missing required query parameter 'shortParam'");

        route
            .run(HttpRequest.create().withUri("/abc?shortParam=test"))
            .assertStatusCode(400)
            .assertEntity("The query parameter 'shortParam' was malformed:\n'test' is not a valid 16-bit signed integer value");

        route
            .run(HttpRequest.create().withUri("/abc?shortParam=100000"))
            .assertStatusCode(400)
            .assertEntity("The query parameter 'shortParam' was malformed:\n'100000' is not a valid 16-bit signed integer value");

        route
            .run(HttpRequest.create().withUri("/abc?shortParam=1234"))
            .assertStatusCode(200)
            .assertEntity("1234");
    }

    @Test
    public void testIntegerParameterExtraction() {
        TestRoute route = testRoute(param(StringUnmarshallers.INTEGER, "intParam", value -> complete(value.toString())));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(404)
            .assertEntity("Request is missing required query parameter 'intParam'");

        route
            .run(HttpRequest.create().withUri("/abc?intParam=test"))
            .assertStatusCode(400)
            .assertEntity("The query parameter 'intParam' was malformed:\n'test' is not a valid 32-bit signed integer value");

        route
            .run(HttpRequest.create().withUri("/abc?intParam=48"))
            .assertStatusCode(200)
            .assertEntity("48");
    }

    @Test
    public void testLongParameterExtraction() {
        TestRoute route = testRoute(param(StringUnmarshallers.LONG, "longParam", value -> complete(value.toString())));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(404)
            .assertEntity("Request is missing required query parameter 'longParam'");

        route
            .run(HttpRequest.create().withUri("/abc?longParam=test"))
            .assertStatusCode(400)
            .assertEntity("The query parameter 'longParam' was malformed:\n'test' is not a valid 64-bit signed integer value");

        route
            .run(HttpRequest.create().withUri("/abc?longParam=123456"))
            .assertStatusCode(200)
            .assertEntity("123456");
    }

    @Test
    public void testFloatParameterExtraction() {
        TestRoute route = testRoute(param(StringUnmarshallers.FLOAT, "floatParam", value -> complete(value.toString())));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(404)
            .assertEntity("Request is missing required query parameter 'floatParam'");

        route
            .run(HttpRequest.create().withUri("/abc?floatParam=test"))
            .assertStatusCode(400)
            .assertEntity("The query parameter 'floatParam' was malformed:\n'test' is not a valid 32-bit floating point value");

        route
            .run(HttpRequest.create().withUri("/abc?floatParam=48"))
            .assertStatusCode(200)
            .assertEntity("48.0");
    }

    @Test
    public void testDoubleParameterExtraction() {
        TestRoute route = testRoute(param(StringUnmarshallers.DOUBLE, "doubleParam", value -> complete(value.toString())));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(404)
            .assertEntity("Request is missing required query parameter 'doubleParam'");

        route
            .run(HttpRequest.create().withUri("/abc?doubleParam=test"))
            .assertStatusCode(400)
            .assertEntity("The query parameter 'doubleParam' was malformed:\n'test' is not a valid 64-bit floating point value");

        route
            .run(HttpRequest.create().withUri("/abc?doubleParam=48"))
            .assertStatusCode(200)
            .assertEntity("48.0");
    }

    @Test
    public void testHexByteParameterExtraction() {
        TestRoute route = testRoute(param(StringUnmarshallers.BYTE, "hexByteParam", value -> complete(value.toString())));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(404)
            .assertEntity("Request is missing required query parameter 'hexByteParam'");

        route
            .run(HttpRequest.create().withUri("/abc?hexByteParam=test"))
            .assertStatusCode(400)
            .assertEntity("The query parameter 'hexByteParam' was malformed:\n'test' is not a valid 8-bit hexadecimal integer value");

        route
            .run(HttpRequest.create().withUri("/abc?hexByteParam=1000"))
            .assertStatusCode(400)
            .assertEntity("The query parameter 'hexByteParam' was malformed:\n'1000' is not a valid 8-bit hexadecimal integer value");

        route
            .run(HttpRequest.create().withUri("/abc?hexByteParam=48"))
            .assertStatusCode(200)
            .assertEntity(Integer.toString(0x48));
    }

    @Test
    public void testHexShortParameterExtraction() {
        TestRoute route = testRoute(param(StringUnmarshallers.SHORT_HEX, "hexShortParam", value -> complete(value.toString())));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(404)
            .assertEntity("Request is missing required query parameter 'hexShortParam'");

        route
            .run(HttpRequest.create().withUri("/abc?hexShortParam=test"))
            .assertStatusCode(400)
            .assertEntity("The query parameter 'hexShortParam' was malformed:\n'test' is not a valid 16-bit hexadecimal integer value");

        route
            .run(HttpRequest.create().withUri("/abc?hexShortParam=100000"))
            .assertStatusCode(400)
            .assertEntity("The query parameter 'hexShortParam' was malformed:\n'100000' is not a valid 16-bit hexadecimal integer value");

        route
            .run(HttpRequest.create().withUri("/abc?hexShortParam=1234"))
            .assertStatusCode(200)
            .assertEntity(Integer.toString(0x1234));
    }

    @Test
    public void testHexIntegerParameterExtraction() {
        TestRoute route = testRoute(param(StringUnmarshallers.INTEGER_HEX, "hexIntParam", value -> complete(value.toString())));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(404)
            .assertEntity("Request is missing required query parameter 'hexIntParam'");

        route
            .run(HttpRequest.create().withUri("/abc?hexIntParam=test"))
            .assertStatusCode(400)
            .assertEntity("The query parameter 'hexIntParam' was malformed:\n'test' is not a valid 32-bit hexadecimal integer value");

        route
            .run(HttpRequest.create().withUri("/abc?hexIntParam=12345678"))
            .assertStatusCode(200)
            .assertEntity(Integer.toString(0x12345678));
    }

    @Test
    public void testHexLongParameterExtraction() {
        TestRoute route = testRoute(param(StringUnmarshallers.LONG_HEX, "hexLongParam", value -> complete(value.toString())));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(404)
            .assertEntity("Request is missing required query parameter 'hexLongParam'");

        route
            .run(HttpRequest.create().withUri("/abc?hexLongParam=test"))
            .assertStatusCode(400)
            .assertEntity("The query parameter 'hexLongParam' was malformed:\n'test' is not a valid 64-bit hexadecimal integer value");

        route
            .run(HttpRequest.create().withUri("/abc?hexLongParam=123456789a"))
            .assertStatusCode(200)
            .assertEntity(Long.toString(0x123456789aL));
    }

    @Test
    public void testParametersAsMapExtraction() {
        TestRoute route = testRoute(
            parameterMap(paramMap -> {
                ArrayList<String> keys = new ArrayList<String>(paramMap.keySet());
                Collections.sort(keys);
                StringBuilder res = new StringBuilder();
                res.append(paramMap.size()).append(": [");
                for (String key: keys)
                    res.append(key).append(" -> ").append(paramMap.get(key)).append(", ");
                res.append(']');
                return complete(res.toString());
        }));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(200)
            .assertEntity("0: []");

        route
            .run(HttpRequest.create().withUri("/abc?a=b"))
            .assertStatusCode(200)
            .assertEntity("1: [a -> b, ]");

        route
            .run(HttpRequest.create().withUri("/abc?a=b&c=d"))
            .assertStatusCode(200)
            .assertEntity("2: [a -> b, c -> d, ]");
    }
    @Test
    public void testParametersAsMultiMapExtraction() {
        TestRoute route = testRoute(
                parameterMultiMap(paramMap -> {
                ArrayList<String> keys = new ArrayList<String>(paramMap.keySet());
                Collections.sort(keys);
                StringBuilder res = new StringBuilder();
                res.append(paramMap.size()).append(": [");
                for (String key: keys) {
                    res.append(key).append(" -> [");
                    ArrayList<String> values = new ArrayList<String>(paramMap.get(key));
                    Collections.sort(values);
                    for (String value: values)
                        res.append(value).append(", ");
                    res.append("], ");
                }
                res.append(']');
                return complete(res.toString());
        }));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(200)
            .assertEntity("0: []");

        route
            .run(HttpRequest.create().withUri("/abc?a=b"))
            .assertStatusCode(200)
            .assertEntity("1: [a -> [b, ], ]");

        route
            .run(HttpRequest.create().withUri("/abc?a=b&c=d&a=a"))
            .assertStatusCode(200)
            .assertEntity("2: [a -> [a, b, ], c -> [d, ], ]");
    }
    @Test
    public void testParametersAsCollectionExtraction() {
        TestRoute route = testRoute(
                parameterList(paramEntries -> { 
                ArrayList<Map.Entry<String, String>> entries = new ArrayList<Map.Entry<String, String>>(paramEntries);
                Collections.sort(entries, new Comparator<Map.Entry<String, String>>() {
                    @Override
                    public int compare(Map.Entry<String, String> e1, Map.Entry<String, String> e2) {
                        int res = e1.getKey().compareTo(e2.getKey());
                        return res == 0 ? e1.getValue().compareTo(e2.getValue()) : res;
                    }
                });

                StringBuilder res = new StringBuilder();
                res.append(paramEntries.size()).append(": [");
                for (Map.Entry<String, String> entry: entries)
                    res.append(entry.getKey()).append(" -> ").append(entry.getValue()).append(", ");
                res.append(']');
                return complete(res.toString());
        }));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(200)
            .assertEntity("0: []");

        route
            .run(HttpRequest.create().withUri("/abc?a=b&e=f&c=d"))
            .assertStatusCode(200)
            .assertEntity("3: [a -> b, c -> d, e -> f, ]");

        route
            .run(HttpRequest.create().withUri("/abc?a=b&e=f&c=d&a=z"))
            .assertStatusCode(200)
            .assertEntity("4: [a -> b, a -> z, c -> d, e -> f, ]");
    }

    @Test
    public void testOptionalIntParameterExtraction() {
        TestRoute route = testRoute(paramOptional(StringUnmarshallers.INTEGER, "optionalIntParam", value -> complete(value.toString())));

        route
            .run(HttpRequest.create().withUri("/abc"))
            .assertStatusCode(200)
            .assertEntity("Optional.empty");

        route
            .run(HttpRequest.create().withUri("/abc?optionalIntParam=23"))
            .assertStatusCode(200)
            .assertEntity("Optional[23]");
    }

}
