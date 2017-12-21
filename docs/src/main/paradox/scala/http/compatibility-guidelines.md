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

### Compatibility with Akka

Akka HTTP 10.1.x is (binary) compatible with Akka `2.5.x`
and future Akka 2.x versions that are released during the lifetime of Akka HTTP 10.1.x.
To facilitate supporting multiple minor versions of Akka we do not depend on `akka-stream`
explicitly but mark it as a `provided` dependency in our build. That means that you will *always* have to add
a manual dependency to `akka-stream`.

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
    compile group: 'com.typesafe.akka', name: 'akka-http_$scala.binary_version$',   version: '$project.version$'
    compile group: 'com.typesafe.akka', name: 'akka-actor_$scala.binary_version$',  version: '$akka25.version$'
    compile group: 'com.typesafe.akka', name: 'akka-stream_$scala.binary_version$', version: '$akka25.version$'
    // If testkit used, explicitly declare dependency on akka-streams-testkit in same version as akka-actor
    testCompile group: 'com.typesafe.akka', name: 'akka-http-testkit_$scala.binary_version$',   version: '$project.version$'
    testCompile group: 'com.typesafe.akka', name: 'akka-stream-testkit_$scala.binary_version$', version: '$akka25.version$'
    ```
    @@@
    
Maven
:   @@@vars
    ```
    <dependency>
      <groupId>com.typesafe.akka</groupId>
      <artifactId>akka-actor_$scala.binary_version$</artifactId>
      <version>$akka25.version$</version>
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
      <version>$project.version$</version>
    </dependency>
    <!-- If testkit used, explicitly declare dependency on akka-streams-testkit in same version as akka-actor-->
    <dependency>
      <groupId>com.typesafe.akka</groupId>
      <artifactId>akka-http-testkit_$scala.binary_version$</artifactId>
      <version>$project.version$</version>
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
