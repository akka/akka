///*
// * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
// */
//package akka.http.javadsl.server;
//
//import akka.http.javadsl.model.*;
//import akka.http.javadsl.model.headers.Accept;
//import akka.http.javadsl.model.headers.RawHeader;
//import akka.http.javadsl.testkit.JUnitRouteTest;
//import akka.http.scaladsl.model.headers.CustomHeader;
//import akka.japi.pf.PFBuilder;
//import akka.util.ByteString;
//import org.junit.Test;
//
//import java.math.BigDecimal;
//import java.util.Arrays;
//import java.util.UUID;
//import java.util.concurrent.CompletableFuture;
//import java.util.concurrent.CompletionStage;
//import java.util.function.Function;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
//import static akka.http.javadsl.server.PathMatcher.segment;
//import static akka.http.javadsl.server.PathMatchers.INTEGER_SEGMENT;
//import static akka.http.javadsl.server.PathMatchers.UUID_SEGMENT;
//
//public class JavaTestServer extends HttpApp {
//
//    @Override
//    public Route createRoute() {
//        return get(
//                path("", withRequestTimeout)
//        )
//    }
//}