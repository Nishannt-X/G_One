/**
 * Generates the voiced sample call: one manifest plus one WAV per spoken line, under web/public/demo/voiced-sample/.
 * The output is committed, so the demo plays with no keys and no network. Only lines whose text or voice changed call
 * Timbre again; raw responses are cached outside the repo.
 *
 *   GNANI_API_KEY=... bun web/scripts/gen-voiced-sample.ts
 *
 * Everything said here is invented: the people, the firm and the prices.
 */
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readdirSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

type Role = 'farmer' | 'buyer' | 'ai'
type Party = 'farmer' | 'buyer'
type Hearing = 'speaking' | 'hears' | 'silent'

interface Line {
  /** Who is voiced. Defaults to the beat's speaker for "own", to G-One for "heard". */
  kind: 'own' | 'heard'
  lang: string
  text: string
  /** English for the viewer. Prices appear in digits here only. */
  gloss?: string
  /** Who G-One says this to, for G-One's own lines and translations. */
  to?: Party
}

interface Beat {
  id: string
  speaker: Role
  audience: Party | 'both' | 'none'
  whisper?: 'consent' | 'price_reference' | 'caution' | 'read_back'
  notPassedOn?: boolean
  lines: Line[]
  deal?: Record<string, unknown>
  /** Things that happen as the beat starts. */
  buyerJoins?: boolean
  tool?: { tool: string; args: Record<string, unknown>; result: string }
  note?: string
  /** Beat closes the call and saves the memory note. */
  endsCall?: boolean
  /** This side's phone receives the deal summary as the beat starts. */
  deliver?: Party
}

const VOICES = {
  farmer: { voice: 'Kavin', label: 'Kavin' },
  buyer: { voice: 'Ishaan', label: 'Ishaan' },
  // G-One has one voice per language. Hindi differs from the live default so the buyer's own Hindi stays easy to tell apart.
  ai: { 'kn-IN': 'Saanvi', 'hi-IN': 'Bhavna', 'en-IN': 'Kaveri', 'bn-IN': 'Kirra' } as Record<string, string>,
}

/** English said inside a Kannada line, spelled the way Timbre should say it. On screen these stay in English. */
const KN_SPOKEN: Record<string, string> = {
  rate: 'ರೇಟ್', quality: 'ಕ್ವಾಲಿಟಿ', good: 'ಗುಡ್', size: 'ಸೈಜ್', price: 'ಪ್ರೈಸ್', market: 'ಮಾರ್ಕೆಟ್', down: 'ಡೌನ್', sample: 'ಸ್ಯಾಂಪಲ್', reference: 'ರೆಫರೆನ್ಸ್',
  qr: 'ಕ್ಯೂ ಆರ್', scan: 'ಸ್ಕ್ಯಾನ್', upi: 'ಯು ಪಿ ಐ', pin: 'ಪಿನ್', last: 'ಲಾಸ್ಟ್', deal: 'ಡೀಲ್', summary: 'ಸಮ್ಮರಿ', message: 'ಮೆಸೇಜ್',
}

