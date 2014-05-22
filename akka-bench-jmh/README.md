Akka JMH Benchmarks
===================
This project should depend on tested projects, and allows to benchmark them using the JMH library.

For good results consider running the benchmarks at least for 10 iterations (both warmup and measuring).

Usage
-----
Simply run a specified benchmark (parameters are 1:1 like in JMH):

```
run                             // all benchmarks, on default settings
run -i 3 -wi 3 -f 1 .*actor.*   // all actor benchmarks, 3 warmups, 3 iterations, 1 fork
run -h                          // shows JMH help
```

The internal workings of the code generation process are as follows:

```
compile: scala -> bytecode_1
generate: bytecode_1 -> java benchmarks source code
compile: java benchmarks -. bytecode_2
```

Examples
--------
There are a lot of good examples here (in java): http://hg.openjdk.java.net/code-tools/jmh/file/tip/jmh-samples/src/main/java/org/openjdk/jmh/samples


