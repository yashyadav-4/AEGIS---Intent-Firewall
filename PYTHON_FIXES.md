# Python Training Pipeline Fixes Required

## Issue 1: Mismatched Filenames
- `distill_wavlm.py` outputs: train_wavlm_targets.npy
- `train_wavlm_student.py` expects: train_wavlm_teacher.npy
- FIX: Rename output in distill_wavlm.py OR change input expectation

## Issue 2: ASVspoof Version Mismatch
- Some scripts reference ASVspoof2021
- Others use ASVspoof2019
- FIX: Standardize all to ASVspoof2019 LA

## Issue 3: Missing Module Imports
- check_predictions.py imports 'data_pipeline.model' which doesn't exist
- validate_tflite.py same issue
- FIX: Either create the module or remove the scripts

## Issue 4: Missing Data
- models/detectors/ directory empty
- modern_attacks/ folders empty
- FIX: Run data collection, then training

## Priority
1. Fix filename mismatch (1 hour)
2. Fix ASVspoof version (2 hours)
3. Remove or fix broken scripts (2 hours)
4. Re-run training pipeline (when data available)
