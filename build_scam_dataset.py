from pathlib import Path
import json
import random
import re
import itertools
import csv
import urllib.request


SMS_URL = "https://archive.ics.uci.edu/ml/machine-learning-databases/00228/smsspamcollection.zip"
SMS_ZIP_PATH = Path("/tmp/sms_spam.zip")


def normalize_text(text):
    return re.sub(r"\s+", " ", text).strip()


def download_sms_zip(url, dest_path):
    dest_path.parent.mkdir(parents=True, exist_ok=True)
    urllib.request.urlretrieve(url, dest_path)


def parse_sms_spam_from_zip(zip_path):
    zipfile_mod = __import__("zipfile")

    with zipfile_mod.ZipFile(zip_path, "r") as zf:
        members = [name for name in zf.namelist() if name.lower().endswith("smsspamcollection")]
        if not members:
            raise RuntimeError("SMSSpamCollection file not found in archive")
        raw_lines = zf.read(members[0]).decode("utf-8", errors="replace").splitlines()

    records = []
    reader = csv.reader(raw_lines, delimiter="\t")
    for row in reader:
        if not row:
            continue
        if len(row) < 2:
            continue
        label_raw = row[0].strip().lower()
        message = normalize_text("\t".join(row[1:]))
        if not message:
            continue

        is_spam = label_raw == "spam"
        records.append(
            {
                "context": "",
                "response": message,
                "label": 1 if is_spam else 0,
                "category": "SMS_SPAM" if is_spam else "BENIGN",
            }
        )
    return records