// G-One's own fixed lines below are copied word for word from backend/src/main/resources/prompts/fixed-lines.json
// (nameCheck, askQuantity, askNoLimits, askEnd, askSave, goodbye), so the sample says what the product says.
const BEATS: Beat[] = [
  {
    id: 'farmer-language-ask', speaker: 'ai', audience: 'farmer',
    lines: [{ kind: 'own', lang: 'en-IN', to: 'farmer', text: 'Hello, this is G-One. Which language would you like to speak? Hindi, English, Bengali, Tamil, Kannada, or Hinglish.' }],
  },
  {
    id: 'farmer-language-answer', speaker: 'farmer', audience: 'none',
    lines: [{ kind: 'own', lang: 'kn-IN', text: 'ಕನ್ನಡ, ಕನ್ನಡದಲ್ಲಿ ಮಾತಾಡಿ.', gloss: 'Kannada, please speak in Kannada.' }],
  },
  {
    id: 'farmer-language-confirm', speaker: 'ai', audience: 'farmer',
    lines: [{ kind: 'own', lang: 'kn-IN', to: 'farmer', text: 'ಸರಿ, ಕನ್ನಡದಲ್ಲಿ ಮಾತನಾಡೋಣ.', gloss: 'All right, let us speak in Kannada.' }],
  },
  {
    id: 'name-check', speaker: 'ai', audience: 'farmer',
    note: 'The remembered name is confirmed before memory is used.',
    lines: [{ kind: 'own', lang: 'kn-IN', to: 'farmer', text: 'ನಮಸ್ಕಾರ, ಜಿ-ಒನ್ ಮಾತನಾಡುತ್ತಿದ್ದೇನೆ. ನೀವು ಬಸವರಾಜ್ ಅವರೇ?', gloss: 'Namaskara, this is G-One speaking. Are you Basavaraj?' }],
  },
  {
    id: 'name-yes', speaker: 'farmer', audience: 'none',
    lines: [{ kind: 'own', lang: 'kn-IN', text: 'ಹೌದು, ನಾನೇ.', gloss: 'Yes, it is me.' }],
  },
  {
    id: 'greet', speaker: 'ai', audience: 'farmer',
    lines: [{ kind: 'own', lang: 'kn-IN', to: 'farmer', text: 'ನಮಸ್ಕಾರ ಬಸವರಾಜ್ ಅವರೇ! ಹೋದ ಸಲ ಈರುಳ್ಳಿ rate ಕೇಳಿದ್ರಿ, ನೆನಪಿದೆ. ಇವತ್ತು ಏನು ಮಾಡೋಣ?', gloss: 'Namaskara, Basavaraj! Last time you asked about the onion rate, I remember. What shall we do today?' }],
  },
  {
    id: 'sell', speaker: 'farmer', audience: 'none',
    deal: { state: 'draft', commodity: 'onion', quantity: '40 quintal' },
    lines: [
      { kind: 'own', lang: 'kn-IN', text: 'ಹೌದು. ನಲವತ್ತು ಕ್ವಿಂಟಾಲ್ ಈರುಳ್ಳಿ ಇದೆ, ಮಾರಬೇಕು.', gloss: 'Yes. I have forty quintals of onion to sell.' },
      { kind: 'own', lang: 'kn-IN', text: 'Good price ಸಿಕ್ಕಿದ್ರೆ ಕೊಡ್ತೀನಿ.', gloss: 'If I get a good price, I will sell.' },
    ],
  },
  {
    id: 'consent', speaker: 'ai', audience: 'farmer', whisper: 'consent',
    tool: { tool: 'buyer_match', args: { commodity: 'onion', quantity: '40 quintal' }, result: "Matched buyer 'Mondal Traders, Kolkata' (sample directory)." },
    lines: [{ kind: 'own', lang: 'kn-IN', to: 'farmer', text: 'ಕೊಲ್ಕತ್ತಾದಲ್ಲಿ ಮೊಂಡಲ್ ಟ್ರೇಡರ್ಸ್ ಅಂತ ಖರೀದಿದಾರರು ಸಿಕ್ಕಿದ್ದಾರೆ. ಅವರಿಗೆ ಕರೆ ಮಾಡಲಾ?', gloss: 'I found a buyer in Kolkata, Mondal Traders. Shall I call them?' }],
  },
  {
    id: 'consent-yes', speaker: 'farmer', audience: 'none',
    lines: [{ kind: 'own', lang: 'kn-IN', text: 'ಆಯ್ತು, ಮಾಡಿ.', gloss: 'All right, do it.' }],
  },
  {
    id: 'buyer-language-ask', speaker: 'ai', audience: 'buyer', buyerJoins: true,
    lines: [{ kind: 'own', lang: 'en-IN', to: 'buyer', text: 'Hello, this is G-One. I am connecting you to a farmer. Which language would you like to speak? Hindi, English, Bengali, Tamil, Kannada, or Hinglish.' }],
  },
  {
    id: 'buyer-language-answer', speaker: 'buyer', audience: 'none',
    lines: [{ kind: 'own', lang: 'bn-IN', text: 'বাংলা।', gloss: 'Bengali.' }],
  },
  {
    id: 'buyer-language-confirm', speaker: 'ai', audience: 'buyer',
    lines: [{ kind: 'own', lang: 'bn-IN', to: 'buyer', text: 'ঠিক আছে, বাংলায় কথা বলি।', gloss: 'All right, let us speak in Bengali.' }],
  },
  {
    id: 'disclose', speaker: 'ai', audience: 'buyer',
    deal: { market: 'Kolkata' },
    lines: [
      { kind: 'own', lang: 'bn-IN', to: 'buyer', text: 'নমস্কার, আমি জি-ওয়ান, একটা এআই সহকারী। কর্ণাটকের এক চাষির হয়ে ফোন করছি। ওঁর চল্লিশ কুইন্টাল পেঁয়াজ বিক্রি আছে।', gloss: 'Namaskar, I am G-One, an AI assistant. I am calling on behalf of a farmer in Karnataka. He has forty quintals of onion for sale.' },
      // The product's own question to a buyer (bn-IN buyerIntro in backend/src/main/resources/prompts/mediation-lines.json).
      { kind: 'own', lang: 'bn-IN', to: 'buyer', text: 'এখন কথা বলা যাবে? হ্যাঁ বা না বলুন।', gloss: 'Can you talk now? Please say yes or no.' },
    ],
  },
  {
    id: 'buyer-in', speaker: 'buyer', audience: 'farmer',
    lines: [
      { kind: 'own', lang: 'bn-IN', text: 'হ্যাঁ বলুন, পেঁয়াজ আমার লাগবে।', gloss: 'Yes, go on, I need onions.' },
      { kind: 'own', lang: 'hi-IN', text: 'ठीक है, बात कराइए।', gloss: 'All right, let me talk to them.' },
      { kind: 'heard', lang: 'kn-IN', to: 'farmer', text: 'ಅವರಿಗೆ ಈರುಳ್ಳಿ ಬೇಕಂತೆ. ಮಾತಾಡೋಣ ಅಂತಿದ್ದಾರೆ.', gloss: 'They need onions. They would like to talk.' },
    ],
  },
  {
    id: 'ask-rate', speaker: 'farmer', audience: 'buyer',
    lines: [
      { kind: 'own', lang: 'kn-IN', text: 'ನಮ್ಮ ಈರುಳ್ಳಿ quality ಚೆನ್ನಾಗಿದೆ, good size ಇದೆ. ಕ್ವಿಂಟಾಲಿಗೆ ಎಷ್ಟು ಕೊಡ್ತೀರಿ?', gloss: 'Our onion quality is good, a good size. How much will you give a quintal?' },
      { kind: 'heard', lang: 'bn-IN', to: 'buyer', text: 'কৃষক বলছেন, ওঁর পেঁয়াজ ভালো। আপনি কুইন্টাল প্রতি কত দেবেন?', gloss: 'The farmer says his onions are good. How much will you give a quintal?' },
    ],
  },
  {
    id: 'ask-quantity', speaker: 'buyer', audience: 'none', notPassedOn: true,
    lines: [{ kind: 'own', lang: 'bn-IN', text: 'জি-ওয়ান, উনি কত কুইন্টাল পেঁয়াজ বেচছেন?', gloss: 'G-One, how many quintals of onion is he selling?' }],
  },
  {
    id: 'answer-quantity', speaker: 'ai', audience: 'buyer', notPassedOn: true,
    note: 'Asked of G-One, answered only to the buyer from what was said on this call.',
    lines: [{ kind: 'own', lang: 'bn-IN', to: 'buyer', text: 'এই কলে এখন পর্যন্ত পরিমাণ বলা হয়েছে চল্লিশ কুইন্টাল।', gloss: 'So far on this call, the quantity said is forty quintals.' }],
  },
  {
    id: 'ask-lowest', speaker: 'buyer', audience: 'none', notPassedOn: true,
    lines: [{ kind: 'own', lang: 'bn-IN', text: 'জি-ওয়ান, উনি সবচেয়ে কম কত নেবেন?', gloss: 'G-One, what is the lowest he will take?' }],
  },
  {
    id: 'answer-lowest', speaker: 'ai', audience: 'buyer', notPassedOn: true,
    note: "G-One never shares either side's limits.",
    lines: [{ kind: 'own', lang: 'bn-IN', to: 'buyer', text: 'দুঃখিত, কৃষক কী মেনে নেবেন তা আমি বলতে পারি না, আর আমি কোনো পক্ষের সীমা জানাই না। কোনো দাম দিতে চাইলে সরাসরি কৃষককে বলুন, আমি অনুবাদ করে দেব।', gloss: 'Sorry, I cannot say what the farmer will accept, and I do not share either side\'s limits. If you want to offer a price, say it to the farmer directly and I will translate.' }],
  },
  {
    id: 'low-offer', speaker: 'buyer', audience: 'farmer',
    deal: { price: 1500, unit: 'quintal' },
    lines: [
      { kind: 'own', lang: 'bn-IN', text: 'দেখুন, বাজার এখন খুব খারাপ।', gloss: 'Look, the market is very bad now.' },
      { kind: 'own', lang: 'hi-IN', text: 'पंद्रह सौ रुपये क्विंटल दे सकता हूँ।', gloss: 'I can give ₹1,500 a quintal.' },
      { kind: 'heard', lang: 'kn-IN', to: 'farmer', text: 'market ಈಗ down ಇದೆ ಅಂತಾರೆ. ಕ್ವಿಂಟಾಲಿಗೆ ಒಂದು ಸಾವಿರದ ಐನೂರು ರೂಪಾಯಿ ಕೊಡ್ತಾರಂತೆ.', gloss: 'They say the market is down now. They will give ₹1,500 a quintal.' },
    ],
  },
  {
    id: 'price-note', speaker: 'ai', audience: 'farmer', whisper: 'price_reference',
    tool: { tool: 'mandi_price', args: { commodity: 'onion', market: 'Hubballi' }, result: 'Onion modal price 1800 rupees per quintal at Hubballi, indicative sample reference dated 2026-11-06.' },
    lines: [{ kind: 'own', lang: 'kn-IN', to: 'farmer', text: 'ನವೆಂಬರ್ ಆರನೇ ತಾರೀಖಿನ sample reference ಪ್ರಕಾರ ಈರುಳ್ಳಿ ಸುಮಾರು ಒಂದು ಸಾವಿರದ ಎಂಟು ನೂರು ರೂಪಾಯಿ ಕ್ವಿಂಟಾಲಿಗೆ ಇತ್ತು. ಇದು ಇವತ್ತಿನ rate ಅಲ್ಲ, ಆದರೆ ಒಂದು ಸಾವಿರದ ಐನೂರು ಅದಕ್ಕಿಂತ ತುಂಬಾ ಕಡಿಮೆ. ಎರಡು ಸಾವಿರ ಕೇಳಿ ನೋಡಿ.', gloss: 'In the indicative sample reference dated 6 November, onion was about ₹1,800 a quintal. That is not today\'s rate, but ₹1,500 is well below it. Try asking ₹2,000.' }],
  },
  {
    id: 'counter', speaker: 'farmer', audience: 'buyer',
    lines: [
      { kind: 'own', lang: 'kn-IN', text: 'ಇಲ್ಲ, ಅಷ್ಟು ಕಡಿಮೆಗೆ ಆಗಲ್ಲ. ಕ್ವಿಂಟಾಲಿಗೆ ಎರಡು ಸಾವಿರ ಕೊಡಿ.', gloss: 'No, not that low. Give ₹2,000 a quintal.' },
      { kind: 'heard', lang: 'hi-IN', to: 'buyer', text: 'किसान कह रहे हैं कि ये बहुत कम है। वो क्विंटल के दो हज़ार रुपये माँग रहे हैं।', gloss: 'The farmer says that is too low. He is asking ₹2,000 a quintal.' },
    ],
  },
  {
    id: 'settle', speaker: 'buyer', audience: 'farmer',
    deal: { price: 1900 },
    lines: [
      { kind: 'own', lang: 'bn-IN', text: 'দু হাজার একটু বেশি হয়ে যাচ্ছে। উনিশশো টাকা দেব, এটাই শেষ কথা।', gloss: 'Two thousand is a bit much. I will give ₹1,900, that is my last word.' },
      { kind: 'heard', lang: 'kn-IN', to: 'farmer', text: 'ಎರಡು ಸಾವಿರ ಕೊಡೋಕೆ ಆಗಲ್ಲ ಅಂತಾರೆ. ಕ್ವಿಂಟಾಲಿಗೆ ಒಂದು ಸಾವಿರದ ಒಂಬೈನೂರು ರೂಪಾಯಿ ಕೊಡ್ತಾರಂತೆ, ಇದೇ last price ಅಂತೆ.', gloss: 'They say they cannot give two thousand. They will give ₹1,900 a quintal, and that is their last price.' },
    ],
  },
  {
    id: 'qr-ask', speaker: 'buyer', audience: 'none', notPassedOn: true,
    lines: [
      { kind: 'own', lang: 'hi-IN', text: 'एडवांस के लिए एक क्यूआर कोड भेज रहा हूँ।', gloss: 'For the advance I am sending a QR code.' },
      { kind: 'own', lang: 'bn-IN', text: 'স্ক্যান করে ইউপিআই পিন দিন, টাকা পেয়ে যাবেন।', gloss: 'Scan it and enter your UPI PIN, and you will get the money.' },
    ],
  },
  {
    id: 'caution', speaker: 'ai', audience: 'farmer', whisper: 'caution',
    lines: [{ kind: 'own', lang: 'kn-IN', to: 'farmer', text: 'ಹುಷಾರು. ಖರೀದಿದಾರರು QR scan ಮಾಡಿ UPI PIN ಹಾಕಿ ಅಂತಿದ್ದಾರೆ. ಹಣ ಪಡೆಯೋದಕ್ಕೆ PIN ಯಾವತ್ತೂ ಬೇಕಾಗಲ್ಲ. PIN ಯಾರಿಗೂ ಕೊಡಬೇಡಿ. ಮಾಲು ತಲುಪಿದ ಮೇಲೆ ಹಣ ತಗೊಳ್ಳಿ.', gloss: 'Careful. The buyer is asking you to scan a QR code and enter a UPI PIN. You never need a PIN to receive money. Do not give your PIN to anyone. Take the money when the goods arrive.' }],
  },
  {
    id: 'no-pin', speaker: 'farmer', audience: 'buyer',
    lines: [
      { kind: 'own', lang: 'kn-IN', text: 'PIN ನಾನು ಹಾಕಲ್ಲ. ಮಾಲು ತಲುಪಿದ ಮೇಲೆ ಹಣ ಕೊಡಿ.', gloss: 'I will not enter any PIN. Pay when the goods arrive.' },
      { kind: 'heard', lang: 'bn-IN', to: 'buyer', text: 'কৃষক ইউপিআই পিন দেবেন না। মাল পৌঁছনোর পর টাকা দিতে বলছেন।', gloss: 'The farmer will not give a UPI PIN. He is asking you to pay after the goods arrive.' },
    ],
  },
  {
    id: 'pay-later', speaker: 'buyer', audience: 'farmer',
    deal: { pickup: "Buyer's truck, pay on delivery" },
    lines: [
      { kind: 'own', lang: 'bn-IN', text: 'ঠিক আছে, আমি ট্রাক পাঠাব।', gloss: 'All right, I will send a truck.' },
      { kind: 'own', lang: 'hi-IN', text: 'माल मिलते ही पूरा पैसा दे दूँगा।', gloss: 'I will pay in full as soon as I get the goods.' },
      { kind: 'heard', lang: 'kn-IN', to: 'farmer', text: 'ಆಯ್ತು ಅಂತಾರೆ. ಅವರೇ ಲಾರಿ ಕಳಿಸ್ತಾರೆ, ಮಾಲು ಸಿಕ್ಕ ಮೇಲೆ ಪೂರ್ತಿ ಹಣ ಕೊಡ್ತಾರಂತೆ.', gloss: 'They agree. They will send a lorry, and pay in full once they get the goods.' },
    ],
  },
  {
    id: 'readback-farmer', speaker: 'ai', audience: 'farmer', whisper: 'read_back',
    deal: { state: 'readback' },
    lines: [{ kind: 'own', lang: 'kn-IN', to: 'farmer', text: 'ಒಪ್ಪಂದ ಹೇಳ್ತೀನಿ. ನಲವತ್ತು ಕ್ವಿಂಟಾಲ್ ಈರುಳ್ಳಿ, ಕ್ವಿಂಟಾಲಿಗೆ ಒಂದು ಸಾವಿರದ ಒಂಬೈನೂರು ರೂಪಾಯಿ. ಒಟ್ಟು ಎಪ್ಪತ್ತಾರು ಸಾವಿರ ರೂಪಾಯಿ. ಲಾರಿ ಅವರದು, ಮಾಲು ತಲುಪಿದ ಮೇಲೆ ಹಣ. ಸರಿನಾ?', gloss: 'Here is the deal. Forty quintals of onion at ₹1,900 a quintal. Total ₹76,000. Their lorry, money after delivery. Is that right?' }],
  },
  {
    id: 'farmer-yes', speaker: 'farmer', audience: 'none',
    deal: { state: 'confirming', farmerYes: true },
    lines: [{ kind: 'own', lang: 'kn-IN', text: 'ಹೌದು, ಸರಿ. ನನಗೆ ಒಪ್ಪಿಗೆ.', gloss: 'Yes, that is right. I agree.' }],
  },
  {
    id: 'readback-buyer', speaker: 'ai', audience: 'buyer', whisper: 'read_back',
    lines: [{ kind: 'own', lang: 'bn-IN', to: 'buyer', text: 'চল্লিশ কুইন্টাল পেঁয়াজ, কুইন্টাল প্রতি উনিশশো টাকা। মোট ছিয়াত্তর হাজার টাকা। আপনার ট্রাক, মাল পৌঁছলে টাকা। ঠিক আছে?', gloss: 'Forty quintals of onion at ₹1,900 a quintal. Total ₹76,000. Your truck, money when the goods arrive. All right?' }],
  },
  {
    id: 'buyer-yes', speaker: 'buyer', audience: 'none',
    deal: { state: 'agreed', buyerYes: true },
    note: 'Both sides said yes. The terms are recorded.',
    lines: [{ kind: 'own', lang: 'bn-IN', text: 'হ্যাঁ, ঠিক আছে। আমি রাজি।', gloss: 'Yes, fine. I agree.' }],
  },
  {
    id: 'sent-farmer', speaker: 'ai', audience: 'farmer', deliver: 'farmer',
    note: "Deal summary arrives on the farmer's phone. Simulated, nothing is sent.",
    lines: [{ kind: 'own', lang: 'kn-IN', to: 'farmer', text: 'ಧನ್ಯವಾದ ಬಸವರಾಜ್ ಅವರೇ. Deal summary ನಿಮ್ಮ ಫೋನಿಗೆ message ಮಾಡಿದ್ದೀನಿ. ಇದು ಕರೆಯಲ್ಲಿ ಹೇಳಿದ್ದರ ದಾಖಲೆ.', gloss: 'Thank you, Basavaraj. I have messaged the deal summary to your phone. It is a record of what was said on the call.' }],
  },
  {
    id: 'sent-buyer', speaker: 'ai', audience: 'buyer', deliver: 'buyer',
    note: "Deal summary arrives on the buyer's phone. Simulated, nothing is sent.",
    lines: [
      { kind: 'own', lang: 'bn-IN', to: 'buyer', text: 'ধন্যবাদ। চুক্তির সারাংশ আপনার ফোনে মেসেজ করে দিয়েছি। এই কলে যা কথা হয়েছে, সেটাই লেখা আছে।', gloss: 'Thank you. I have messaged the deal summary to your phone. It records what was said on this call.' },
    ],
  },
  {
    id: 'ask-end', speaker: 'ai', audience: 'both',
    lines: [
      { kind: 'own', lang: 'kn-IN', to: 'farmer', text: 'ಕರೆಯನ್ನು ಮುಗಿಸಲು ಬಯಸುತ್ತೀರಾ? ದಯವಿಟ್ಟು ಹೌದು ಅಥವಾ ಇಲ್ಲ ಎಂದು ಹೇಳಿ.', gloss: 'Would you like to end the call? Please say yes or no.' },
      { kind: 'own', lang: 'bn-IN', to: 'buyer', text: 'আপনি কি কলটা শেষ করতে চান? দয়া করে হ্যাঁ বা না বলুন।', gloss: 'Would you like to end the call? Please say yes or no.' },
    ],
  },
  {
    id: 'end-yes-farmer', speaker: 'farmer', audience: 'none',
    lines: [{ kind: 'own', lang: 'kn-IN', text: 'ಹೌದು, ಮುಗಿಸೋಣ.', gloss: 'Yes, let us finish.' }],
  },
  {
    id: 'end-yes-buyer', speaker: 'buyer', audience: 'none',
    lines: [{ kind: 'own', lang: 'bn-IN', text: 'হ্যাঁ, শেষ করুন।', gloss: 'Yes, end it.' }],
  },
  {
    id: 'ask-save', speaker: 'ai', audience: 'both',
    lines: [
      { kind: 'own', lang: 'kn-IN', to: 'farmer', text: 'ಮುಂದಿನ ಉಪಯೋಗಕ್ಕಾಗಿ ಈ ಮಾತುಕತೆಯ ಮುಖ್ಯ ಅಂಶಗಳನ್ನು ನಾವು ನೆನಪಿಟ್ಟುಕೊಳ್ಳಬಹುದೇ? ದಯವಿಟ್ಟು ಹೌದು ಅಥವಾ ಇಲ್ಲ ಎಂದು ಹೇಳಿ.', gloss: 'May we remember the main points of this conversation for next time? Please say yes or no.' },
      { kind: 'own', lang: 'bn-IN', to: 'buyer', text: 'ভবিষ্যতের জন্য আমরা কি এই কথাবার্তার মূল কথাগুলো মনে রাখতে পারি? দয়া করে হ্যাঁ বা না বলুন।', gloss: 'May we remember the main points of this conversation for the future? Please say yes or no.' },
    ],
  },
  {
    id: 'save-yes-farmer', speaker: 'farmer', audience: 'none',
    lines: [{ kind: 'own', lang: 'kn-IN', text: 'ಹೌದು, ಇಟ್ಕೊಳ್ಳಿ.', gloss: 'Yes, keep it.' }],
  },
  {
    id: 'save-yes-buyer', speaker: 'buyer', audience: 'none',
    note: 'Both sides said yes to remembering the main points.',
    lines: [{ kind: 'own', lang: 'bn-IN', text: 'হ্যাঁ, রাখতে পারেন।', gloss: 'Yes, you may keep them.' }],
  },
  {
    id: 'goodbye', speaker: 'ai', audience: 'both', endsCall: true,
    lines: [
      { kind: 'own', lang: 'kn-IN', to: 'farmer', text: 'ಧನ್ಯವಾದಗಳು. ನಮಸ್ಕಾರ.', gloss: 'Thank you. Namaskara.' },
      { kind: 'own', lang: 'bn-IN', to: 'buyer', text: 'ধন্যবাদ। নমস্কার।', gloss: 'Thank you. Namaskar.' },
    ],
  },
]

