#!/bin/bash

# Load environment variables from .env file
if [ -f .env ]; then
    source .env
fi

# Clean old build artifacts to avoid bundling multiple versions
echo "Cleaning old build artifacts..."
./gradlew clean

# Run gradle with properties passed as command line arguments
./gradlew createCentralBundle \
    -Psigning.keyId="$SIGNING_KEY_ID" \
    -Psigning.password="$SIGNING_PASSWORD" \
    -Psigning.secretKeyRingFile="$SIGNING_SECRET_KEY_RING_FILE" \
    --no-configuration-cache "$@"