def category_specs():
    return {
        "OTP_PHISHING": {
            "label": 1,
            "templates": [
                "Dear customer your {bank} account OTP is {otp}. Share with our agent {phone} to avoid suspension.",
                "Your {bank} KYC expires today. Share OTP {otp} with helpline {phone} immediately.",
                "URGENT: {bank} account blocked. Call {phone} and share OTP to reactivate.",
            ],
            "slots": {
                "bank": lambda: random.choice(["SBI", "HDFC", "ICICI", "Paytm", "PhonePe", "Axis Bank"]),
                "otp": lambda: f"{random.randint(100000, 999999)}",
                "phone": lambda: f"+91{random.randint(7000000000, 9999999999)}",
            },
        },
        "UPI_FRAUD": {
            "label": 1,
            "templates": [
                "Send ₹{amount} to UPI {upi} to claim your {prize} reward. Offer expires in {minutes} minutes.",
                "You won ₹{amount}! Transfer ₹{fee} processing fee to {upi} to receive prize.",
                "URGENT: Pay ₹{amount} to {upi} before {time} or your account will be suspended.",
            ],
            "slots": {
                "amount": lambda: random.randint(100, 50000),
                "upi": lambda: f"agent{random.randint(100, 999)}@{random.choice(['paytm', 'ybl', 'okaxis'])}",
                "prize": lambda: random.choice(["iPhone 15", "₹1 Lakh", "Gold Coin", "Car"]),
                "minutes": lambda: random.randint(5, 30),
                "fee": lambda: random.randint(99, 999),
                "time": lambda: random.choice(["2 PM", "5 PM", "midnight", "12 PM"]),
            },
        },
        "AUTHORITY_SCAM": {
            "label": 1,
            "templates": [
                "This is {agency}. An FIR has been filed against your Aadhaar {aadhaar}. Call {phone} immediately.",
                "Income Tax Department: Arrest warrant issued for PAN {pan}. Pay ₹{amount} to avoid arrest.",
                "CBI notice: Your account linked to money laundering. Contact officer {phone} in {hours} hours.",
                "Sir, aapke naam par ek complaint aayi hai investigation department se. Seriously baat hai, please cooperate otherwise consequences bahut bhaari honge. Verification ke liye ek code aayega usse share karein jaldi.",
                "Verification pending from your side. Delay will result in automatic closure of access.",
                "Bhai sun, ek important baat hai. Teri taraf se verification nahi hua. Agar abhi nahi kiya toh problem ho jayega. Simple sa process hai, bas ek message aayega usko forward kar dena.",
                "Sir investigation chal rahi hai aapke against, statement aur code confirm karo.",
                "Aapka number illegal activities me use ho raha hai, immediately verification code bhejo warna police aayegi.",
                "Official department se enquiry aayi hai, apna last text me aaya hua code confirm karein abhi.",
                "Apke naam pe complaint darj hui hai cyber branch me. Bachna hai to OTP provide karo for clearance."
            ],
            "slots": {
                "agency": lambda: random.choice(["CBI", "RBI", "Income Tax Department", "Cybercrime Cell", "ED"]),
                "aadhaar": lambda: f"{random.randint(1000, 9999)} {random.randint(1000, 9999)} {random.randint(1000, 9999)}",
                "pan": lambda: (
                    f"{''.join(random.choices('ABCDEFGHIJKLMNOPQRSTUVWXYZ', k=5))}"
                    f"{random.randint(1000, 9999)}"
                    f"{''.join(random.choices('ABCDEFGHIJKLMNOPQRSTUVWXYZ', k=1))}"
                ),
                "phone": lambda: f"+91{random.randint(7000000000, 9999999999)}",
                "amount": lambda: random.randint(5000, 500000),
                "hours": lambda: random.randint(1, 6),
            },
        },
        "FINANCIAL_FRAUD": {
            "label": 1,
            "templates": [
                "Earn ₹{daily} daily from home. Invest ₹{invest} in our crypto scheme. WhatsApp {phone}.",
                "Double your money in {days} days. Send ₹{amount} to {upi}. {count} people already earning.",
                "Work from home: Like {count} YouTube videos, earn ₹{earn} per task. Join via {link}.",
            ],
            "slots": {
                "daily": lambda: random.randint(500, 5000),
                "invest": lambda: random.randint(1000, 50000),
                "phone": lambda: f"+91{random.randint(7000000000, 9999999999)}",
                "days": lambda: random.randint(7, 30),
                "amount": lambda: random.randint(1000, 10000),
                "upi": lambda: f"profit{random.randint(100, 999)}@paytm",
                "count": lambda: random.randint(50, 5000),
                "earn": lambda: random.randint(200, 2000),
                "link": lambda: f"bit.ly/{''.join(random.choices('abcdefghijklmnopqrstuvwxyz0123456789', k=8))}",
            },
        },
        "GIFT_CARD_SCAM": {
            "label": 1,
            "templates": [
                "Congratulations! You won a {prize}. Buy a ₹{amount} Amazon gift card and share the code at {phone}.",
                "Your KBC lucky draw number is {number}. Claim ₹{prize} by sending gift card code to {phone}.",
                "BSNL reward: Send iTunes gift card worth ₹{amount} to claim your free {data}GB data.",
            ],
            "slots": {
                "prize": lambda: random.choice(["iPhone 15 Pro", "₹25,000", "Samsung TV", "₹1 Lakh cash"]),
                "amount": lambda: random.randint(500, 5000),
                "phone": lambda: f"+91{random.randint(7000000000, 9999999999)}",
                "number": lambda: f"KBC{random.randint(10000, 99999)}",
                "data": lambda: random.randint(10, 100),
            },
        },
        "BENIGN_INDIA": {
            "label": 0,
            "templates": [
                "Hi {name}, your order #{order} has been shipped. Expected delivery {day}.",
                "Your {bank} account has been credited with ₹{amount}. Available balance: ₹{balance}.",
                "OTP for login to {service} is {otp}. Valid for 10 minutes. Do not share.",
                "Dear {name}, your appointment is confirmed for {day} at {time}. Reply CANCEL to cancel.",
                "Reminder: Your {bill} bill of ₹{amount} is due on {day}. Pay at {link}.",
            ],
            "slots": {
                "name": lambda: random.choice(["Rahul", "Priya", "Amit", "Sneha", "Vikram", "Ananya"]),
                "order": lambda: f"ORD{random.randint(100000, 999999)}",
                "day": lambda: random.choice(["Monday", "Tuesday", "Wednesday", "Thursday", "Friday"]),
                "bank": lambda: random.choice(["SBI", "HDFC", "ICICI", "Axis"]),
                "amount": lambda: random.randint(100, 50000),
                "balance": lambda: random.randint(1000, 100000),
                "service": lambda: random.choice(["Netflix", "Amazon", "Swiggy", "Zomato", "IRCTC"]),
                "otp": lambda: random.randint(100000, 999999),
                "time": lambda: random.choice(["10:00 AM", "2:00 PM", "4:30 PM"]),
                "bill": lambda: random.choice(["electricity", "mobile", "broadband", "gas"]),
                "link": lambda: random.choice(["paytm.com/pay", "phonepe.com/bill"]),
            },
        },
    }


def render_template(template, slots):
    placeholders = re.findall(r"{(\w+)}", template)
    values = {}
    for key in placeholders:
        if key not in values:
            values[key] = str(slots[key]())
    return template.format(**values)