/** The message each side finds on their phone at the end. Numbers stay in digits, the way a slip is written. */
const DISCLAIMER = 'Recorded by G-One from the call. Not a contract.'
const CALL_REF = 'GO-4F7A'
const SLIP_DATE = '2026-11-07'
const PHONES = {
  farmer: {
    lang: 'kn', labelLang: 'en', lockTime: '10:48', lockDate: 'ಶನಿವಾರ, 7 ನವೆಂಬರ್',
    notice: 'ಡೀಲ್ ಸಾರಾಂಶ ಬಂದಿದೆ', title: 'Sauda Parchi', subtitle: 'ಡೀಲ್ ಸಾರಾಂಶ',
    rows: [
      { label: 'Crop', value: 'ಈರುಳ್ಳಿ' },
      { label: 'Quantity', value: '40 ಕ್ವಿಂಟಾಲ್' },
      { label: 'Rate', value: '₹1,900 / ಕ್ವಿಂಟಾಲ್' },
      { label: 'Total', value: '₹76,000' },
      { label: 'Pickup', value: 'ಖರೀದಿದಾರರ ಲಾರಿ' },
      { label: 'Payment', value: 'ಮಾಲು ತಲುಪಿದ ಮೇಲೆ ಹಣ' },
      { label: 'Date', value: SLIP_DATE },
      { label: 'Call ID', value: CALL_REF },
    ],
    note: 'ಕರೆಯಲ್ಲಿ ಹೇಳಿದ್ದರ ದಾಖಲೆ. ಇದು ಒಪ್ಪಂದ ಪತ್ರ ಅಲ್ಲ.',
    noteGloss: 'A record of what was said on the call. This is not a contract document.',
  },
  buyer: {
    lang: 'bn', labelLang: 'hi', lockTime: '10:48', lockDate: 'শনিবার, 7 নভেম্বর',
    notice: 'চুক্তির সারাংশ এসেছে', title: 'Sauda Parchi', subtitle: 'চুক্তির সারাংশ',
    rows: [
      { label: 'फसल', value: 'পেঁয়াজ' },
      { label: 'मात्रा', value: '40 কুইন্টাল' },
      { label: 'भाव', value: '₹1,900 / কুইন্টাল' },
      { label: 'कुल रकम', value: '₹76,000' },
      { label: 'ढुलाई', value: 'আপনার ট্রাক' },
      { label: 'भुगतान', value: 'মাল পৌঁছলে টাকা' },
      { label: 'तारीख', value: SLIP_DATE },
      { label: 'कॉल आईडी', value: CALL_REF },
    ],
    note: 'ফোনে যা কথা হয়েছে তার লিখিত বিবরণ। এটা চুক্তিপত্র নয়।',
    noteGloss: 'A written account of what was said on the call. This is not a contract document.',
  },
}

