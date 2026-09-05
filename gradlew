#!/bin/sh
#
# Gradle startup script for POSIX
#
PRG="$0"
while [ -h "$PRG" ] ; do
    ls=$(ls -ld "$PRG")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=$(dirname "$PRG")/"$link"
    fi
done
SAVED="$(pwd)"
cd "$(dirname "$PRG")/" >/dev/null
APP_HOME="$(pwd -P)"
cd "$SAVED" >/dev/null

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
        echo "Please set the JAVA_HOME variable."
        exit 1
    fi
else
    JAVACMD="java"
    if ! command -v java >/dev/null 2>&1 ; then
        echo "ERROR: JAVA_HOME is not set and no 'java' command found."
        exit 1
    fi
fi

# Build classpath from all gradle-8.5 lib JARs
CLASSPATH=""
for jar in gradle-8.5/lib/*.jar gradle-8.5/lib/plugins/*.jar; do
    if [ -f "$jar" ]; then
        if [ -z "$CLASSPATH" ]; then
            CLASSPATH="$jar"
        else
            CLASSPATH="$CLASSPATH:$jar"
        fi
    fi
done

exec "$JAVACMD" -classpath "$CLASSPATH" org.gradle.launcher.Main "$@"
