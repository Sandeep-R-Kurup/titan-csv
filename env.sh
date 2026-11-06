# Usage: source env.sh
# Puts a Java 8 JDK (required by Titan 1.0) and Maven on PATH for this shell.
# Looks for, in order: an installed Java 8 known to macOS, then a JDK 8 / Maven unpacked under ~/.jdks.

if JH=$(/usr/libexec/java_home -v 1.8 2>/dev/null); then
  export JAVA_HOME="$JH"
else
  for d in "$HOME"/.jdks/zulu8*/Contents/Home "$HOME"/.jdks/*jdk8*/Contents/Home "$HOME"/.jdks/*jdk8*; do
    if [ -x "$d/bin/java" ]; then export JAVA_HOME="$d"; break; fi
  done
fi

if [ -z "$JAVA_HOME" ]; then
  echo "Java 8 not found. Install one, e.g.  brew install --cask zulu@8   (see README)" >&2
else
  export PATH="$JAVA_HOME/bin:$PATH"
fi

for m in "$HOME"/.jdks/apache-maven-*/bin; do
  [ -x "$m/mvn" ] && export PATH="$m:$PATH" && break
done
command -v mvn >/dev/null || echo "Maven not found. Install it, e.g.  brew install maven" >&2

java -version 2>&1 | head -1
