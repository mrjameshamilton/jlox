# jlox

jlox is an optimizing Lox compiler for the JVM. The front-end is 
unmodified from the jlox implementation from the [Crafting Interpreters](https://craftinginterpreters.com/) book.

jlox adds the following:

* An optimizer which performs some basic optimizations like constant folding.
* A compiler that uses [ProGuardCORE](https://github.com/guardsquare/proguard-core) to generate Java class files
 and write an executable jar.

# build and run

The Gradle `build` task will run the Lox tests and build a jar in the lib folder.
The jar can be compiled without running the tests with the `copyJar` task.

A Lox script can be executed by passing the script as the first command line
parameter. An optional second parameter specifies the output jar.

```shell
$ ./gradlew copyJar
$ echo "print \"Hello World\"" > hello.lox
$ bin/jlox hello.lox hello.jar
$ java -jar hello.jar
Hello World
```
