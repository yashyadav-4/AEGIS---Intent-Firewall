# Traverse Server Integration Plan

## Current State
- Server exists: traverse_server.py ✅
- App integration: None ❌

## Required Work
1. Add network layer in RN to call server
2. Implement HMAC key exchange (securely)
3. Add UI for opt-in to federated reporting
4. Handle offline mode gracefully

## Security Considerations
- Cannot hardcode HMAC secret in app
- Need key exchange or asymmetric approach
- Consider: Serverless function with API key

## Recommendation
- Defer to v2.0
- On-device detection is valuable standalone