const OUT = join(import.meta.dir, '..', 'public', 'demo', 'voiced-sample')
const RAW = process.env.TTS_CACHE_DIR ?? join(tmpdir(), 'g-one-voiced-sample-raw')
const MAX_CALLS = Number(process.env.MAX_TTS_CALLS ?? 40)
/** Writes the manifest to this path without touching Timbre or the audio folder, so the text can be checked first. */
const DRY_OUT = process.env.DRY_OUT
const CLIP_GAP_MS = 220
const BEAT_GAP_MS = 320
const PAN = { farmer: -0.75, buyer: 0.75, whisper: -1 }

let calls = 0

function hashOf(parts: string[]): string {
  return createHash('sha256').update(parts.join('|')).digest('hex').slice(0, 8)
}

async function synthesize(text: string, voice: string, language: string, hash: string): Promise<Uint8Array> {
  const cached = join(RAW, `${hash}.wav`)
  if (existsSync(cached)) return new Uint8Array(await Bun.file(cached).arrayBuffer())
  const key = process.env.GNANI_API_KEY
  if (!key) throw new Error('GNANI_API_KEY is not set and this line is not cached')
  for (let attempt = 0; attempt < 4; attempt++) {
    if (calls >= MAX_CALLS) throw new Error(`Stopped at the ${MAX_CALLS} call budget`)
    calls += 1
    const response = await fetch('https://api.vachana.ai/api/v1/tts/inference', {
      method: 'POST',
      headers: { 'X-API-Key-ID': key, 'content-type': 'application/json' },
      body: JSON.stringify({ text, model: 'timbre-v2.5', voice, language, audio_config: { sample_rate: 24000, num_channels: 1, sample_width: 2, encoding: 'linear_pcm', container: 'wav' } }),
    })
    if (response.status === 429) {
      await Bun.sleep(5000 * (attempt + 1))
      continue
    }
    if (!response.ok) throw new Error(`Timbre answered ${response.status} for ${hash}`)
    const bytes = new Uint8Array(await response.arrayBuffer())
    await Bun.write(cached, bytes)
    await Bun.sleep(2500)
    return bytes
  }
  throw new Error(`Timbre kept rate limiting ${hash}`)
}