def generate_synthetic_single_turns(specs, per_category=2000):
    samples = []
    for category, meta in specs.items():
        templates = meta["templates"]
        slots = meta["slots"]
        label = meta["label"]

        for _ in range(per_category):
            template = random.choice(templates)
            message = normalize_text(render_template(template, slots))
            samples.append(
                {
                    "context": "",
                    "response": message,
                    "label": label,
                    "category": category,
                }
            )
    return samples


def build_scam_dialogue(specs):
    scam_categories = [
        "OTP_PHISHING",
        "UPI_FRAUD",
        "AUTHORITY_SCAM",
        "FINANCIAL_FRAUD",
        "GIFT_CARD_SCAM",
    ]
    confused = [
        "What?",
        "Who is this?",
        "I don't understand",
        "Which bank?",
        "Are you sure?",
        "Please explain",
    ]
    pressure = [
        "This is urgent, act immediately or face consequences.",
        "Your account will be blocked in 30 minutes.",
        "Our officer will visit your home if you don't comply.",
        "This is your final warning before legal action.",
    ]
    hesitation = [
        "Let me check with my bank first.",
        "I need to ask my family.",
        "Can you call back later?",
        "This seems suspicious.",
    ]
    final_push = [
        "There is no time. Act now or lose everything.",
        "Do not tell anyone, this is confidential.",
        "Your family cannot help. Only we can resolve this.",
        "Calling back is not possible. Decide now.",
    ]

    category = random.choice(scam_categories)
    opening = render_template(
        random.choice(specs[category]["templates"]),
        specs[category]["slots"],
    )

    turns = [
        ("[THEM]", normalize_text(opening)),
        ("[YOU]", random.choice(confused)),
        ("[THEM]", random.choice(pressure)),
    ]

    if random.random() < 0.5:
        turns.append(("[YOU]", random.choice(hesitation)))
        turns.append(("[THEM]", random.choice(final_push)))

    context = "\n".join(f"{speaker} {text}" for speaker, text in turns[:-1])
    response = turns[-1][1]

    return {
        "context": context,
        "response": response,
        "label": 1,
        "category": category,
    }


def build_benign_dialogue(specs):
    acknowledgements = [
        "Ok thanks",
        "Got it",
        "Thank you",
        "Noted",
        "Sure",
        "Understood",
    ]
    closings = [
        "Have a great day!",
        "Let us know if you need help.",
        "Thank you for using our service.",
        "Your request has been processed.",
    ]

    opening = render_template(
        random.choice(specs["BENIGN_INDIA"]["templates"]),
        specs["BENIGN_INDIA"]["slots"],
    )

    turns = [
        ("[THEM]", normalize_text(opening)),
        ("[YOU]", random.choice(acknowledgements)),
        ("[THEM]", random.choice(closings)),
    ]

    return {
        "context": "\n".join(f"{speaker} {text}" for speaker, text in turns[:-1]),
        "response": turns[-1][1],
        "label": 0,
        "category": "BENIGN_INDIA",
    }


def generate_multi_turn_dialogues(specs, scam_count=500, benign_count=500):
    scam_samples = [build_scam_dialogue(specs) for _ in range(scam_count)]
    benign_samples = [build_benign_dialogue(specs) for _ in range(benign_count)]
    return scam_samples + benign_samples


def write_jsonl(path, records):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for record in records:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")


def summarize(records):
    total = len(records)
    scam = sum(1 for r in records if r["label"] == 1)
    benign = sum(1 for r in records if r["label"] == 0)
    multi_turn = sum(1 for r in records if r["context"] != "")

    print(f"Total samples: {total}")
    print(f"Scam samples: {scam}")
    print(f"Benign samples: {benign}")
    print(f"Multi-turn samples: {multi_turn}")
    print("Output: dataset/scam_dialogues.jsonl")


def main():
    base_dir = Path(__file__).resolve().parent
    output_path = base_dir / "dataset" / "scam_dialogues.jsonl"

    download_sms_zip(SMS_URL, SMS_ZIP_PATH)
    part1 = parse_sms_spam_from_zip(SMS_ZIP_PATH)

    specs = category_specs()
    part2 = generate_synthetic_single_turns(specs, per_category=2000)
    part3 = generate_multi_turn_dialogues(specs, scam_count=5000, benign_count=5000)

    records = list(itertools.chain(part1, part2, part3))
    random.seed(42)
    random.shuffle(records)

    write_jsonl(output_path, records)
    summarize(records)


if __name__ == "__main__":
    main()
