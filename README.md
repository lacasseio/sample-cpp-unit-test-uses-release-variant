The repository showcases a plugin designed to shift testing focus from debug to release binaries.
This plugin enhances the native `cpp-unit-test` Gradle plugin, allowing it to target the release variant of the main component.
The shift is achieved using a straightforward extension method:

```
testsAgainst.release()
```

When this method is called, it reconfigures the build process to modify the object file linkage for the `CppTestExecutable`.
As a result, the unit test will link against release binary's object files.

The `app` subproject is configured to test against the release build type, as indicated by the `compileReleaseCpp` task.
Conversely, the `lib` subproject remains with the default settings, performing tests against the debug build type, as shown by the `compileDebugCpp` task.
```
$ ./gradlew test
> Task :app:compileTestCpp
> Task :lib:compileDebugCpp
> Task :app:compileReleaseCpp
> Task :app:relocateMainForTest
> Task :lib:compileTestCpp
> Task :app:linkTest
> Task :lib:linkTest
> Task :lib:installTest
> Task :app:installTest
> Task :app:runTest
> Task :lib:runTest
> Task :app:test
> Task :lib:test

BUILD SUCCESSFUL
```