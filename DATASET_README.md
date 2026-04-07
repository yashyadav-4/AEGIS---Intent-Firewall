# Dataset Requirements

## Required Data

### For Text Model Training (Tier 2/3)
- `dataset/scam_dialogues.jsonl` - Existing ✅
- Need to add: benign SMS dataset (recommend: Kaggle SMS Spam Collection)

### For Audio Deepfake Training
- ASVspoof2019 LA protocol files (train, dev, eval)
- Expected location: `dataset/asvspoof2019/`
- Required files:
  - ASVspoof2019.LA.cm.train.protocol.csv
  - ASVspoof2019.LA.cm.dev.protocol.csv
  - ASVspoof2019.LA.cm.eval.protocol.csv
- Audio files: `dataset/asvspoof2019/LA/` (train, dev, eval folders)

### Modern Attack Evaluation
- `modern_attacks/bonafide/` - Placeholder, needs real audio
- `modern_attacks/synthetic_wav/` - Placeholder, needs real audio

## Output Locations

Training outputs go to:
- `models/detectors/` - Detector checkpoints (*.h5)
- `models/dialogrpt/` - Text model (exists ✅)
- `android/app/src/main/assets/` - Exported models for app

## Known Path Issues (FIXME)

1. `distill_wavlm.py` outputs `train_wavlm_targets.npy`
   But `train_wavlm_student.py` expects `train_wavlm_teacher.npy`
   -> Need to standardize naming

2. Some scripts reference ASVspoof2021, others use ASVspoof2019
   -> Current pipeline uses ASVspoof2019 LA