/** Finds the PCM in a WAV whose RIFF and data lengths may be the open-ended 0xffffffff. */
function pcmOf(wav: Uint8Array): { rate: number; samples: Int16Array } {
  const view = new DataView(wav.buffer, wav.byteOffset, wav.byteLength)
  const tag = (at: number) => String.fromCharCode(wav[at], wav[at + 1], wav[at + 2], wav[at + 3])
  if (tag(0) !== 'RIFF') throw new Error('Not a WAV')
  let at = 12
  let rate = 24000
  while (at + 8 <= wav.length) {
    const id = tag(at)
    const size = view.getUint32(at + 4, true)
    if (id === 'fmt ') rate = view.getUint32(at + 12, true)
    if (id === 'data') {
      const available = wav.length - at - 8
      const bytes = (size === 0xffffffff || size > available ? available : size) & ~1
      const copy = wav.slice(at + 8, at + 8 + bytes)
      return { rate, samples: new Int16Array(copy.buffer, copy.byteOffset, bytes / 2) }
    }
    at += 8 + size + (size & 1)
  }
  throw new Error('WAV has no data chunk')
}

/** Trims the silence around a line and brings every voice to one loudness, so no speaker jumps out. */
function master(samples: Int16Array, rate: number): Int16Array {
  const floor = 300
  let start = samples.findIndex((s) => Math.abs(s) > floor)
  let end = samples.length - 1
  while (end > 0 && Math.abs(samples[end]) <= floor) end--
  if (start < 0) throw new Error('Silent clip')
  start = Math.max(0, start - Math.round(rate * 0.04))
  end = Math.min(samples.length - 1, end + Math.round(rate * 0.09))
  const clip = samples.slice(start, end + 1)
  let energy = 0
  let peak = 1
  for (const s of clip) {
    energy += s * s
    peak = Math.max(peak, Math.abs(s))
  }
  const rms = Math.sqrt(energy / clip.length)
  const gain = Math.min((32768 * 0.14) / rms, (32768 * 0.89) / peak)
  const fade = Math.round(rate * 0.012)
  for (let i = 0; i < clip.length; i++) {
    const edge = Math.min(1, i / fade, (clip.length - 1 - i) / fade)
    clip[i] = Math.round(clip[i] * gain * edge)
  }
  return clip
}

