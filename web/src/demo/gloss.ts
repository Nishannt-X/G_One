/**
 * English (or Hindi) for the sample call's lines that the feed does not translate, so a viewer can follow them. Used for
 * sample calls only: on a live call, a line appears with a translation only if the engine produced one.
 */
const GLOSS: Record<string, { lang: string; text: string }> = {
  'नमस्ते! पिछली बार आपने प्याज का भाव पूछा था। आज क्या बेचना है?': { lang: 'en', text: 'Hello! Last time you asked about the onion rate. What would you like to sell today?' },
  'नमस्ते, आज मुझे प्याज बेचना है, करीब दस क्विंटल।': { lang: 'en', text: 'Hello, I want to sell onions today, about ten quintals.' },
  'लासलगांव में प्याज का भाव करीब इक्कीस सौ पचास रुपये क्विंटल था। इससे कम पर जल्दी हाँ मत कहिए।': { lang: 'en', text: "The onion rate at Lasalgaon was about ₹2,150 a quintal. Don't agree quickly to less than that." },
  'ठीक है, मैं एक खरीदार को लाइन पर लेता हूँ।': { lang: 'en', text: 'All right, I will bring a buyer on the line.' },
  'यह भाव सैंपल मंडी भाव के बराबर है। चाहें तो उठाने की जगह और दिन भी तय कर लीजिए।': { lang: 'en', text: 'This offer matches the sample mandi rate. If you like, also settle the pickup place and day.' },
  'Yes, agreed. We will come tomorrow morning.': { lang: 'hi', text: 'हाँ, तय है। हम कल सुबह आएँगे।' },
}

export function sampleGloss(text: string): { lang: string; text: string } | null {
  return GLOSS[text] ?? null
}
