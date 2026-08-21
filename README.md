# Purpose

This repository exists to coordinate the running of OpenDCS and district custom algorithms within our cloud environments.
Everyone is welcome to use it if it meets their needs but the focus is on USACE WaterManagement functions.

If something appears to be very beneficial to the general usage of OpenDCS it should be merged into the main OpenDCS project
at https://github.com/opendcs/opendcs.


# Appstarter

This is a temporary program that will be used to handle multiple offices and compproc instances until such time
as the main OpenDCS can handle that correctly.

# District Algorithms (future)

There will be a location here to either:
1. Compile the provided code (prefered)
2. Reference an artifact somewhere, such as github packages from your own repo
3. Just include the jar(s) (frown upon and we'll likely just decompile it and go back to 1.)

# Development container

The repository includes a development container for GitHub Codespaces or a local
Dev Containers installation. It provides the Java, Gradle, Go, and Docker tooling
used by the algorithm, appstarter, and image builds.

In VS Code, open the repository and run **Dev Containers: Reopen in Container**.
On GitHub, create a Codespace for the repository; the same configuration is used
automatically.

Run the primary checks from the container terminal:

```bash
cd algorithms
./gradlew test

cd ../appstarter
go test ./...

cd ..
docker build --target apps -t opendcs-wm-apps:dev .
```

## Add and test a custom algorithm

1. Create a Gradle subproject under `algorithms/<district>/<project>` and apply
   the `algorithms.deps-conventions` and `algorithms.java-conventions` plugins.
2. Add the subproject to `algorithms/settings.gradle`.
3. Add its project dependency to the appropriate configuration in
   `algorithms/distribution/build.gradle` so its JAR is included in the
   `district-algorithms` distribution.
4. Put implementation code under `src/main/java` and JUnit tests under
   `src/test/java`.
5. Run a focused test while iterating:

   ```bash
   cd algorithms
   ./gradlew :<district>:<project>:test
   ```

Before opening a change, run `./gradlew test` from `algorithms`, `go test ./...`
from `appstarter`, and the Docker build shown above.