/** A WAV whose RIFF and data lengths are correct, which is what browsers can decode. */
function wavOf(samples: Int16Array, rate: number): Uint8Array {
  const out = new Uint8Array(44 + samples.byteLength)
  const view = new DataView(out.buffer)
  const write = (at: number, text: string) => [...text].forEach((c, i) => (out[at + i] = c.charCodeAt(0)))
  write(0, 'RIFF')
  view.setUint32(4, 36 + samples.byteLength, true)
  write(8, 'WAVEfmt ')
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true)
  view.setUint16(22, 1, true)
  view.setUint32(24, rate, true)
  view.setUint32(28, rate * 2, true)
  view.setUint16(32, 2, true)
  view.setUint16(34, 16, true)
  write(36, 'data')
  view.setUint32(40, samples.byteLength, true)
  out.set(new Uint8Array(samples.buffer, samples.byteOffset, samples.byteLength), 44)
  return out
}

const LANGUAGE_NAME: Record<string, string> = { 'kn-IN': 'Kannada', 'hi-IN': 'Hindi', 'en-IN': 'English', 'bn-IN': 'Bengali' }

/** The text for Timbre: English words in a Kannada line are respelled in Kannada. */
function spokenOf(line: Line): string {
  if (line.lang !== 'kn-IN') return line.text
  return line.text.replace(/[A-Za-z]+/g, (word) => {
    const spelled = KN_SPOKEN[word.toLowerCase()]
    if (!spelled) throw new Error(`No Kannada spelling for "${word}"`)
    return spelled
  })
}

