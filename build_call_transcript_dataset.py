"""
Call Transcript Scam Dialogue Generator
========================================
Generates realistic multi-turn call transcripts simulating what Whisper STT
would produce from live scam/benign calls. Covers 15+ scam archetypes with
Hinglish code-switching, speech disfluencies, and transcription artifacts.

Output: list of {"context": str, "response": str, "label": int, "category": str}
"""

import random
import re
from typing import Callable


# ---------------------------------------------------------------------------
# Speech disfluency & Whisper artifact injection
# ---------------------------------------------------------------------------

FILLERS_EN = ["um", "uh", "you know", "like", "okay", "so", "actually", "basically", "right"]
FILLERS_HI = ["haan", "matlab", "dekho", "acha", "toh", "waise", "suniye", "ji"]

WHISPER_ARTIFACTS = [
    lambda t: t.lower(),                        # lowercase everything
    lambda t: re.sub(r"[.!?,;:]", "", t),       # strip punctuation
    lambda t: re.sub(r"\s+", " ", t).strip(),   # collapse whitespace
]


def inject_disfluency(text: str, prob: float = 0.25) -> str:
    """Randomly inject speech fillers into text."""
    words = text.split()
    result = []
    for w in words:
        if random.random() < prob and len(result) > 0:
            filler = random.choice(FILLERS_EN + FILLERS_HI)
            result.append(filler)
        result.append(w)
    return " ".join(result)


def apply_whisper_artifacts(text: str) -> str:
    """Simulate Whisper transcription imperfections."""
    for fn in WHISPER_ARTIFACTS:
        text = fn(text)
    # Randomly repeat a word (common Whisper artifact)
    words = text.split()
    if len(words) > 5 and random.random() < 0.15:
        idx = random.randint(1, len(words) - 2)
        words.insert(idx, words[idx])
    return " ".join(words)


