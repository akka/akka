Sources and sinks for integrating with `java.io.InputStream` and `java.io.OutputStream` can be found on
`StreamConverters`. As they are blocking APIs the implementations of these operators are run on a separate
dispatcher configured through the `akka.stream.blocking-io-dispatcher`.

@@@ warning

Be aware that `asInputStream` and `asOutputStream` materialize `InputStream` and `OutputStream` respectively as
blocking API implementation. They will block the thread until data will be available from upstream.
Because of blocking nature these objects cannot be used in `mapMaterializeValue` section as it causes deadlock
of the stream materialization process.
For example, following snippet will fall with timeout exception:

```scala
...
.toMat(StreamConverters.asInputStream().mapMaterializedValue { inputStream =>
        inputStream.read()  // this could block forever
        ...
}).run()
```

@@@
