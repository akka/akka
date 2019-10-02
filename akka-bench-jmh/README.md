# Akka Microbenchmarks

This subproject contains some microbenchmarks excercising key parts of Akka. (Excluding typed which has its 
own jmh module)


You can run them like:

   project akka-bench-jmh
   jmh:run -i 3 -wi 3 -f 1 .*ActorCreationBenchmark

Use 'jmh:run -h' to get an overview of the available options.

Some potentially out of date resources for writing JMH benchmarks:

* [Studying what's wrong with JMH benchmarks](https://www.researchgate.net/publication/333825812_What's_Wrong_With_My_Benchmark_Results_Studying_Bad_Practices_in_JMH_Benchmarks)
* [Writing good benchmarks](http://tutorials.jenkov.com/java-performance/jmh.html#writing-good-benchmarks)
