"""
# Aegis-Zero Traverse Server
# Herd immunity via SimHash + differential privacy
# Reputation-weighted consensus gate (min 3 devices)
# Run: python traverse_server.py  (listens on :5001)
# Env: TRAVERSE_HMAC_SECRET=<secret>
"""

from flask import Flask, Response, request
import hashlib
import hmac
import math
import time
import json
import threading
import collections
import secrets
import struct
import os


EPOCH_DURATION_SECONDS = 3600
BLOOM_CAPACITY = 10000
BLOOM_ERROR_RATE = 0.01
CONSENSUS_MIN_DEVICES = 3
LAPLACE_EPSILON = 1.0
MAX_SYNC_BYTES = 15360
SERVER_PORT = 5001  # 5000 taken by macOS Control Center on M1
HMAC_SECRET = os.environ.get("TRAVERSE_HMAC_SECRET", "aegis-zero-dev-secret")


class BloomFilter:
    def __init__(self, capacity, error_rate):
        if capacity <= 0:
            raise ValueError("capacity must be > 0")
        if not (0 < error_rate < 1):
            raise ValueError("error_rate must be in (0, 1)")

        n = float(capacity)
        p = float(error_rate)
        m = -n * math.log(p) / (math.log(2) ** 2)
        k = (m / n) * math.log(2)

        self.num_bits = max(8, int(math.ceil(m)))
        self.num_hash_functions = max(1, int(round(k)))
        self.bit_array = bytearray((self.num_bits + 7) // 8)

    def _hashes(self, item):
        if not isinstance(item, str):
            raise TypeError("item must be a string")

        item_bytes = item.encode("utf-8")
        positions = []
        for seed in range(self.num_hash_functions):
            seed_bytes = struct.pack(">I", seed)
            digest1 = hashlib.sha256(seed_bytes + item_bytes).digest()
            digest2 = hashlib.md5(item_bytes + seed_bytes).digest()
            h1 = int.from_bytes(digest1[:8], "big")
            h2 = int.from_bytes(digest2, "big")
            positions.append((h1 + seed * h2) % self.num_bits)
        return positions

    def add(self, item):
        for pos in self._hashes(item):
            byte_index = pos // 8
            bit_index = pos % 8
            self.bit_array[byte_index] |= 1 << bit_index

    def contains(self, item):
        for pos in self._hashes(item):
            byte_index = pos // 8
            bit_index = pos % 8
            if (self.bit_array[byte_index] & (1 << bit_index)) == 0:
                return False
        return True

    def to_bytes(self):
        return bytes(self.bit_array)

    def size_bytes(self):
        return len(self.bit_array)


def dataclass(cls):
    fields = list(getattr(cls, "__annotations__", {}).keys())

    def __init__(self, *args, **kwargs):
        if len(args) > len(fields):
            raise TypeError("too many positional arguments")

        values = {}
        for index, value in enumerate(args):
            values[fields[index]] = value

        for key, value in kwargs.items():
            if key not in fields:
                raise TypeError(f"unexpected field: {key}")
            if key in values:
                raise TypeError(f"multiple values for field: {key}")
            values[key] = value

        missing = [name for name in fields if name not in values]
        if missing:
            raise TypeError("missing fields: " + ", ".join(missing))

        for name in fields:
            setattr(self, name, values[name])

    def __repr__(self):
        pairs = ", ".join(f"{name}={getattr(self, name)!r}" for name in fields)
        return f"{cls.__name__}({pairs})"

    cls.__init__ = __init__
    cls.__repr__ = __repr__
    return cls


@dataclass
class ThreatRecord:
    simhash: str
    noisy_label: float
    device_id: str
    epoch: int
    timestamp: float
    hmac_sig: str


class EpochPartition:
    def __init__(self):
        self.bloom = BloomFilter(BLOOM_CAPACITY, BLOOM_ERROR_RATE)
        self.records = []
        self.device_confirmations = collections.defaultdict(set)
        self.created_at = time.time()


def get_current_epoch():
    return int(time.time() // EPOCH_DURATION_SECONDS)


def _uniform_minus_half_to_half():
    # Draw from an open interval to avoid log(0) in Laplace sampling.
    u01 = (secrets.randbits(53) + 0.5) / float(1 << 53)
    return u01 - 0.5


def add_laplace_noise(value, epsilon):
    if epsilon <= 0:
        raise ValueError("epsilon must be > 0")

    u = _uniform_minus_half_to_half()
    if u > 0:
        sign = 1.0
    elif u < 0:
        sign = -1.0
    else:
        sign = 0.0

    noise = -(1.0 / float(epsilon)) * sign * math.log(1.0 - 2.0 * abs(u))
    noisy = float(value) + noise
    if noisy < 0.0:
        return 0.0
    if noisy > 1.0:
        return 1.0
    return noisy


def verify_hmac(device_id, simhash, noisy_label, sig):
    message = f"{device_id}:{simhash}:{noisy_label:.6f}"
    expected = hmac.new(
        HMAC_SECRET.encode(),
        message.encode(),
        hashlib.sha256,
    ).hexdigest()
    return hmac.compare_digest(expected, sig)


app = Flask(__name__)
state_lock = threading.Lock()
epochs = {}
device_reputation = collections.defaultdict(lambda: 1.0)

def compute_consensus(simhash, epoch):
    partition = epochs.get(epoch)
    if partition is None:
        return {
            "confirmed": False,
            "device_count": 0,
            "mean_label": 0.0,
        }

    device_count = len(partition.device_confirmations.get(simhash, set()))
    labels = [r.noisy_label for r in partition.records if r.simhash == simhash]
    if labels:
        mean_label = sum(labels) / float(len(labels))
    else:
        mean_label = 0.0

    return {
        "confirmed": device_count >= CONSENSUS_MIN_DEVICES,
        "device_count": device_count,
        "mean_label": mean_label,
    }


def _json_response(payload, status=200):
    return Response(json.dumps(payload), status=status, mimetype="application/json")


def _is_valid_simhash(simhash):
    if not isinstance(simhash, str) or len(simhash) != 64:
        return False
    hexdigits = "0123456789abcdefABCDEF"
    return all(ch in hexdigits for ch in simhash)


def _parse_epoch(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


@app.post("/submit")
def submit():
    payload = request.get_json(silent=True)
    if not isinstance(payload, dict):
        return _json_response({"error": "invalid JSON body"}, status=400)

    device_id = payload.get("device_id")
    simhash = payload.get("simhash")
    noisy_label_raw = payload.get("noisy_label")
    epoch = _parse_epoch(payload.get("epoch"))
    hmac_sig = payload.get("hmac_sig")

    if not isinstance(device_id, str) or not device_id:
        return _json_response({"error": "invalid device_id"}, status=400)
    if not isinstance(simhash, str):
        return _json_response({"error": "invalid simhash"}, status=400)
    if not isinstance(hmac_sig, str) or not hmac_sig:
        return _json_response({"error": "invalid hmac_sig"}, status=400)
    if epoch is None:
        return _json_response({"error": "invalid epoch"}, status=400)

    try:
        noisy_label = float(noisy_label_raw)
    except (TypeError, ValueError):
        return _json_response({"error": "invalid noisy_label"}, status=400)

    if not verify_hmac(device_id, simhash, noisy_label, hmac_sig):
        return _json_response({"error": "invalid hmac"}, status=401)

    if not _is_valid_simhash(simhash):
        return _json_response({"error": "simhash must be 64-char hex"}, status=400)

    if noisy_label < 0.0 or noisy_label > 1.0:
        return _json_response({"error": "noisy_label must be in [0.0, 1.0]"}, status=400)

    current_epoch = get_current_epoch()
    if epoch not in (current_epoch, current_epoch - 1):
        return _json_response({"error": "epoch must be current or previous"}, status=400)

    server_noisy_label = add_laplace_noise(noisy_label, LAPLACE_EPSILON)

    with state_lock:
        partition = epochs.get(epoch)
        if partition is None:
            partition = EpochPartition()
            epochs[epoch] = partition

        partition.bloom.add(simhash)
        record = ThreatRecord(
            simhash=simhash,
            noisy_label=server_noisy_label,
            device_id=device_id,
            epoch=epoch,
            timestamp=time.time(),
            hmac_sig=hmac_sig,
        )
        partition.records.append(record)
        partition.device_confirmations[simhash].add(device_id)
        device_reputation.setdefault(device_id, 1.0)

        consensus = compute_consensus(simhash, epoch)
        if consensus["confirmed"]:
            current_rep = device_reputation.get(device_id, 1.0)
            device_reputation[device_id] = min(2.0, current_rep + 0.1)

    return _json_response({"status": "ok", "consensus": consensus}, status=200)


@app.get("/sync")
def sync():
    device_id = request.args.get("device_id", type=str)
    if not isinstance(device_id, str) or not device_id:
        return _json_response({"error": "device_id is required"}, status=400)

    since_epoch = request.args.get("since_epoch")
    if since_epoch is None:
        epoch = get_current_epoch()
    else:
        epoch = _parse_epoch(since_epoch)
        if epoch is None:
            return _json_response({"error": "since_epoch must be an integer"}, status=400)

    with state_lock:
        partition = epochs.get(epoch)
        if partition is None:
            # Use a deterministic empty bloom with configured dimensions.
            bloom_bytes = BloomFilter(BLOOM_CAPACITY, BLOOM_ERROR_RATE).to_bytes()
            confirmed_count = 0
            created_at = float(epoch * EPOCH_DURATION_SECONDS)
        else:
            bloom_bytes = partition.bloom.to_bytes()
            confirmed_count = sum(
                1
                for devices in partition.device_confirmations.values()
                if len(devices) >= CONSENSUS_MIN_DEVICES
            )
            created_at = partition.created_at

    truncated = False
    if len(bloom_bytes) > MAX_SYNC_BYTES:
        bloom_bytes = bloom_bytes[:MAX_SYNC_BYTES]
        truncated = True

    response_payload = {
        "epoch": epoch,
        "bloom_hex": bloom_bytes.hex(),
        "confirmed_count": confirmed_count,
        "epoch_start_ts": created_at,
        "size_bytes": len(bloom_bytes),
    }
    if truncated:
        response_payload["truncated"] = True

    return _json_response(response_payload, status=200)


@app.get("/health")
def health():
    with state_lock:
        total_submissions = sum(len(partition.records) for partition in epochs.values())
        active_devices = len(device_reputation)

    return _json_response(
        {
            "status": "ok",
            "epoch": get_current_epoch(),
            "total_submissions": total_submissions,
            "active_devices": active_devices,
        },
        status=200,
    )


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=SERVER_PORT, debug=False, threaded=True)
