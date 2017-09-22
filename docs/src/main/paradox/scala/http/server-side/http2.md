# Server-Side HTTP/2 (Preview)

@@@ warning

Server-Side HTTP/2 support in akka-http is currently available as a preview.
This means it is ready to be evaluated, but the APIs and behavior are likely to change.

@@@

## Enable HTTP/2 support

To enabled HTTP/2 support `akka-http2-support` must be added as a dependency:

@@dependency [sbt,Gradle,Maven] {
  group="com.typesafe.akka"
  artifact="akka-http2-support_$scala.binary.version$"
  version="$project.version$"
}

HTTP/2 can then be enabled through configuration:

```
akka.http.server.preview.enable-http2 = on
```

## Use `bindAndHandleAsync` and HTTPS

Only secure HTTP/2 (also known as "over HTTPS" or "with TLS") connections are supported as this is the prime mode of operation for this protocol. While un-encrypted connections are allowed by HTTP/2, clients that support this are rare. See the @ref[HTTPS section](server-https-support.md) for how to set up HTTPS.

You can use @scala[@scaladoc[Http().bindAndHandleAsync](akka.http.scaladsl.HttpExt)]@java[@javadoc[Http().get(system).bindAndHandleAsync()](akka.http.javadsl.HttpExt)] as long as you followed the above steps:

Scala
:   @@snip[Http2Spec.scala]($test$/scala/docs/http/scaladsl/Http2Spec.scala) { #bindAndHandleAsync }

Java
:   @@snip[Http2Test.java]($test$/java/docs/http/javadsl/Http2Test.java) { #bindAndHandleAsync }

Note that `bindAndHandle` currently does not support HTTP/2, you must use `bindAndHandleAsync`.

## Testing with cURL

At this point you should be able to connect, but HTTP/2 may still not be available.

You'll need a recent version of [cURL](https://curl.haxx.se/) compiled with HTTP/2 support (for OSX see [this article](https://simonecarletti.com/blog/2016/01/http2-curl-macosx/)). You can check whether your version supports HTTP2 with `curl --version`, look for the nghttp2 extension and the HTTP2 feature:

```
curl 7.52.1 (x86_64-pc-linux-gnu) libcurl/7.52.1 OpenSSL/1.0.2l zlib/1.2.8 libidn2/0.16 libpsl/0.17.0 (+libidn2/0.16) libssh2/1.8.0 nghttp2/1.23.1 librtmp/2.3
Protocols: dict file ftp ftps gopher http https imap imaps ldap ldaps pop3 pop3s rtmp rtsp scp sftp smb smbs smtp smtps telnet tftp
Features: AsynchDNS IDN IPv6 Largefile GSS-API Kerberos SPNEGO NTLM NTLM_WB SSL libz TLS-SRP HTTP2 UnixSockets HTTPS-proxy PSL
```

When you connect to your service you may now see something like:

```
$ curl -k -v https://localhost:8443
(...)
* ALPN, offering h2
* ALPN, offering http/1.1
(...)
* ALPN, server accepted to use h2
(...)
> GET / HTTP/1.1
(...)
< HTTP/2 200
(...)
```

If your curl output looks like above, you have successfully configured HTTP/2. However, on JDKs up to version 9, it is likely to look like this instead:

```
$ curl -k -v https://localhost:8443
(...)
* ALPN, offering h2
* ALPN, offering http/1.1
(...)
* ALPN, server did not agree to a protocol
(...)
> GET / HTTP/1.1
(...)
< HTTP/1.1 200 OK
(...)
```

This shows `curl` declaring it is ready to speak `h2` (the shorthand name of HTTP/2), but could not determine whether the server is ready to, so it fell back to HTTP/1.1. To make this negotiation work you'll have to configure ALPN as described below.

## Application-Layer Protocol Negotiation (ALPN)

[Application-Layer Protocol Negotiation (ALPN)](https://en.wikipedia.org/wiki/Application-Layer_Protocol_Negotiation) is used to negotiate whether both client and server support HTTP/2.

ALPN support comes with the JVM starting from version 9. If you're on a previous version of the JVM, you'll have to load a Java Agent to provide this functionality. We recommend the agent from the [Jetty](http://www.eclipse.org/jetty/) project, `jetty-alpn-agent`.

### manually

This agent can be loaded with the JVM option `-javaagent:/path/to/jetty-alpn-agent-2.0.6.jar`.

```
  java -javaagent:/path/to/jetty-alpn-agent-2.0.6.jar -jar app.jar
```

### sbt

sbt can be configured to load the agent with the [sbt-javaagent plugin](https://github.com/sbt/sbt-javaagent):

```
  .enablePlugins(JavaAgent)
  .settings(
    javaAgents += "org.mortbay.jetty.alpn" % "jetty-alpn-agent" % "2.0.6" % "runtime"
  )
```

This should automatically load the agent when running, testing, or even in distributions made with [sbt-native-package](https://github.com/sbt/sbt-native-packager).

@@@ div { .group-java}

### maven

To configure maven to load the agent when running `mvn exec:exec`, add it as a 'runtime' dependency:

```
<dependency>
    <groupId>org.mortbay.jetty.alpn</groupId>
    <artifactId>jetty-alpn-agent</artifactId>
    <version>2.0.6</version>
    <scope>runtime</scope>
</dependency>
```

and use the `maven-dependency-plugin`:

```
<plugin>
    <artifactId>maven-dependency-plugin</artifactId>
    <version>2.5.1</version>
    <executions>
        <execution>
            <id>getClasspathFilenames</id>
            <goals>
                <goal>properties</goal>
            </goals>
        </execution>
     </executions>
</plugin>
```

to add it to the `exec-maven-plugin` arguments:

```
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <version>1.6.0</version>
    <configuration>
        <executable>java</executable>
        <arguments>
            <argument>-javaagent:${org.mortbay.jetty.alpn:jetty-alpn-agent:jar}</argument>
            <argument>-classpath</argument>
            <classpath />
            <argument>com.example.HttpServer</argument>
        </arguments>
    </configuration>
</plugin>
```

@@@
