# Compatibility Guidelines

## Binary Compatibility Rules

Akka HTTP follows the same binary compatibility rules as Akka itself.
In short it means that the versioning scheme should be read as `major.minor.patch`,
wherein all versions with the same `major` version are backwards binary-compatible,
with the exception of `@ApiMayChange`, `@InternalApi` or `@DoNotInherit` marked APIs 
or other specifically documented special-cases.

For more information and a detailed discussion of these rules and guarantees please refer to
@extref:[The @DoNotInherit and @ApiMayChange markers](akka-docs:common/binary-compatibility-rules.html#The_@DoNotInherit_and_@ApiMayChange_markers).

## Specific versions inter-op discussion

In this section we discuss some of the specific cases of compatibility between versions of Akka HTTP and Akka itself.

For example, you may be interested in those examples if you encountered the following exception in your system when upgrading parts 
of your libraries: `Detected java.lang.NoSuchMethodError error, which MAY be caused by incompatible Akka versions on the classpath. Please note that a given Akka version MUST be the same across all modules of Akka that you are using, e.g. if you use akka-actor [2.5.3 (resolved from current classpath)] all other core Akka modules MUST be of the same version. External projects like Alpakka, Persistence plugins or Akka HTTP etc. have their own version numbers - please make sure you're using a compatible set of libraries.`

### Akka HTTP 10.0.x with Akka 2.5.x

Akka HTTP 10.0.x is (binary) compatible with *both* Akka `2.4.x` as well as Akka `2.5.x`, however in order to facilitate 
this the build (and thus released artifacts) depend on the `2.4` series. Depending on how you structure your dependencies,
you may encounter a situation where you depended on `akka-actor` of the `2.5` series, and you depend on `akka-http`
from the `10.0` series, which in turn would transitively pull in the `akka-streams` dependency in version `2.4` which 
breaks the binary compatibility requirement that all Akka modules must be of the same version, so the `akka-streams` 
dependency MUST be the same version as `akka-actor` (so the exact version from the `2.5` series).

In order to resolve this dependency issue, you must depend on akka-streams explicitly, and make it the same version as
the rest of your Akka environment, for example like this:

sbt
:   @@@vars
    ```
    val akkaVersion = "$akka25.version$"
    val akkaHttpVersion = "$project.version$"
    libraryDependencies += "com.typesafe.akka" %% "akka-http"   % akkaHttpVersion
    libraryDependencies += "com.typesafe.akka" %% "akka-actor"  % akkaVersion
    libraryDependencies += "com.typesafe.akka" %% "akka-stream" % akkaVersion
    
    // If testkit used, explicitly declare dependency on akka-streams-testkit in same version as akka-actor
    libraryDependencies += "com.typesafe.akka" %% "akka-http-testkit"   % akkaHttpVersion % Test
    libraryDependencies += "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion     % Test
    ```
    @@@

Gradle
:   @@@vars
    ```
    compile group: 'com.typesafe.akka', name: 'akka-http_$scala.binary_version$', version: '$project.version$'
    compile group: 'com.typesafe.akka', name: 'akka-actor_$scala.binary_version$', version: '$akka25.version$'
    compile group: 'com.typesafe.akka', name: 'akka-stream_$scala.binary_version$', version: '$akka25.version$'
    
    // If testkit used, explicitly declare dependency on akka-streams-testkit in same version as akka-actor
    testCompile group: 'com.typesafe.akka', name: 'akka-http-testkit_$scala.binary_version$', version: '$project.version$'
    testCompile group: 'com.typesafe.akka', name: 'akka-stream-testkit_$scala.binary_version$', version: '$akka25.version$'
    ```
    @@@
    
Maven
:   @@@vars
    ```
    <dependency>
      <groupId>com.typesafe.akka</groupId>
      <artifactId>akka-actor_$scala.binary_version$</artifactId>
      <version>2.5.[...]</version>
    </dependency>
    <!-- Explicitly depend on akka-streams in same version as akka-actor-->
    <dependency>
      <groupId>com.typesafe.akka</groupId>
      <artifactId>akka-stream_$scala.binary_version$</artifactId>
      <version>$akka25.version$</version>
    </dependency>
    <dependency>
      <groupId>com.typesafe.akka</groupId>
      <artifactId>akka-http_$scala.binary_version$</artifactId>
      <version>$akka25.version$</version>
    </dependency>

    <!-- If testkit used, explicitly declare dependency on akka-streams-testkit in same version as akka-actor-->
    <dependency>
      <groupId>com.typesafe.akka</groupId>
      <artifactId>akka-http-testkit_$scala.binary_version$</artifactId>
      <version>$akka25.version$</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.typesafe.akka</groupId>
      <artifactId>akka-stream-testkit_$scala.binary_version$</artifactId>
      <version>$akka25.version$</version>
      <scope>test</scope>
    </dependency>
    ```
    @@@
