# Memory Optimization Plan

## Current State
- All models loaded = ~365MB peak
- Too high for older devices

## Optimization Strategies

### Strategy 1: Lazy Loading
- Load Tier 2 only when Tier 1 triggers positive
- Load Tier 3 only when Tier 2 triggers positive
- Unload after 30 seconds of inactivity

### Strategy 2: Model Swapping
- Keep only one detector model in memory at a time
- Swap in/out as needed

### Strategy 3: Reduced Precision
- Current: int8 quantization
- Could try: float16 or dynamic quantization

## Target
- Peak memory: <200MB
- Per-model: ~30-50MB each

## Priority
- Post-launch, based on user crash reports
