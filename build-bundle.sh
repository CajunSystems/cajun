#!/bin/bash

# Load environment variables from .env file
if [ -f .env ]; then
    source .env
fi

# Run gradle with properties passed as command line arguments
./gradlew createCentralBundle \
    -Psigning.keyId="$SIGNING_KEY_ID" \
    -Psigning.password="$SIGNING_PASSWORD" \
    -Psigning.secretKeyRingFile="$SIGNING_SECRET_KEY_RING_FILE" \
    --no-configuration-cache "$@"
