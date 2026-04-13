"""
Aegis Intent Firewall — Expanded Scam Dataset Builder v2
=========================================================
Generates 100K+ samples combining:
  - Original template-based scams (from v1)
  - UCI SMS Spam corpus
  - Multi-turn call transcript scam dialogues (15 archetypes)
  - Adversarial / evasive scam patterns
  - Hard negatives (legitimate messages that look like scams)
  - Regional Hinglish/Hindi patterns
  - Expanded benign corpus

Output: dataset/scam_dialogues_v2.jsonl
"""

from pathlib import Path
import json
import random
import re
import itertools
import csv
import urllib.request

from build_call_transcript_dataset import (
    generate_call_transcripts,
    inject_disfluency,
    apply_whisper_artifacts,
    normalize,
    rphone, ramt, rotp, rname, rbank, rapp, rdept, rcity,
)

SMS_URL = "https://archive.ics.uci.edu/ml/machine-learning-databases/00228/smsspamcollection.zip"
SMS_ZIP_PATH = Path("/tmp/sms_spam.zip")


def normalize_text(text):
    return re.sub(r"\s+", " ", text).strip()


def download_sms_zip(url, dest_path):
    dest_path.parent.mkdir(parents=True, exist_ok=True)
    if not dest_path.exists():
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
        if not row or len(row) < 2:
            continue
        label_raw = row[0].strip().lower()
        message = normalize_text("\t".join(row[1:]))
        if not message:
            continue
        is_spam = label_raw == "spam"
        records.append({
            "context": "",
            "response": message,
            "label": 1 if is_spam else 0,
            "category": "SMS_SPAM" if is_spam else "BENIGN",
        })
    return records


# ============================================================================
# ORIGINAL TEMPLATE-BASED GENERATORS (from v1, expanded categories)
# ============================================================================