def normalize(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


# ---------------------------------------------------------------------------
# Slot generators
# ---------------------------------------------------------------------------

def rphone():
    return f"+91{random.randint(7000000000, 9999999999)}"

def ramt():
    return str(random.choice([500, 1000, 2000, 5000, 10000, 15000, 25000, 50000, 100000]))

def rotp():
    return str(random.randint(100000, 999999))

def rname():
    return random.choice([
        "Rahul", "Priya", "Amit", "Sneha", "Vikram", "Ananya", "Raj", "Pooja",
        "Suresh", "Kavita", "Deepak", "Meena", "Ravi", "Sunita", "Arun",
    ])

def rbank():
    return random.choice(["SBI", "HDFC", "ICICI", "Axis Bank", "PNB", "Kotak", "Canara Bank", "Bank of Baroda"])

def rapp():
    return random.choice(["AnyDesk", "TeamViewer", "QuickSupport", "AirDroid"])

def rdept():
    return random.choice(["CBI", "ED", "Income Tax Department", "Cybercrime Cell", "RBI", "TRAI", "Narcotics Bureau"])

def rcity():
    return random.choice(["Mumbai", "Delhi", "Bangalore", "Hyderabad", "Chennai", "Kolkata", "Pune", "Jaipur"])


# ---------------------------------------------------------------------------
# 15 Scam Call Scenario Archetypes
# ---------------------------------------------------------------------------

def _digital_arrest_scenario():
    """Fake government officer threatening digital arrest."""
    dept = rdept()
    city = rcity()
    name = rname()
    amt = ramt()
    
    scammer_turns = [
        [
            f"Hello, am I speaking to {name}? This is a very serious matter.",
            f"Namaste, kya main {name} se baat kar raha hoon? Bahut serious matter hai.",
            f"Yes hello, is this {name}? I'm calling from {dept} headquarters {city}.",
        ],
        [
            f"I am officer Sharma calling from {dept}. Your Aadhaar number has been linked to suspicious activities.",
            f"Main {dept} se bol raha hoon. Aapke Aadhaar number se kuch illegal transactions hue hain.",
            f"This is senior investigation officer from {dept}. A case has been registered against your name.",
        ],
        [
            f"Sir, aapke naam par money laundering ka case file hua hai. We have evidence.",
            f"Multiple FIRs have been registered against your identity. This is very serious.",
            f"Aapka number drug trafficking network se linked mila hai in our investigation.",
        ],
        [
            "You need to cooperate with us right now otherwise we will have to issue arrest warrant.",
            "Agar aap cooperate nahi karte to hum digital arrest ke process mein jayenge.",
            "Sir this is your last chance. Either cooperate now or face consequences.",
        ],
        [
            f"For verification, you need to transfer Rs {amt} to our investigation account. This will be refunded after clearance.",
            f"Aapko apni identity verify karne ke liye ek security deposit dena hoga of Rs {amt}.",
            "You need to keep your phone switched on and camera on. Do not tell anyone about this call. This is confidential.",
        ],
        [
            f"Transfer the amount immediately to this UPI ID. Do not disconnect the call.",
            f"Abhi turant transfer karo warna hum aapko arrest karenge within 2 hours.",
            "This is a matter of national security. Any delay will be considered obstruction of justice.",
        ],
    ]
    
    victim_turns = [
        ["What? Who is this?", "Kaun bol raha hai?", "Hello? What do you mean?"],
        ["But I haven't done anything wrong!", "Maine kuch nahi kiya hai sir!", "There must be some mistake."],
        ["This sounds suspicious to me.", "Mujhe lagta hai ye fraud hai.", "Can I verify this with the police station?"],
        ["Okay sir, but this seems very strange.", "Sir please, main bahut scared hoon.", "I don't have that much money right now."],
    ]
    
    return _build_call_dialogue(scammer_turns, victim_turns, "DIGITAL_ARREST", 1)


def _fake_bank_kyc_scenario():
    """Fake bank KYC update calling."""
    bank = rbank()
    name = rname()
    otp = rotp()
    
    scammer_turns = [
        [
            f"Good afternoon sir, I'm calling from {bank} customer care. Am I speaking with {name}?",
            f"Namaste sir, main {bank} head office se bol raha hoon. {name} ji?",
        ],
        [
            f"Sir your {bank} account KYC has expired. Your account will be blocked within 24 hours if not updated.",
            f"Aapka KYC update nahi hua hai. Agar aaj hi nahi karenge to account permanently block ho jayega.",
        ],
        [
            "I can help you complete the KYC right now over the phone sir. It will take just 2 minutes.",
            "Main abhi phone pe hi aapka KYC complete kar deta hoon. Bas thoda sa cooperation chahiye.",
        ],
        [
            f"Sir I'm sending an OTP to your registered mobile number. Please share the OTP for verification.",
            f"Aapke number par ek OTP aayega, please mujhe bata dijiye for account verification.",
        ],
        [
            "Sir this is urgent, your account already has a freeze warning. Please share the code immediately.",
            "Jaldi batao sir, system mein time limit hai. Warna dubara process start karna padega.",
        ],
    ]
    
    victim_turns = [
        ["Yes speaking.", "Haan boliye.", "Yes, this is {}.".format(name)],
        ["Oh really? I didn't know about this.", "Acha? Mujhe koi notification nahi aaya.", "When did this happen?"],
        ["Okay, but can't I do this at the branch?", "Branch mein jaake nahi ho sakta kya?", "I usually update at the ATM."],
    ]
    
    return _build_call_dialogue(scammer_turns, victim_turns, "KYC_SCAM_CALL", 1)


def _insurance_fraud_scenario():
    """Fake insurance claim / bonus scam."""
    name = rname()
    amt = str(random.choice([25000, 50000, 100000, 200000, 500000]))
    fee = str(random.choice([500, 999, 1500, 2999, 4999]))
    
    scammer_turns = [
        [
            f"Hello {name} ji, congratulations! Your LIC policy has matured and you are eligible for a bonus of Rs {amt}.",
            f"Namaste {name} ji, aapki insurance policy ka bonus amount Rs {amt} release ho gaya hai.",
        ],
        [
            f"To process this amount, there is a small processing fee of Rs {fee} that needs to be paid first.",
            f"Bas Rs {fee} ka processing charge pay karna hai, phir amount directly aapke account mein aa jayega.",
        ],
        [
            "Sir, thousands of people have already received their bonus. This is a limited time offer valid only today.",
            "Ye government scheme hai sir, bahut log already receive kar chuke hain. Aaj last date hai.",
        ],
        [
            "You can pay through UPI or NEFT. Let me share the details right now.",
            "Abhi payment kar dijiye, within 2 hours aapke account mein credited ho jayega.",
        ],
    ]
    
    victim_turns = [
        ["Really? Which policy is this?", "Kaun si policy?", "I don't remember having an insurance policy."],
        ["Why do I need to pay to receive money?", "Paisa lene ke liye paisa dena padega?", "This doesn't sound right."],
        ["Let me think about it.", "Main soch ke batata hoon.", "Can you call back later?"],
    ]
    
    return _build_call_dialogue(scammer_turns, victim_turns, "INSURANCE_FRAUD", 1)


def _tech_support_scenario():
    """Fake tech support / remote access scam."""
    name = rname()
    app = rapp()
    
    scammer_turns = [
        [
            f"Hello {name}, I'm calling from Microsoft Windows technical support. Your computer has been sending error reports to our server.",
            f"Sir, aapke phone mein virus detect hua hai by our security system. I'm from the technical department.",
        ],
        [
            "Your device is compromised. Hackers may have already accessed your banking applications.",
            "Aapka device hack ho chuka hai. Banking apps ka data leak ho raha hai right now.",
        ],
        [
            f"Don't worry sir, I can fix this remotely. Please download {app} from the Play Store.",
            f"Bas aap {app} install kar lijiye, main remote se sab fix kar dunga. Bilkul free service hai.",
        ],
        [
            f"Open {app} and tell me the 9-digit code shown on your screen.",
            "App open karo aur jo code dikhega wo mujhe bata do. Main connect karke fix kar dunga.",
        ],
        [
            "Sir please hurry up, every minute your data is being leaked. Your bank account is at risk.",
            "Jaldi karo sir, har minute mein aapka data hackers ke paas ja raha hai.",
        ],
    ]
    
    victim_turns = [
        ["What? My computer has a problem?", "Kya? Mere phone mein virus hai?", "How do you know about my device?"],
        ["That's very concerning.", "Oh no, kya karna chahiye?", "Really? How did this happen?"],
        ["Is this application safe?", "Ye app safe hai kya?", "My friend told me not to install such apps."],
    ]
    
    return _build_call_dialogue(scammer_turns, victim_turns, "TECH_SUPPORT_SCAM", 1)


def _loan_scam_scenario():
    """Fake instant loan offer."""
    name = rname()
    loan_amt = str(random.choice([50000, 100000, 200000, 500000, 1000000]))
    fee = str(random.choice([1000, 2500, 5000, 9999]))
    
    scammer_turns = [
        [
            f"Hello sir, am I speaking with {name}? You have been pre-approved for a personal loan of Rs {loan_amt}.",
            f"Namaste {name} ji, aapko Rs {loan_amt} ka instant personal loan pre-approved hai. No documents needed.",
        ],
        [
            "This is a zero documentation loan. Interest rate is only 3% per annum. Much lower than any bank.",
            "Koi paperwork nahi chahiye, sirf Aadhaar number se ho jayega. 3% interest rate.",
        ],
        [
            f"To process the loan, we need a one-time processing fee of Rs {fee}. This will be adjusted in your first EMI.",
            f"Bas Rs {fee} processing fee pay karo, loan 30 minutes mein aapke account mein.",
        ],
        [
            "Sir this offer is valid only for today. After today the interest rate will increase to 12%.",
            "Aaj hi apply karo toh additional cashback bhi milega. Limited period offer hai.",
        ],
    ]
    
    victim_turns = [
        ["I didn't apply for any loan.", "Maine koi loan apply nahi kiya.", "Which company is this from?"],
        ["That interest rate sounds too good to be true.", "Itna kam interest kaise?", "Which RBI license do you have?"],
    ]
    
    return _build_call_dialogue(scammer_turns, victim_turns, "LOAN_SCAM", 1)


def _delivery_customs_scenario():
    """Fake parcel/customs clearance scam."""
    name = rname()
    amt = str(random.choice([2000, 5000, 8000, 15000, 25000]))
    
    scammer_turns = [
        [
            f"Hello, is this {name}? I'm calling from International Courier Services. Your parcel from abroad is stuck at customs.",
            f"{name} ji, aapka international parcel customs department mein ruka hua hai.",
        ],
        [
            f"There are some restricted items found in the package. You need to pay customs duty of Rs {amt} to release it.",
            f"Package mein kuch restricted items mili hain. Rs {amt} customs clearance fee deni hogi.",
        ],
        [
            "If you don't pay within 4 hours, the parcel will be destroyed and a case will be filed against you.",
            "4 ghante ke andar payment nahi hui toh parcel destroy ho jayega aur legal action hoga.",
        ],
        [
            "Pay through UPI to our official customs clearance officer's account. I'll share the UPI ID now.",
            "Abhi turant UPI se payment karo. Main customs officer ka UPI ID share karta hoon.",
        ],
    ]
    
    victim_turns = [
        ["I haven't ordered anything from abroad.", "Maine kuch order nahi kiya international.", "There must be some mistake."],
        ["This sounds suspicious. Can you give me a reference number?", "Mujhe suspicious lag raha hai.", "Let me check with the post office first."],
    ]
    
    return _build_call_dialogue(scammer_turns, victim_turns, "CUSTOMS_SCAM_CALL", 1)


def _electricity_bill_scenario():
    """Fake electricity disconnection threat."""
    name = rname()
    amt = str(random.choice([3500, 5000, 7500, 12000]))
    
    scammer_turns = [
        [
            f"This is an automated call from the electricity department. Your pending bill of Rs {amt} has not been paid. Your connection will be disconnected within 2 hours.",
            f"Namaste, main electricity department se bol raha hoon. Aapka Rs {amt} ka bill pending hai. 2 ghante mein bijli kat jayegi.",
        ],
        [
            "To avoid disconnection, pay immediately using UPI. I'll guide you through the payment process.",
            "Disconnection se bachne ke liye abhi payment karo. Main aapko help kar deta hoon.",
        ],
        [
            "Sir this is the final notice. After disconnection there will be additional Rs 5000 reconnection charge.",
            "Ye last warning hai sir, baad mein Rs 5000 extra lagega reconnection ka.",
        ],
    ]
    
    victim_turns = [
        ["But I already paid my bill last month.", "Maine toh bill already pay kiya tha.", "I have the receipt."],
        ["Can I pay at the office instead?", "Office jaake nahi pay kar sakte?", "Which account should I pay to?"],
    ]
    
    return _build_call_dialogue(scammer_turns, victim_turns, "UTILITY_SCAM", 1)


def _sextortion_scenario():
    """Sextortion / blackmail call."""
    name = rname()
    amt = str(random.choice([5000, 10000, 25000, 50000]))
    
    scammer_turns = [
        [
            f"Hello {name}, I have some very personal photos and videos of yours. I'm sure you know what I'm talking about.",
            f"{name}, mere paas tumhare kuch private screenshots hain. Samajh rahe ho na?",
        ],
        [
            f"If you don't transfer Rs {amt} within 1 hour, I will share these with all your contacts and on social media.",
            f"1 ghante mein Rs {amt} transfer karo warna sab contacts ko bhej dunga.",
        ],
        [
            "Don't try to go to police. I have your entire contact list. I know where you work.",
            "Police ke paas mat jaana. Tumhare saare contacts mere paas hain. Job bhi khatam ho jayegi.",
        ],
    ]
    
    victim_turns = [
        ["What? What are you talking about?", "Kya? Kaise? Ye kya bol rahe ho?", "I don't know what you mean."],
        ["Please don't do this.", "Please aisa mat karo.", "I will go to the police."],
    ]
    
    return _build_call_dialogue(scammer_turns, victim_turns, "SEXTORTION", 1)


def _crypto_investment_scenario():
    """Fake crypto/forex investment scam."""
    name = rname()
    returns = random.choice(["double", "triple", "5x", "10x"])
    period = random.choice(["7 days", "15 days", "1 month"])
    invest = str(random.choice([5000, 10000, 25000, 50000]))
    
    scammer_turns = [
        [
            f"Hello {name}, this is a premium investment opportunity. Our AI trading platform guarantees {returns} returns in {period}.",
            f"Namaste {name} ji, kya aap investment mein interested hain? Hamare platform se {returns} returns milte hain {period} mein.",
        ],
        [
            f"Just invest Rs {invest} today and watch your money grow. Thousands of investors are already earning daily.",
            f"Bas Rs {invest} lagao aur daily earnings dekho. Already 50000+ investors hain hamare platform pe.",
        ],
        [
            "I'll send you some screenshots of other investors' earnings. You can start today and withdraw anytime.",
            "Main aapko WhatsApp pe proof bhejta hoon. Aap aaj hi start kar sakte ho.",
        ],
        [
            "This is a limited period offer. After today, minimum investment will increase to Rs 100000.",
            "Aaj last day hai is rate pe invest karne ka. Kal se minimum 1 lakh ho jayega.",
        ],
    ]
    
    victim_turns = [
        ["How is this possible? Guaranteed returns?", "Guaranteed returns kaise? Stock market toh volatile hai.", "Is this regulated by SEBI?"],
        ["Let me think about it first.", "Main soch ke batata hoon.", "I need to discuss with my family."],
    ]
    
    return _build_call_dialogue(scammer_turns, victim_turns, "CRYPTO_SCAM", 1)


def _job_fraud_scenario():
    """Fake job offer scam."""
    name = rname()
    fee = str(random.choice([500, 1000, 2000, 5000]))
    salary = str(random.choice([30000, 50000, 75000, 100000]))
    company = random.choice(["Amazon", "Google", "TCS", "Infosys", "Flipkart", "Reliance"])
    
    scammer_turns = [
        [
            f"Hello {name}, congratulations! You have been shortlisted for a work from home position at {company}. Monthly salary Rs {salary}.",
            f"Namaste {name} ji, aapka resume select hua hai {company} mein. Salary Rs {salary} per month hai.",
        ],
        [
            f"To complete your registration, there is a one-time registration fee of Rs {fee}.",
            f"Bas Rs {fee} ka registration charge pay karo, baaki sab HR team handle karegi.",
        ],
        [
            "This is from our official HR department. You can start working from tomorrow itself.",
            "Official offer letter fee payment ke baad email ho jayega. Kal se kaam shuru.",
        ],
    ]
    
    victim_turns = [
        ["I didn't apply for any job there.", "Maine apply nahi kiya tha.", "How did you get my resume?"],
        ["Why do I need to pay for a job?", "Job ke liye paise kyun dene hain?", "Real companies don't charge fees."],
    ]
    
    return _build_call_dialogue(scammer_turns, victim_turns, "JOB_SCAM", 1)


def _sim_swap_scenario():
    """SIM swap / telecom verification scam."""
    name = rname()
    otp = rotp()
    
    scammer_turns = [
        [
            f"Hello {name}, I'm calling from Jio/Airtel network. Your SIM card needs to be re-verified under new TRAI regulations.",
            f"Namaste, main Jio office se bol raha hoon. TRAI ke naye rules ke hisaab se SIM re-verification zaruri hai.",
        ],
        [
            "If verification is not completed today, your SIM will be deactivated automatically in 4 hours.",
            "Aaj complete nahi hua toh SIM automatically deactivate ho jayega 4 ghante mein.",
        ],
        [
            f"I'm sending a verification code to your number. Please read it back to me for confirmation.",
            "Ek code aayega aapke number pe, bas wo mujhe bata dijiye. Simple verification hai.",
        ],
        [
            "Sir this is the standard process. Every SIM holder has to do this. It's mandatory under TRAI guidelines.",
            "Ye TRAI mandatory process hai sir. Sabko karna padta hai. Koi risk nahi hai.",
        ],
    ]
    
    victim_turns = [
        ["But I just got my SIM verified last month.", "Maine abhi recently verify karaya tha.", "Can I do this at the Jio store?"],
        ["Why can't I go to the store for this?", "Store pe jaake nahi kar sakte?", "My friend said these calls are scams."],
    ]
    
    return _build_call_dialogue(scammer_turns, victim_turns, "SIM_SWAP_SCAM", 1)


def _matrimony_scam_scenario():
    """Romance/matrimony scam call."""
    name = rname()
    amt = str(random.choice([10000, 25000, 50000]))
    
    scammer_turns = [
        [
            f"Hi {name}, I really liked your profile on the matrimony site. I think we could be a great match.",
            f"Hello {name}, aapka profile dekha matrimony site pe. Bahut acha laga. Can we talk more?",
        ],
        [
            "I'm actually an NRI settled in Canada. I want to visit India to meet you but my card is blocked.",
            "Main Canada mein rehta hoon. India aana chahta hoon tumse milne lekin mera international card block ho gaya.",
        ],
        [
            f"Can you please help me with Rs {amt} for the flight ticket? I'll return it as soon as I reach India.",
            f"Bas Rs {amt} transfer kar do ticket ke liye. India aate hi wapas kar dunga. Please trust me.",
        ],
    ]
    
    victim_turns = [
        ["Thank you, your profile is nice too.", "Thank you, aapka bhi acha laga.", "Tell me more about yourself."],
        ["Oh that's unfortunate.", "Acha, aisa kya?", "That sounds like a problem."],
    ]
    
    return _build_call_dialogue(scammer_turns, victim_turns, "MATRIMONY_SCAM", 1)


def _award_lottery_scenario():
    """Fake award / lottery winning call."""
    name = rname()
    prize = random.choice(["Rs 10 Lakh", "Rs 25 Lakh", "a BMW car", "an iPhone 15 Pro"])
    fee = str(random.choice([999, 2000, 5000, 9999]))
    show = random.choice(["KBC", "Big Boss", "Jio Lucky Draw", "Amazon Lucky Customer"])
    
    scammer_turns = [
        [
            f"Congratulations {name}! You have won {prize} in the {show} lucky draw!",
            f"Badhai ho {name} ji! Aapne {show} mein {prize} jeet liye hain!",
        ],
        [
            f"To claim your prize, you need to pay a small tax/processing fee of Rs {fee}.",
            f"Prize claim karne ke liye Rs {fee} tax amount pay karna hoga. Government rule hai.",
        ],
        [
            "This is a once in a lifetime opportunity. Prize claim deadline is today only.",
            "Aaj hi claim karna hoga sir, warna prize next person ko chala jayega.",
        ],
    ]
    
    victim_turns = [
        ["Really? I never entered any contest!", "Sacchi? Maine toh koi contest enter nahi kiya!", "Is this genuine?"],
        ["Why do I need to pay to receive a prize?", "Jeetne ke baad bhi paisa dena padega?", "Can you deduct from the prize amount?"],
    ]
    
    return _build_call_dialogue(scammer_turns, victim_turns, "LOTTERY_SCAM", 1)


def _hospital_emergency_scenario():
    """Fake hospital emergency demanding money for relative."""
    name = rname()
    relative = random.choice(["beta", "behen", "bhai", "beti", "husband", "wife"])
    amt = str(random.choice([25000, 50000, 100000, 200000]))
    
    scammer_turns = [
        [
            f"Hello, is this {name}? Your {relative} has been in a serious accident and is in the ICU right now.",
            f"Hello {name} ji? Aapke {relative} ka accident ho gaya hai. ICU mein hain abhi.",
        ],
        [
            f"Doctor says surgery is needed immediately. We need Rs {amt} as advance before we can start.",
            f"Doctor bol rahe hain ki surgery immediately chahiye. Rs {amt} advance deposit karna hoga.",
        ],
        [
            "Every minute counts. If surgery is delayed, it could be life threatening. Please arrange the money now.",
            "Ek ek minute ki delay dangerous hai sir. Jaldi se jaldi payment karo please.",
        ],
    ]
    
    victim_turns = [
        ["Oh my god! Which hospital?", "Kya? Kaun sa hospital?", "Is he/she okay?"],
        ["I'm coming right now!", "Main abhi aa raha hoon!", "Let me talk to the doctor!"],
    ]
    
    return _build_call_dialogue(scammer_turns, victim_turns, "EMERGENCY_SCAM", 1)


def _refund_scam_scenario():
    """Fake refund / overpayment scam."""
    name = rname()
    amt = str(random.choice([1500, 3000, 5000, 8000]))
    company = random.choice(["Amazon", "Flipkart", "Swiggy", "Zomato", "Paytm"])
    
    scammer_turns = [
        [
            f"Hello {name}, I'm calling from {company}. Due to a system error, we charged you Rs {amt} extra on your last order.",
            f"Namaste {name}, main {company} se bol raha hoon. Aapke last order mein Rs {amt} extra charge ho gaya system error se.",
        ],
        [
            "We want to process your refund immediately. Can you please share your UPI ID for the refund?",
            "Hum aapko turant refund karna chahte hain. Aapka UPI ID share kar dijiye.",
        ],
        [
            "I'm sending you a refund request on your UPI app. Please approve it to receive your money back.",
            "Aapke UPI pe ek request aa rahi hai. Please approve karo, aapka refund aa jayega.",
        ],
    ]
    
    victim_turns = [
        ["Oh really? Let me check my order history.", "Acha? Main check karta hoon.", "I didn't notice extra charge."],
        ["Okay, my UPI ID is...", "Wait, but why do I need to approve something to receive money?", "Let me check the app first."],
    ]
    
    return _build_call_dialogue(scammer_turns, victim_turns, "REFUND_SCAM", 1)


# ---------------------------------------------------------------------------
# Benign Call Scenarios
# ---------------------------------------------------------------------------

def _benign_bank_call():
    bank = rbank()
    name = rname()
    turns = [
        (f"[CALLER] Hello {name}, this is {bank} regarding your credit card statement for this month. Your total outstanding is Rs {random.randint(5000, 50000)}.", 
         f"[CALLEE] Yes, I'm {name}. Thank you for the reminder."),
        (f"[CALLER] Just a reminder that the payment due date is the {random.randint(1,28)}th. You can pay via net banking or our mobile app.",
         "[CALLEE] Got it, I'll pay before the due date. Thank you."),
        ("[CALLER] Thank you sir. Is there anything else I can help with?",
         "[CALLEE] No, that's all. Thank you."),
    ]
    return _format_benign_dialogue(turns, "BENIGN_BANK")


def _benign_delivery_call():
    name = rname()
    company = random.choice(["Amazon", "Flipkart", "Myntra", "Swiggy", "Zomato"])
    turns = [
        (f"[CALLER] Hello, is this {name}? I'm the delivery agent from {company}. I'm at your location but the address is not clear.",
         f"[CALLEE] Yes I'm {name}. Which building are you at?"),
        ("[CALLER] I'm at the main gate. Can you come down or guide me to your flat?",
         f"[CALLEE] Take the lift to floor {random.randint(1,15)}, flat number {random.randint(101,1510)}."),
        ("[CALLER] Okay thank you, I'll be there in 2 minutes.",
         "[CALLEE] Sure, I'm home."),
    ]
    return _format_benign_dialogue(turns, "BENIGN_DELIVERY")


def _benign_doctor_call():
    name = rname()
    doctor = random.choice(["Dr. Sharma", "Dr. Patel", "Dr. Gupta", "Dr. Kumar", "Dr. Singh"])
    turns = [
        (f"[CALLER] Hello {name}, this is {doctor}'s clinic calling. We're confirming your appointment for tomorrow at {random.choice(['10 AM', '2 PM', '4:30 PM', '11 AM'])}.",
         "[CALLEE] Yes doctor, I'll be there on time."),
        (f"[CALLER] Please bring your previous reports and arrive 15 minutes early for registration.",
         "[CALLEE] Sure, I have all the reports ready. Thank you for reminding."),
    ]
    return _format_benign_dialogue(turns, "BENIGN_DOCTOR")


def _benign_friend_call():
    name1 = rname()
    name2 = rname()
    while name2 == name1:
        name2 = rname()
    
    topics = [
        [
            f"[CALLER] Hey {name2}! Long time no see. How have you been?",
            f"[CALLEE] Hey {name1}! I'm good yaar. What's up?",
            f"[CALLER] Nothing much. Wanted to check if you're free this weekend? Planning a trip to {rcity()}.",
            "[CALLEE] Sounds fun! Let me check with my family and I'll let you know by tomorrow.",
        ],
        [
            f"[CALLER] {name2}, tune wo movie dekhi? Avatar 3 ki tickets leni hain kya?",
            "[CALLEE] Haan yaar, bahut suna hai. Weekend pe chalte hain!",
            "[CALLER] Done! Main tickets book kar leta hoon. Saturday evening chalega?",
            "[CALLEE] Perfect! See you then.",
        ],
        [
            f"[CALLER] Bro {name2}, ek help chahiye. Meri bike ka insurance renew karna hai. Tu kidhar se karta hai?",
            "[CALLEE] Acko se karta hoon main. Online ho jata hai 5 minute mein.",
            "[CALLER] Acha, link bhej de please. Thanks!",
            "[CALLEE] Haan bhej deta hoon WhatsApp pe. No problem.",
        ],
    ]
    
    chosen = random.choice(topics)
    context = "\n".join(chosen[:-1])
    response = chosen[-1].split("] ", 1)[1] if "] " in chosen[-1] else chosen[-1]
    return {"context": context, "response": response, "label": 0, "category": "BENIGN_FRIEND"}


def _benign_work_call():
    name = rname()
    boss = random.choice(["Mr. Verma", "Mrs. Kapoor", "Rajesh sir", "Priya ma'am"])
    turns = [
        (f"[CALLER] {name}, {boss} here. Can you join the standup call at 3 PM? We need to discuss the sprint deadline.",
         "[CALLEE] Sure sir, I'll join. Should I prepare anything specific?"),
        ("[CALLER] Just update on the API integration status. Client wants an update by EOD.",
         "[CALLEE] Got it, I'll prepare a summary. The integration is almost done."),
        ("[CALLER] Great, see you at 3 then.",
         "[CALLEE] Yes sir, see you."),
    ]
    return _format_benign_dialogue(turns, "BENIGN_WORK")


def _benign_family_finance_call():
    """Family discussing money — hard negative for financial scam."""
    name = rname()
    relative_name = rname()
    amt = str(random.randint(5000, 100000))
    
    turns = [
        (f"[CALLER] Hello {name} beta, papa bol raha hoon. Mujhe Rs {amt} transfer kar de na, {relative_name} ki school fees bharni hai.",
         f"[CALLEE] Haan papa, abhi karta hoon. GPay pe bhejun ya bank transfer?"),
        ("[CALLER] GPay pe bhej de, jaldi ho jayega. Thank you beta.",
         f"[CALLEE] Bhej diya papa. Check kar lo, {amt} aaya hoga."),
    ]
    return _format_benign_dialogue(turns, "BENIGN_FAMILY_FINANCE")


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _format_benign_dialogue(turns, category):
    flat = []
    for caller_line, callee_line in turns:
        flat.append(caller_line)
        flat.append(callee_line)
    
    context = "\n".join(flat[:-1])
    response = flat[-1].split("] ", 1)[1] if "] " in flat[-1] else flat[-1]
    return {"context": context, "response": response, "label": 0, "category": category}


def _build_call_dialogue(scammer_turns, victim_turns, category, label):
    """Build a multi-turn call dialogue from scammer and victim turn options."""
    dialogue = []
    
    # Determine conversation length (3 to max turns)
    n_scammer = random.randint(3, len(scammer_turns))
    n_victim = min(n_scammer - 1, len(victim_turns))
    
    for i in range(n_scammer):
        # Scammer turn
        scammer_line = random.choice(scammer_turns[i])
        scammer_line = inject_disfluency(scammer_line, prob=0.15)
        scammer_line = apply_whisper_artifacts(scammer_line)
        dialogue.append(f"[CALLER] {normalize(scammer_line)}")
        
        # Victim turn (if available)
        if i < n_victim:
            victim_line = random.choice(victim_turns[i])
            victim_line = inject_disfluency(victim_line, prob=0.2)
            victim_line = apply_whisper_artifacts(victim_line)
            dialogue.append(f"[CALLEE] {normalize(victim_line)}")
    
    context = "\n".join(dialogue[:-1])
    response = dialogue[-1].split("] ", 1)[1] if "] " in dialogue[-1] else dialogue[-1]
    
    return {"context": context, "response": response, "label": label, "category": category}


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

SCAM_GENERATORS = [
    _digital_arrest_scenario,
    _fake_bank_kyc_scenario,
    _insurance_fraud_scenario,
    _tech_support_scenario,
    _loan_scam_scenario,
    _delivery_customs_scenario,
    _electricity_bill_scenario,
    _sextortion_scenario,
    _crypto_investment_scenario,
    _job_fraud_scenario,
    _sim_swap_scenario,
    _matrimony_scam_scenario,
    _award_lottery_scenario,
    _hospital_emergency_scenario,
    _refund_scam_scenario,
]

BENIGN_GENERATORS = [
    _benign_bank_call,
    _benign_delivery_call,
    _benign_doctor_call,
    _benign_friend_call,
    _benign_work_call,
    _benign_family_finance_call,
]


def generate_call_transcripts(scam_count=15000, benign_count=10000):
    """Generate call transcript scam + benign dialogues."""
    samples = []
    
    # Scam calls — distribute across all archetypes
    per_archetype = scam_count // len(SCAM_GENERATORS)
    remainder = scam_count % len(SCAM_GENERATORS)
    
    for i, gen in enumerate(SCAM_GENERATORS):
        count = per_archetype + (1 if i < remainder else 0)
        for _ in range(count):
            samples.append(gen())
    
    # Benign calls — distribute
    per_benign = benign_count // len(BENIGN_GENERATORS)
    for gen in BENIGN_GENERATORS:
        for _ in range(per_benign):
            samples.append(gen())
    
    # Fill remainder
    remaining = benign_count - per_benign * len(BENIGN_GENERATORS)
    for _ in range(remaining):
        gen = random.choice(BENIGN_GENERATORS)
        samples.append(gen())
    
    return samples


if __name__ == "__main__":
    random.seed(42)
    samples = generate_call_transcripts(scam_count=100, benign_count=50)
    
    scam = sum(1 for s in samples if s["label"] == 1)
    benign = sum(1 for s in samples if s["label"] == 0)
    categories = {}
    for s in samples:
        categories[s["category"]] = categories.get(s["category"], 0) + 1
    
    print(f"Total: {len(samples)} | Scam: {scam} | Benign: {benign}")
    print("Categories:")
    for cat, count in sorted(categories.items()):
        print(f"  {cat}: {count}")
    print("\nSample scam dialogue:")
    s = next(s for s in samples if s["label"] == 1)
    print(f"  Context: {s['context'][:200]}...")
    print(f"  Response: {s['response']}")
    print(f"  Category: {s['category']}")
