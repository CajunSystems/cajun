#!/bin/bash

# Load environment variables from .env file
if [ -f .env ]; then
    source .env
fi

# Clean old build artifacts to avoid bundling multiple versions
echo "Cleaning old build artifacts..."
./gradlew clean

# Create bundles for all modules
echo "Creating bundles for all Cajun modules..."
./gradlew \
    :cajun-core:createCentralBundle \
    :cajun-mailbox:createCentralBundle \
    :cajun-persistence:createCentralBundle \
    :cajun-cluster:createCentralBundle \
    :cajun-system:createCentralBundle \
    :lib:createCentralBundle \
    :test-utils:createCentralBundle \
    -Psigning.keyId="$SIGNING_KEY_ID" \
    -Psigning.password="$SIGNING_PASSWORD" \
    -Psigning.secretKeyRingFile="$SIGNING_SECRET_KEY_RING_FILE" \
    --no-configuration-cache "$@"

echo ""
echo "✅ Bundles created successfully:"
echo "   - cajun-core/build/distributions/cajun-core-*-bundle.zip"
echo "   - cajun-mailbox/build/distributions/cajun-mailbox-*-bundle.zip"
echo "   - cajun-persistence/build/distributions/cajun-persistence-*-bundle.zip"
echo "   - cajun-cluster/build/distributions/cajun-cluster-*-bundle.zip"
echo "   - cajun-system/build/distributions/cajun-system-*-bundle.zip"
echo "   - lib/build/distributions/cajun-*-bundle.zip"
echo "   - test-utils/build/distributions/cajun-test-*-bundle.zip"