def category_specs():
    return {
        "OTP_PHISHING": {
            "label": 1,
            "templates": [
                "Dear customer your {bank} account OTP is {otp}. Share with our agent {phone} to avoid suspension.",
                "Your {bank} KYC expires today. Share OTP {otp} with helpline {phone} immediately.",
                "URGENT: {bank} account blocked. Call {phone} and share OTP to reactivate.",
                "{bank} alert: Suspicious transaction detected. Verify by sharing OTP {otp} at {phone}.",
                "Last warning: Your {bank} account will be permanently deleted. Share OTP {otp} to secure it.",
            ],
            "slots": {
                "bank": lambda: random.choice(["SBI", "HDFC", "ICICI", "Paytm", "PhonePe", "Axis Bank", "PNB", "Kotak"]),
                "otp": lambda: f"{random.randint(100000, 999999)}",
                "phone": rphone,
            },
        },
        "UPI_FRAUD": {
            "label": 1,
            "templates": [
                "Send ₹{amount} to UPI {upi} to claim your {prize} reward. Offer expires in {minutes} minutes.",
                "You won ₹{amount}! Transfer ₹{fee} processing fee to {upi} to receive prize.",
                "URGENT: Pay ₹{amount} to {upi} before {time} or your account will be suspended.",
                "Your cashback of ₹{amount} is ready. Pay ₹{fee} verification charge to {upi} to claim.",
            ],
            "slots": {
                "amount": lambda: random.randint(100, 50000),
                "upi": lambda: f"agent{random.randint(100, 999)}@{random.choice(['paytm', 'ybl', 'okaxis', 'upi'])}",
                "prize": lambda: random.choice(["iPhone 15", "₹1 Lakh", "Gold Coin", "Car", "Laptop"]),
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
                "Sir aapke naam par ek complaint aayi hai investigation department se. Seriously baat hai please cooperate otherwise consequences bahut bhaari honge. Verification ke liye ek code aayega usse share karein jaldi.",
                "Verification pending from your side. Delay will result in automatic closure of access.",
                "Bhai sun ek important baat hai. Teri taraf se verification nahi hua. Agar abhi nahi kiya toh problem ho jayega. Simple sa process hai bas ek message aayega usko forward kar dena.",
                "Sir investigation chal rahi hai aapke against statement aur code confirm karo.",
                "Aapka number illegal activities me use ho raha hai immediately verification code bhejo warna police aayegi.",
                "Official department se enquiry aayi hai apna last text me aaya hua code confirm karein abhi.",
                "Apke naam pe complaint darj hui hai cyber branch me. Bachna hai to OTP provide karo for clearance.",
                "TRAI notice: Aapke naam pe {count} illegal SIM registered hain. Call {phone} within {hours} hour.",
                "Digital arrest warrant issued against {name}. Appear on video call immediately at {phone}.",
            ],
            "slots": {
                "agency": lambda: random.choice(["CBI", "RBI", "Income Tax Department", "Cybercrime Cell", "ED", "TRAI", "Narcotics Bureau"]),
                "aadhaar": lambda: f"{random.randint(1000, 9999)} {random.randint(1000, 9999)} {random.randint(1000, 9999)}",
                "pan": lambda: (
                    f"{''.join(random.choices('ABCDEFGHIJKLMNOPQRSTUVWXYZ', k=5))}"
                    f"{random.randint(1000, 9999)}"
                    f"{''.join(random.choices('ABCDEFGHIJKLMNOPQRSTUVWXYZ', k=1))}"
                ),
                "phone": rphone,
                "amount": lambda: random.randint(5000, 500000),
                "hours": lambda: random.randint(1, 6),
                "count": lambda: random.randint(3, 15),
                "name": rname,
            },
        },
        "FINANCIAL_FRAUD": {
            "label": 1,
            "templates": [
                "Earn ₹{daily} daily from home. Invest ₹{invest} in our crypto scheme. WhatsApp {phone}.",
                "Double your money in {days} days. Send ₹{amount} to {upi}. {count} people already earning.",
                "Work from home: Like {count} YouTube videos, earn ₹{earn} per task. Join via {link}.",
                "Guaranteed {returns}% monthly returns on investment. Minimum ₹{invest}. Contact {phone}.",
            ],
            "slots": {
                "daily": lambda: random.randint(500, 5000),
                "invest": lambda: random.randint(1000, 50000),
                "phone": rphone,
                "days": lambda: random.randint(7, 30),
                "amount": lambda: random.randint(1000, 10000),
                "upi": lambda: f"profit{random.randint(100, 999)}@paytm",
                "count": lambda: random.randint(50, 5000),
                "earn": lambda: random.randint(200, 2000),
                "link": lambda: f"bit.ly/{''.join(random.choices('abcdefghijklmnopqrstuvwxyz0123456789', k=8))}",
                "returns": lambda: random.randint(15, 50),
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
                "phone": rphone,
                "number": lambda: f"KBC{random.randint(10000, 99999)}",
                "data": lambda: random.randint(10, 100),
            },
        },
        "REMOTE_ACCESS_SCAM": {
            "label": 1,
            "templates": [
                "Your device is infected with malware. Install {app} immediately and share the code with our tech team at {phone}.",
                "Aapke phone mein virus hai. {app} download karo aur code share karo. Free service.",
                "Microsoft security alert: Download {app} to fix critical vulnerability. Contact {phone}.",
            ],
            "slots": {
                "app": rapp,
                "phone": rphone,
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
                "{name}, your {service} subscription renews on {day}. Amount: ₹{amount}.",
                "Your flight {flight} to {city} is confirmed for {day}. PNR: {pnr}.",
                "Hi {name}, thank you for your purchase at {store}. Invoice: ₹{amount}.",
            ],
            "slots": {
                "name": rname,
                "order": lambda: f"ORD{random.randint(100000, 999999)}",
                "day": lambda: random.choice(["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]),
                "bank": rbank,
                "amount": lambda: random.randint(100, 50000),
                "balance": lambda: random.randint(1000, 100000),
                "service": lambda: random.choice(["Netflix", "Amazon", "Swiggy", "Zomato", "IRCTC", "Hotstar", "Spotify"]),
                "otp": lambda: random.randint(100000, 999999),
                "time": lambda: random.choice(["10:00 AM", "2:00 PM", "4:30 PM", "11:30 AM"]),
                "bill": lambda: random.choice(["electricity", "mobile", "broadband", "gas", "water"]),
                "link": lambda: random.choice(["paytm.com/pay", "phonepe.com/bill", "bharatbillpay.com"]),
                "flight": lambda: f"{random.choice(['AI', '6E', 'SG', 'UK'])}{random.randint(100, 999)}",
                "city": rcity,
                "pnr": lambda: f"{''.join(random.choices('ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789', k=6))}",
                "store": lambda: random.choice(["Reliance Digital", "Croma", "DMart", "BigBazaar"]),
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


def generate_synthetic_single_turns(specs, per_category=3000):
    samples = []
    for category, meta in specs.items():
        templates = meta["templates"]
        slots = meta["slots"]
        label = meta["label"]
        for _ in range(per_category):
            template = random.choice(templates)
            message = normalize_text(render_template(template, slots))
            samples.append({
                "context": "",
                "response": message,
                "label": label,
                "category": category,
            })
    return samples


# ============================================================================
# ADVERSARIAL / EVASIVE SCAM PATTERNS
# ============================================================================

def generate_adversarial_scams(count=15000):
    """Generate scam messages designed to evade simple pattern matching."""
    samples = []
    generators = [
        _gradual_escalation,
        _codename_substitution,
        _indirect_request,
        _politeness_masking,
        _fragmented_scam,
        _emotional_manipulation,
        _time_pressure_subtle,
        _social_proof_scam,
        _reverse_psychology_scam,
        _context_switching_scam,
    ]
    
    per_gen = count // len(generators)
    for gen in generators:
        for _ in range(per_gen):
            samples.append(gen())
    
    remaining = count - per_gen * len(generators)
    for _ in range(remaining):
        samples.append(random.choice(generators)())
    
    return samples


def _gradual_escalation():
    """Starts benign, gradually introduces scam elements across turns."""
    name = rname()
    bank = rbank()
    
    openings = [
        f"Hello {name}, I hope I'm not disturbing you. Is this a good time to talk?",
        f"Namaste {name} ji, kaise hain aap? Ek choti si baat karni thi.",
        f"Hi {name}, I just wanted to have a quick chat about your account.",
    ]
    
    build_ups = [
        f"I noticed some interesting activity on your {bank} account. Nothing to worry about yet.",
        "We've been reviewing accounts in your area and there are some routine checks needed.",
        f"Your {bank} profile came up during our monthly audit. It's a standard procedure.",
    ]
    
    pivots = [
        "However, to keep your account secure, we do need to verify a few things. Could you help me with that?",
        "The only thing is, we need to confirm your identity. It's very quick, just need you to read me a number.",
        "To complete the review, our system will send you a small verification code. Can you share it when it arrives?",
    ]
    
    escalations = [
        "The system is sending a code now. Just read it to me and we're all done. Very simple.",
        "I understand your concern, but this is standard RBI procedure. The code will expire in 2 minutes.",
        "Sir if you don't verify now, the system will automatically flag your account for manual review which takes 30 days.",
    ]
    
    turns = [
        f"[CALLER] {random.choice(openings)}",
        f"[CALLEE] Yes, go ahead.",
        f"[CALLER] {random.choice(build_ups)}",
        f"[CALLEE] Okay, what's the issue?",
        f"[CALLER] {random.choice(pivots)}",
        f"[CALLEE] What kind of verification?",
        f"[CALLER] {random.choice(escalations)}",
    ]
    
    context = "\n".join(turns[:-1])
    response = turns[-1].split("] ", 1)[1]
    return {"context": context, "response": response, "label": 1, "category": "ADVERSARIAL_GRADUAL"}


def _codename_substitution():
    """Uses euphemisms and codenames instead of obvious scam words."""
    templates = [
        "Sir, please send the 'gift' to our collection point. The 'package' number will be shared shortly.",
        "Aapko ek 'token' milega phone par. Bas wo 'token' hume forward kar dijiye verification ke liye.",
        "We need you to transfer the 'security deposit' to our 'holding account'. It's completely safe.",
        "Could you please share the 'access key' that arrives on your phone? It's for the system update.",
        "Main aapko ek 'reference number' bhejta hoon. Usko note kar ke wapas mujhe bata dijiye.",
        "Please install the 'support tool' on your device. Our team lead will connect remotely to assist.",
        "Aapke 'digital wallet' mein ek 'test transaction' aayegi. Please approve it for verification.",
        "We need a small 'processing amount' to release your funds. It's a standard regulatory requirement.",
    ]
    
    msg = random.choice(templates)
    return {"context": "", "response": msg, "label": 1, "category": "ADVERSARIAL_CODENAME"}


def _indirect_request():
    """Makes requests indirectly, never directly asking for OTP or money."""
    templates = [
        "You know, it would really help if you could just read back whatever message comes to your phone in the next minute.",
        "I'm not asking you to share anything sensitive. Just confirm what the system sends you.",
        "Waise sir, agar aapke phone pe koi message aaye toh bas usmein se numbers bata dena.",
        "I just need you to check your messages and tell me if something arrived. That's all.",
        "Sir, bas itna karna hai ki jo bhi notification aaye usmein jo number likha ho wo bata dena.",
        "We can't proceed without the confirmation. Just let me know when you receive it and what it says.",
        "I understand you're cautious. Let me put it this way — just read out the notification you'll get.",
    ]
    
    msg = random.choice(templates)
    return {"context": "", "response": msg, "label": 1, "category": "ADVERSARIAL_INDIRECT"}


def _politeness_masking():
    """Extremely polite language wrapping malicious intent."""
    templates = [
        "Sir, I completely understand your hesitation and I really appreciate your patience. If you could just kindly share the verification code that our system has sent, I would be most grateful. This is purely for your account safety.",
        "Namaste ji, main samajhta hoon aapko doubt hai. Lekin believe kijiye, hum aapki safety ke liye hi call kar rahe hain. Bas ek choti si madad chahiye — jo code aaya hai wo share kar dijiye.",
        "I want to assure you sir, we take privacy very seriously. This verification is mandated by RBI for your protection. Would you be kind enough to share the confirmation number?",
        "Thank you so much for your time sir, I really appreciate it. Just one last thing — could you please verify the amount we're about to credit by confirming the code on your phone?",
        "Aapka bahut bahut dhanyavaad sir. Hum aapke account ki suraksha ke liye yeh kar rahe hain. Kripya code share karein.",
    ]
    
    msg = random.choice(templates)
    return {"context": "", "response": msg, "label": 1, "category": "ADVERSARIAL_POLITE"}


def _fragmented_scam():
    """Scam intent spread across multiple short messages."""
    patterns = [
        [
            "[THEM] Hi",
            "[THEM] Are you available?",
            "[THEM] I had called earlier from the bank",
            "[THEM] There's a small verification pending",
            "[YOU] What verification?",
            "[THEM] Just a code we sent to your phone",
            "[THEM] Can you read it out?",
        ],
        [
            "[THEM] Namaste",
            "[THEM] Bank se bol raha hoon",
            "[THEM] Ek message aaya hoga",
            "[YOU] Kaun sa message?",
            "[THEM] 6 digit ka",
            "[THEM] Bas wo bata do",
        ],
        [
            "[THEM] Hello",
            "[THEM] Quick question",
            "[THEM] Did you get a notification just now?",
            "[YOU] What notification?",
            "[THEM] From your bank",
            "[THEM] There should be a number in it",
            "[THEM] Just tell me what it says",
        ],
    ]
    
    chosen = random.choice(patterns)
    context = "\n".join(chosen[:-1])
    response = chosen[-1].split("] ", 1)[1] if "] " in chosen[-1] else chosen[-1]
    return {"context": context, "response": response, "label": 1, "category": "ADVERSARIAL_FRAGMENTED"}


def _emotional_manipulation():
    """Uses emotional manipulation to get compliance."""
    templates = [
        "Sir, I really want to help you. If you don't cooperate, I'll have to transfer you to the enforcement department and I don't want that to happen to you.",
        "Please sir, meri job pe aa jayega agar ye verification nahi hua toh. Bas ek baar code share kar dijiye.",
        "I've been trying to help you for the last 30 minutes. Other customers cooperate immediately. Why are you making this difficult?",
        "Aapke bachche ke future ke liye sochiye. Agar account freeze hua toh EMI bounce hogi, CIBIL score kharaab hoga.",
        "Main samajhta hoon aap dar gaye hain. Lekin agar ab nahi karoge toh baad mein bahut mushkil hogi. Main genuinely help karna chahta hoon.",
        "Sir your mother had set up this insurance for you. She would want you to claim it. Please cooperate.",
    ]
    
    msg = random.choice(templates)
    return {"context": "", "response": msg, "label": 1, "category": "ADVERSARIAL_EMOTIONAL"}


def _time_pressure_subtle():
    """Subtle time pressure without obvious urgency words."""
    templates = [
        "I'm just letting you know that this window closes at the end of my shift. After that, you'd have to go through the whole process again.",
        "The system logs out automatically in about 5 minutes. If we don't finish now, you'll need to call back and wait in queue again.",
        "Ye offer sirf aaj ke liye hai. Kal se naya policy lagega aur charges double ho jayenge.",
        "I have other customers waiting sir. If we can finish this quickly, it would be helpful for both of us.",
        "The compliance team reviews these cases twice a month. If we miss this window, next review is in 15 days.",
    ]
    
    msg = random.choice(templates)
    return {"context": "", "response": msg, "label": 1, "category": "ADVERSARIAL_SUBTLE_PRESSURE"}


def _social_proof_scam():
    """Uses social proof to build trust."""
    templates = [
        "Sir, just today I've helped 47 customers complete this same verification. Everyone found it very smooth.",
        "Aapke area mein already 200+ logo ne ye process complete kar liya hai. Bahut simple hai.",
        "Our Google rating is 4.8 stars. You can check our reviews. We're a fully authorized service center.",
        "Your neighbor Mr. Sharma also completed his verification last week. He was also skeptical at first.",
        "We have tie-ups with all major banks. SBI, HDFC, ICICI — sab ke saath kaam karte hain.",
    ]
    
    msg = random.choice(templates)
    return {"context": "", "response": msg, "label": 1, "category": "ADVERSARIAL_SOCIAL_PROOF"}


def _reverse_psychology_scam():
    """Uses reverse psychology to lower guard."""
    templates = [
        "Sir, I completely understand if you don't want to verify. But then I'll have to mark your account as unverified and the system will automatically restrict transactions.",
        "Look, you don't have to do anything. But I must inform you that without verification, your insurance claim of Rs 5 lakhs will be forfeited.",
        "I'm not forcing you sir. It's your choice. But the deadline is today and after that, the special rate won't be available.",
        "Aap nahi karna chahte toh koi baat nahi. Lekin phir mujhe report mein likhna padega ki customer ne cooperate nahi kiya.",
    ]
    
    msg = random.choice(templates)
    return {"context": "", "response": msg, "label": 1, "category": "ADVERSARIAL_REVERSE_PSYCH"}


def _context_switching_scam():
    """Switches between legitimate-sounding and scam topics."""
    patterns = [
        [
            "[THEM] Sir, I'm calling about your electricity bill payment, which shows as cleared. Thank you for that.",
            "[YOU] Yes, I paid it last week.",
            "[THEM] Great. Actually, there's one more thing. Our department is running a verification drive and your connection ID needs re-verification.",
            "[YOU] What do I need to do?",
            "[THEM] Just share the code that comes to your phone. It's for the DISCOM database update.",
        ],
        [
            "[THEM] Hello, your Amazon order has been delivered successfully. We hope you're satisfied.",
            "[YOU] Yes, I got it. Thank you.",
            "[THEM] Glad to hear that. By the way, we noticed you're eligible for our premium rewards program. There's a small activation fee of Rs 299.",
            "[YOU] What rewards?",
            "[THEM] Free deliveries for a year plus 10% cashback on all orders. Just pay Rs 299 to this UPI.",
        ],
    ]
    
    chosen = random.choice(patterns)
    context = "\n".join(chosen[:-1])
    response = chosen[-1].split("] ", 1)[1] if "] " in chosen[-1] else chosen[-1]
    return {"context": context, "response": response, "label": 1, "category": "ADVERSARIAL_CONTEXT_SWITCH"}


# ============================================================================
# HARD NEGATIVES (Look like scams but are legitimate)
# ============================================================================

def generate_hard_negatives(count=20000):
    """Generate legitimate messages that contain scam-like keywords but are benign."""
    samples = []
    generators = [
        _legit_otp_message,
        _legit_bank_notification,
        _legit_customer_service,
        _news_about_scams,
        _legit_kyc_reminder,
        _legit_payment_reminder,
        _legit_govt_message,
        _friend_discussing_scam,
        _legit_insurance_reminder,
        _legit_job_notification,
    ]
    
    per_gen = count // len(generators)
    for gen in generators:
        for _ in range(per_gen):
            samples.append(gen())
    
    remaining = count - per_gen * len(generators)
    for _ in range(remaining):
        samples.append(random.choice(generators)())
    
    return samples


def _legit_otp_message():
    templates = [
        "Your OTP for {service} login is {otp}. Do NOT share this with anyone. Valid for 5 minutes.",
        "{otp} is your one time password for {service}. It expires in 10 minutes. Never share your OTP.",
        "Your verification code is {otp}. If you did not request this, please ignore this message.",
        "{bank}: Your OTP for transaction of Rs {amount} is {otp}. DO NOT share with anyone including bank staff.",
        "OTP {otp} for adding beneficiary on {bank} net banking. If not initiated by you, call {phone}.",
    ]
    msg = random.choice(templates).format(
        service=random.choice(["Amazon", "Swiggy", "Zomato", "Netflix", "PhonePe", "GPay"]),
        otp=rotp(), bank=rbank(), amount=random.randint(100, 50000), phone="1800-XXX-XXXX"
    )
    return {"context": "", "response": msg, "label": 0, "category": "HARD_NEG_OTP"}


def _legit_bank_notification():
    templates = [
        "Dear Customer, Rs {amount} has been debited from your {bank} a/c for {merchant}. If not done by you, call {phone}.",
        "Your {bank} credit card payment of Rs {amount} is due on {date}. Pay now to avoid late fees.",
        "{bank}: Your fixed deposit of Rs {amount} matures on {date}. Visit your branch for renewal options.",
        "Alert: Login to your {bank} account detected from new device. If not you, call {phone} immediately.",
        "{bank} statement: Your account balance as on {date} is Rs {balance}.",
    ]
    msg = random.choice(templates).format(
        amount=random.randint(100, 100000), bank=rbank(),
        merchant=random.choice(["Amazon", "BigBasket", "DMart", "Flipkart"]),
        phone="1800-XXX-XXXX", date=f"{random.randint(1,28)}/{random.randint(1,12)}/2025",
        balance=random.randint(1000, 500000),
    )
    return {"context": "", "response": msg, "label": 0, "category": "HARD_NEG_BANK"}


def _legit_customer_service():
    templates = [
        "Hi {name}, your complaint #{ticket} has been registered. Our team will resolve it within 48 hours.",
        "Thank you for contacting {company} support. Your reference number is {ref}. We'll call you back within 24 hours.",
        "{name}, your refund of Rs {amount} for order #{order} has been initiated. It will reflect in 5-7 business days.",
        "Your {company} account has been successfully updated. If you didn't make this change, contact support immediately.",
    ]
    msg = random.choice(templates).format(
        name=rname(), ticket=random.randint(100000, 999999),
        company=random.choice(["Amazon", "Flipkart", "Swiggy", "Jio", "Airtel"]),
        ref=f"REF{random.randint(10000, 99999)}", amount=random.randint(100, 10000),
        order=f"ORD{random.randint(100000, 999999)}",
    )
    return {"context": "", "response": msg, "label": 0, "category": "HARD_NEG_SUPPORT"}


def _news_about_scams():
    """Meta-discussion about scams (NOT actual scams)."""
    templates = [
        "Be careful of fake calls claiming to be from CBI or ED. Real officers never call and demand money over phone.",
        "Cyber crime helpline: Report online fraud at cybercrime.gov.in or call 1930. Never share OTP with anyone.",
        "RBI advisory: Banks will NEVER ask for your OTP, PIN or card details over phone or SMS.",
        "{bank} alert: Beware of fake KYC update calls. We never ask for OTP or remote access to your phone.",
        "Dosto, ek scam chal raha hai jisme log bank officer banke OTP maangte hain. Please savdhaan rahein.",
        "My uncle almost fell for a digital arrest scam yesterday. These fraudsters are getting really sophisticated.",
        "New scam alert: Fake delivery calls asking for customs duty. Real couriers never ask for advance payment.",
    ]
    msg = random.choice(templates).format(bank=rbank())
    return {"context": "", "response": msg, "label": 0, "category": "HARD_NEG_AWARENESS"}


def _legit_kyc_reminder():
    templates = [
        "Dear {name}, please visit your nearest {bank} branch with Aadhaar and PAN for KYC update before {date}.",
        "{bank}: Your KYC is due for update. Visit any branch with valid ID proof. Do not share documents on call.",
        "Reminder: Complete your {service} KYC at the nearest store. Carry original Aadhaar and recent photo.",
    ]
    msg = random.choice(templates).format(
        name=rname(), bank=rbank(),
        date=f"{random.randint(1,28)}/{random.randint(1,12)}/2025",
        service=random.choice(["Jio", "Airtel", "Vi", "BSNL"]),
    )
    return {"context": "", "response": msg, "label": 0, "category": "HARD_NEG_KYC"}


def _legit_payment_reminder():
    templates = [
        "Hi {name}, your EMI of Rs {amount} for {purpose} is due on {date}. Ensure sufficient balance.",
        "{bank} auto-debit of Rs {amount} scheduled for {date}. Maintain minimum balance to avoid bounce charges.",
        "Rent reminder: Rs {amount} due to {landlord} by {date}. Set up auto-pay for convenience.",
    ]
    msg = random.choice(templates).format(
        name=rname(), amount=random.randint(5000, 50000),
        purpose=random.choice(["home loan", "car loan", "personal loan", "education loan"]),
        date=f"{random.randint(1,28)}/{random.randint(1,12)}/2025",
        bank=rbank(), landlord=rname(),
    )
    return {"context": "", "response": msg, "label": 0, "category": "HARD_NEG_PAYMENT"}


def _legit_govt_message():
    templates = [
        "UIDAI: Your Aadhaar has been successfully updated. If not initiated by you, call 1947.",
        "IRCTC: Your ticket #{pnr} for train {train} on {date} has been confirmed. Boarding: {station}.",
        "CoWIN: Your vaccination certificate is ready for download at cowin.gov.in",
        "EPFO: Your PF contribution of Rs {amount} for {month} has been credited to your UAN account.",
        "DigiLocker: Your driving license has been successfully added. Access anytime at digilocker.gov.in",
    ]
    msg = random.choice(templates).format(
        pnr=f"{''.join(random.choices('0123456789', k=10))}",
        train=f"{random.randint(10000, 99999)}",
        date=f"{random.randint(1,28)}/{random.randint(1,12)}/2025",
        station=random.choice(["New Delhi", "Mumbai Central", "Bangalore City", "Chennai Central"]),
        amount=random.randint(500, 5000),
        month=random.choice(["January", "February", "March", "April", "May", "June"]),
    )
    return {"context": "", "response": msg, "label": 0, "category": "HARD_NEG_GOVT"}


def _friend_discussing_scam():
    """Friends casually discussing financial topics."""
    templates = [
        "Hey, can you transfer Rs {amount} for the dinner last night? My GPay is {upi}.",
        "Bro parking ke liye {amount} dena hai. UPI kar de na.",
        "Kal movie ke tickets book kiye the, tera share Rs {amount} hai. PhonePe kar de.",
        "Dude, my UPI is not working. Can you pay the Ola and I'll give you cash?",
        "Team lunch ka bill split karna hai. Rs {amount} each. Account number bhej raha hoon.",
        "Papa, mujhe Rs {amount} chahiye hostel fees ke liye. GPay pe bhej do please.",
        "Behen, meri taraf se {amount} ka gift voucher le lena {name} ke birthday ke liye. Paise transfer karti hoon.",
    ]
    msg = random.choice(templates).format(
        amount=random.randint(100, 5000),
        upi=f"{rname().lower()}{random.randint(1,99)}@{''.join(random.choices(['paytm','ybl','okaxis'], k=1))}",
        name=rname(),
    )
    return {"context": "", "response": msg, "label": 0, "category": "HARD_NEG_FRIEND_FINANCE"}


def _legit_insurance_reminder():
    templates = [
        "Dear {name}, your {type} insurance policy #{policy} premium of Rs {amount} is due on {date}. Pay at lic.in",
        "{company}: Your vehicle insurance for {vehicle} expires on {date}. Renew online at {link}.",
        "Health insurance renewal reminder: Policy {policy} due for renewal. No claims bonus of {ncb}% applicable.",
    ]
    msg = random.choice(templates).format(
        name=rname(), type=random.choice(["life", "health", "vehicle", "term"]),
        policy=f"POL{random.randint(100000, 999999)}",
        amount=random.randint(5000, 50000),
        date=f"{random.randint(1,28)}/{random.randint(1,12)}/2025",
        company=random.choice(["LIC", "HDFC Life", "ICICI Lombard", "Star Health"]),
        vehicle=random.choice(["Swift Dzire", "Honda City", "Royal Enfield", "Hyundai Creta"]),
        link="policybazaar.com", ncb=random.choice([20, 25, 35, 50]),
    )
    return {"context": "", "response": msg, "label": 0, "category": "HARD_NEG_INSURANCE"}


def _legit_job_notification():
    templates = [
        "Hi {name}, your application for {role} at {company} has been shortlisted. Interview scheduled on {date}.",
        "{company} hiring update: Congratulations! You've cleared Round 1. Please log in to the careers portal for next steps.",
        "Naukri.com: {count} new jobs matching your profile in {city}. Apply now at naukri.com/jobs",
        "LinkedIn: {recruiter} viewed your profile and is interested in connecting about a {role} opportunity.",
    ]
    msg = random.choice(templates).format(
        name=rname(), role=random.choice(["Software Engineer", "Data Analyst", "Product Manager", "HR Executive"]),
        company=random.choice(["TCS", "Infosys", "Wipro", "Google", "Microsoft", "Amazon"]),
        date=f"{random.randint(1,28)}/{random.randint(1,12)}/2025",
        count=random.randint(5, 50), city=rcity(), recruiter=rname(),
    )
    return {"context": "", "response": msg, "label": 0, "category": "HARD_NEG_JOB"}


# ============================================================================
# CURATED REAL-WORLD SCAM EXAMPLES
# ============================================================================

def generate_curated_real_world_scams():
    """High-signal real-world scam samples provided during device testing."""
    samples = [
        "Your Flipkart PayLater account is blocked due to suspicious activity. Share the O T P sent to your mobile to verify your identity and unblock.",
        "Sir, your JioFiber router needs a firmware update or the connection will drop. Please install the TeamViewer QuickSupport app so our technician can do it remotely",
        "Y0ur electricity p0wer will be cut off by 9:30 PM tonight. P1ease update your bil1 right now by clicking here",
        "System Warning: Your WhatsApp account is being logged in from another device. If this was not you, quickly share the 6-digit verification code sent via SMS to block the login.",
        "CBI Notice: Your bank accounts are being frozen under the Money Laundering Act. Please download the Skype app and connect to ID 'CBI_Desk_04' for your digital video interrogation. Do not inform any local police.",
        "Income Tax Dept: Your ITR refund of Rs 15,450 has been approved but could not be credited due to incorrect bank details. Please update your PAN details here",
        "Hello sir, aap jite h dhamaka lottery, claim karne ke liye apke number pe send kiya gya 6 digit ka number batayein",
    ]
    return [
        {
            "context": "",
            "response": normalize_text(text),
            "label": 1,
            "category": "CURATED_REAL_WORLD_SCAM",
        }
        for text in samples
    ]


# ============================================================================
# MULTI-TURN DIALOGUE GENERATORS (from v1, enhanced)
# ============================================================================

def build_scam_dialogue(specs):
    scam_categories = [k for k, v in specs.items() if v["label"] == 1]
    confused = [
        "What?", "Who is this?", "I don't understand", "Which bank?",
        "Are you sure?", "Please explain", "Kya?", "Kaun bol raha hai?",
    ]
    pressure = [
        "This is urgent, act immediately or face consequences.",
        "Your account will be blocked in 30 minutes.",
        "Our officer will visit your home if you don't comply.",
        "This is your final warning before legal action.",
        "Warna arrest ho jayega aapka.",
    ]
    hesitation = [
        "Let me check with my bank first.",
        "I need to ask my family.",
        "Can you call back later?",
        "This seems suspicious.",
        "Main soch ke batata hoon.",
    ]
    final_push = [
        "There is no time. Act now or lose everything.",
        "Do not tell anyone, this is confidential.",
        "Your family cannot help. Only we can resolve this.",
        "Calling back is not possible. Decide now.",
        "Ye confidential matter hai. Kisi ko bhi mat batana.",
    ]

    category = random.choice(scam_categories)
    opening = render_template(random.choice(specs[category]["templates"]), specs[category]["slots"])
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
    return {"context": context, "response": response, "label": 1, "category": category}


def build_benign_dialogue(specs):
    acknowledgements = ["Ok thanks", "Got it", "Thank you", "Noted", "Sure", "Understood", "Dhanyavaad"]
    closings = [
        "Have a great day!", "Let us know if you need help.",
        "Thank you for using our service.", "Your request has been processed.",
    ]
    opening = render_template(random.choice(specs["BENIGN_INDIA"]["templates"]), specs["BENIGN_INDIA"]["slots"])
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


def generate_multi_turn_dialogues(specs, scam_count=5000, benign_count=5000):
    scam_samples = [build_scam_dialogue(specs) for _ in range(scam_count)]
    benign_samples = [build_benign_dialogue(specs) for _ in range(benign_count)]
    return scam_samples + benign_samples


# ============================================================================
# MAIN ORCHESTRATOR
# ============================================================================

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
    
    categories = {}
    for r in records:
        cat = r["category"]
        categories[cat] = categories.get(cat, 0) + 1
    
    print(f"\n{'='*60}")
    print(f"DATASET SUMMARY")
    print(f"{'='*60}")
    print(f"Total samples:     {total:,}")
    print(f"Scam samples:      {scam:,} ({100*scam/total:.1f}%)")
    print(f"Benign samples:    {benign:,} ({100*benign/total:.1f}%)")
    print(f"Multi-turn:        {multi_turn:,}")
    print(f"\nCategories ({len(categories)}):")
    for cat, count in sorted(categories.items(), key=lambda x: -x[1]):
        label = "SCAM" if any(r["category"] == cat and r["label"] == 1 for r in records[:100] if r["category"] == cat) else "BENIGN"
        print(f"  {cat:40s} {count:6,}  [{label}]")


def main():
    random.seed(42)
    base_dir = Path(__file__).resolve().parent
    output_path = base_dir / "dataset" / "scam_dialogues_v2.jsonl"

    print("Step 1/6: Downloading UCI SMS Spam corpus...")
    download_sms_zip(SMS_URL, SMS_ZIP_PATH)
    part_sms = parse_sms_spam_from_zip(SMS_ZIP_PATH)
    print(f"  → {len(part_sms)} SMS samples")

    print("Step 2/6: Generating template-based scam/benign messages...")
    specs = category_specs()
    part_templates = generate_synthetic_single_turns(specs, per_category=3000)
    print(f"  → {len(part_templates)} template samples")

    print("Step 3/6: Generating multi-turn dialogues...")
    part_dialogues = generate_multi_turn_dialogues(specs, scam_count=5000, benign_count=5000)
    print(f"  → {len(part_dialogues)} dialogue samples")

    print("Step 4/6: Generating call transcript dialogues (15 archetypes)...")
    part_calls = generate_call_transcripts(scam_count=15000, benign_count=10000)
    print(f"  → {len(part_calls)} call transcript samples")

    print("Step 5/6: Generating adversarial/evasive scam patterns...")
    part_adversarial = generate_adversarial_scams(count=15000)
    print(f"  → {len(part_adversarial)} adversarial samples")

    print("Step 6/7: Generating hard negatives...")
    part_hard_neg = generate_hard_negatives(count=20000)
    print(f"  → {len(part_hard_neg)} hard negative samples")

    print("Step 7/7: Adding curated real-world scam examples...")
    part_curated = generate_curated_real_world_scams()
    print(f"  → {len(part_curated)} curated scam samples")

    # Combine all
    records = list(itertools.chain(
        part_sms,
        part_templates,
        part_dialogues,
        part_calls,
        part_adversarial,
        part_hard_neg,
        part_curated,
    ))
    
    random.seed(42)
    random.shuffle(records)

    write_jsonl(output_path, records)
    summarize(records)
    print(f"\nOutput: {output_path}")


if __name__ == "__main__":
    main()
