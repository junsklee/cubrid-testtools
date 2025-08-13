#!/bin/bash

# Download Kubernetes client libraries for Builder-Tester

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
LIB_DIR="$( cd "$SCRIPT_DIR/../lib" && pwd )"

echo "Downloading Kubernetes client libraries to $LIB_DIR..."

# Fabric8 Kubernetes Client version 6.9.2
FABRIC8_VERSION="6.9.2"
MAVEN_REPO="https://repo1.maven.org/maven2"

# Core libraries needed
declare -a LIBS=(
    "io/fabric8/kubernetes-client/$FABRIC8_VERSION/kubernetes-client-$FABRIC8_VERSION.jar"
    "io/fabric8/kubernetes-client-api/$FABRIC8_VERSION/kubernetes-client-api-$FABRIC8_VERSION.jar"
    "io/fabric8/kubernetes-model-core/$FABRIC8_VERSION/kubernetes-model-core-$FABRIC8_VERSION.jar"
    "io/fabric8/kubernetes-model-common/$FABRIC8_VERSION/kubernetes-model-common-$FABRIC8_VERSION.jar"
    "io/fabric8/kubernetes-model-batch/$FABRIC8_VERSION/kubernetes-model-batch-$FABRIC8_VERSION.jar"
    "com/fasterxml/jackson/core/jackson-core/2.15.2/jackson-core-2.15.2.jar"
    "com/fasterxml/jackson/core/jackson-databind/2.15.2/jackson-databind-2.15.2.jar"
    "com/fasterxml/jackson/core/jackson-annotations/2.15.2/jackson-annotations-2.15.2.jar"
    "com/squareup/okhttp3/okhttp/3.14.9/okhttp-3.14.9.jar"
    "com/squareup/okio/okio/1.17.2/okio-1.17.2.jar"
    "org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar"
    "org/slf4j/slf4j-simple/2.0.9/slf4j-simple-2.0.9.jar"
)

cd "$LIB_DIR"

for lib in "${LIBS[@]}"; do
    filename=$(basename "$lib")
    if [ -f "$filename" ]; then
        echo "Already exists: $filename"
    else
        echo "Downloading: $filename"
        curl -sL -o "$filename" "$MAVEN_REPO/$lib"
        if [ $? -eq 0 ]; then
            echo "  Downloaded: $filename"
        else
            echo "  Failed to download: $filename"
        fi
    fi
done

echo "Download complete!"
echo "Libraries are in: $LIB_DIR"
