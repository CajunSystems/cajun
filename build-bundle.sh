#!/bin/bash

# Load environment variables from .env file
if [ -f .env ]; then
    source .env
fi

# Clean old build artifacts to avoid bundling multiple versions
echo "Cleaning old build artifacts..."
./gradlew clean

# Create bundles for both cajun and cajun-test modules
echo "Creating bundles for cajun and cajun-test modules..."
./gradlew :lib:createCentralBundle :test-utils:createCentralBundle \
    -Psigning.keyId="$SIGNING_KEY_ID" \
    -Psigning.password="$SIGNING_PASSWORD" \
    -Psigning.secretKeyRingFile="$SIGNING_SECRET_KEY_RING_FILE" \
    --no-configuration-cache "$@"

echo ""
echo "✅ Bundles created successfully:"
echo "   - lib/build/distributions/cajun-*-bundle.zip"
echo "   - test-utils/build/distributions/cajun-test-*-bundle.zip"
