# Environment setup

## Java and Git

Use JDK 21 to run Gradle and the installed CLI. Set `JAVA_HOME` to your JDK
installation and put its `bin` directory on `PATH`. Confirm with `java -version`,
`git --version`, and `.\\gradlew.bat --version` on Windows (or
`bash ./gradlew --version` on Linux/macOS).

For example, in PowerShell, replace the path with your installation:

```powershell
$env:JAVA_HOME = 'C:/path/to/jdk-21'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
.\\gradlew.bat check :server:spike:installDist
```

A persistent alternative is `org.gradle.java.home=C:/path/to/jdk-21` in your
**user-level** `~/.gradle/gradle.properties`. Do not commit it to this repository.
Use forward slashes in properties files; backslashes are escape characters.

Gradle toolchain provisioning selects the compilation JDK; it does not repair
an obsolete JVM used to launch Gradle. The original development machine's old
JDK 11 also failed TLS negotiation with Maven Central.

Git must be on `PATH`. Integration tests use temporary local repositories and
Java fixture processes. They do not invoke Claude Code or access remote repos.

## Android SDK

Only `android/app` needs the SDK; it is skipped when neither `ANDROID_HOME`,
`ANDROID_SDK_ROOT`, nor `sdk.dir` in `local.properties` points at one, so server
work still needs no SDK. Android Studio is not required. Command-line install
without root (Linux):

```sh
SDK=$HOME/Android/Sdk; mkdir -p $SDK/cmdline-tools
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
python3 -m zipfile -e commandlinetools-linux-11076708_latest.zip /tmp/clt
mv /tmp/clt/cmdline-tools $SDK/cmdline-tools/latest && chmod +x $SDK/cmdline-tools/latest/bin/*
yes | $SDK/cmdline-tools/latest/bin/sdkmanager --sdk_root=$SDK --licenses
$SDK/cmdline-tools/latest/bin/sdkmanager --sdk_root=$SDK "platforms;android-35" "build-tools;35.0.0" "platform-tools"
echo "sdk.dir=$SDK" >> local.properties
```

Running the license step accepts the Android SDK License Agreement. With the SDK
configured, `check` also runs the app's lint and unit tests.

## Claude Code

Install and authenticate Claude Code separately for live runs. The harness
checks `claude --version` before starting. It closes stdin and passes an explicit
permission mode. The default is `acceptEdits`; `bypass` disables the provider's
permission checks and should only be chosen deliberately.

Use a long absolute Windows path for `--work-root`. Claude Code can reject
8.3 paths such as `C:\\Users\\PHILIP~1\\...` as suspicious. A worktree is not an
OS security boundary.

## Validation and output

Run `check` for tests and Detekt; run `:server:spike:installDist` to package
the CLI. See the root README for Windows and Unix commands. CI runs both systems.

Money is formatted with `Locale.ROOT`, independent of the machine's locale.
Output is sanitized before events reach the CLI, and failed or interrupted runs
clean up their worktrees unless `--keep-worktree true` was selected.