function languagesOf(line: Line): string[] {
  const base = LANGUAGE_NAME[line.lang]
  if (!base) throw new Error(`No name for ${line.lang}`)
  return line.lang === 'kn-IN' && /[A-Za-z]/.test(line.text) ? [base, 'English'] : [base]
}

function voiceFor(beat: Beat, line: Line): string {
  if (line.kind === 'heard' || beat.speaker === 'ai') {
    const voice = VOICES.ai[line.lang]
    if (!voice) throw new Error(`No G-One voice for ${line.lang}`)
    return voice
  }
  return VOICES[beat.speaker].voice
}

function panFor(beat: Beat, line: Line): number {
  if (beat.whisper && beat.whisper !== 'read_back' && line.to === 'farmer') return PAN.whisper
  const side: Party = line.to ?? (beat.speaker as Party)
  return PAN[side]
}

function hearingFor(beat: Beat, line: Line): Record<Party, Hearing> {
  const speaker = line.kind === 'own' && beat.speaker !== 'ai' ? beat.speaker : null
  const party = (who: Party): Hearing => (speaker === who ? 'speaking' : line.to === who ? 'hears' : 'silent')
  return { farmer: party('farmer'), buyer: party('buyer') }
}

mkdirSync(OUT, { recursive: true })
mkdirSync(RAW, { recursive: true })

const kept = new Set<string>(['manifest.json'])
const beats = []
let totalMs = 0

for (const beat of BEATS) {
  const clips = []
  for (const [index, line] of beat.lines.entries()) {
    const voice = voiceFor(beat, line)
    const spoken = spokenOf(line)
    const hash = hashOf(['timbre-v2.5', voice, line.lang, spoken])
    const file = `${beat.id}-${index + 1}-${hash}.wav`
    kept.add(file)
    let durationMs = 0
    if (!DRY_OUT) {
      const target = join(OUT, file)
      // A committed clip with this name is already mastered for the same text and voice: reuse it as it is.
      if (existsSync(target)) {
        const done = pcmOf(new Uint8Array(await Bun.file(target).arrayBuffer()))
        durationMs = Math.round((done.samples.length / done.rate) * 1000)
      } else {
        const raw = pcmOf(await synthesize(spoken, voice, line.lang, hash))
        const samples = master(raw.samples, raw.rate)
        await Bun.write(target, wavOf(samples, raw.rate))
        durationMs = Math.round((samples.length / raw.rate) * 1000)
      }
    }
    totalMs += durationMs
    clips.push({
      id: `${beat.id}-${index + 1}`,
      kind: line.kind === 'heard' ? 'heard' : beat.speaker === 'ai' ? 'said' : 'original',
      language: line.lang,
      languages: languagesOf(line),
      text: line.text,
      spoken: spoken === line.text ? null : spoken,
      gloss: line.gloss ?? null,
      to: line.to ?? null,
      voice,
      file,
      durationMs,
      pan: panFor(beat, line),
      hearing: hearingFor(beat, line),
      pauseAfterMs: index === beat.lines.length - 1 ? BEAT_GAP_MS : CLIP_GAP_MS,
    })
    totalMs += clips.at(-1)!.pauseAfterMs
  }
  beats.push({
    id: beat.id,
    speakerRole: beat.speaker,
    audience: beat.audience,
    whisperKind: beat.whisper ?? null,
    notPassedOn: beat.notPassedOn ?? false,
    buyerJoins: beat.buyerJoins ?? false,
    endsCall: beat.endsCall ?? false,
    deliver: beat.deliver ?? null,
    tool: beat.tool ?? null,
    note: beat.note ?? null,
    dealPatch: beat.deal ?? null,
    clips,
  })
}

if (!DRY_OUT) for (const name of readdirSync(OUT)) if (!kept.has(name)) rmSync(join(OUT, name))

const manifest = {
  version: 1,
  label: 'Scripted sample, synthetic voices',
  sampleRate: 24000,
  voices: { farmer: VOICES.farmer.voice, buyer: VOICES.buyer.voice, ai: VOICES.ai },
  farmer: { name: 'Basavaraj', language: 'kn-IN', languages: ['Kannada', 'English'] },
  buyer: { name: 'Mondal Traders', language: 'bn-IN', languages: ['Bengali', 'Hindi'] },
  phones: PHONES,
  disclaimer: DISCLAIMER,
  memoryBefore: {
    facts: [
      { field: 'district', value: 'Gadag' },
      { field: 'main_crop', value: 'onion' },
      { field: 'languages', value: 'Kannada, some English' },
    ],
    gist: { daysAgo: 12, summary: 'Asked the onion rate at Hubballi. Did not sell yet.' },
  },
  memoryAfter: { summary: 'Sold 40 quintals of onion at ₹1,900 to Mondal Traders, pay on delivery. Refused a UPI PIN request.' },
  totalMs,
  beats,
}
await Bun.write(DRY_OUT ?? join(OUT, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`)
console.log(`${beats.length} beats, ${beats.reduce((n, b) => n + b.clips.length, 0)} clips, ${(totalMs / 1000).toFixed(1)} s, ${calls} Timbre calls this run`)
